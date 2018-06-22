package com.r3.demos.ubin2a.cash

import co.paralleluniverse.fibers.Suspendable
import com.r3.demos.ubin2a.base.*
import com.r3.demos.ubin2a.cash.TransferTransaction.Companion.TRANSFER_TRANSACTION_CONTRACT_ID
import com.r3.demos.ubin2a.obligation.GetQueue
import com.r3.demos.ubin2a.obligation.IssueObligation
import com.r3.demos.ubin2a.obligation.Obligation
import com.r3.demos.ubin2a.obligation.PostIssueObligationFlow
import net.corda.confidential.IdentitySyncFlow
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalances
import net.corda.finance.flows.CashIssueFlow
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.utilities.*
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.net.Socket

// TODO: Remove this and just use the built in cash issue flow.
@StartableByRPC
@InitiatingFlow
class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {

    companion object {
        object PREPARING : ProgressTracker.Step("Preparing to self issue cash.")
        object ISSUING : ProgressTracker.Step("Issuing cash")

        fun tracker() = ProgressTracker(PREPARING, ISSUING)
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): Cash.State {
        progressTracker.currentStep = PREPARING
        val issueRef = OpaqueBytes.of(0)
        val notary = serviceHub.networkMapCache.notaryIdentities.firstOrNull()
                ?: throw IllegalStateException("No available notary.")
        progressTracker.currentStep = ISSUING
        val cashIssueTransaction = subFlow(CashIssueFlow(amount, issueRef, notary))
        return cashIssueTransaction.stx.tx.outputs.single().data as Cash.State
    }
}

/**
 * Flow to send digital currencies to the other party
 */
@StartableByRPC
@InitiatingFlow
class Pay(val otherParty: Party,
          val amount: Amount<Currency>,
          val priority: Int,
          val anonymous: Boolean = true) : FlowLogic<SignedTransaction>() {

    companion object {
        object BUILDING : ProgressTracker.Step("Building payment to be sent to other party")
        object SIGNING : ProgressTracker.Step("Signing the payment")
        object COLLECTING : ProgressTracker.Step("Collecting signature from the counterparty") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : ProgressTracker.Step("Finalising the transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(BUILDING, SIGNING, COLLECTING, FINALISING)
    }

    override val progressTracker: ProgressTracker = tracker()
    @Suspendable
    override fun call(): SignedTransaction {
        logger.info("Pay: Building pay transaction")
        val priorityIsValid = OBLIGATION_PRIORITY.values().map { it.ordinal }.contains(priority)
        if (!priorityIsValid) throw IllegalArgumentException("Priority given is invalid")
        val notary = serviceHub.networkMapCache.notaryIdentities.firstOrNull()
                ?: throw IllegalStateException("No available notary.")
        // Exchange certs and keys.
        val txIdentities = if (anonymous) {
            subFlow(SwapIdentitiesFlow(otherParty))
        } else {
            emptyMap<Party, AnonymousParty>()
        }
        val maybeAnonymousOtherParty = txIdentities[otherParty] ?: otherParty
        progressTracker.currentStep = BUILDING
        val builder = TransactionBuilder(notary = notary)
        // Check we have enough cash to transfer the requested amount in a try block
        try {
            serviceHub.getCashBalances()[amount.token]?.let { if (it < amount) throw InsufficientBalanceException(amount - it) }
            // Check to see if any higher priorities obligation in queue
            // if higher priority obligations exist in queue than current transfer priority, put in queue
            val higherPriorityNotEmpty = subFlow(GetQueue.OutgoingUnconsumed()).any { it.priority >= priority }
            if (higherPriorityNotEmpty) throw IllegalArgumentException("Higher priority payments exist in queue.")
            // else continue to generate spend
            val (spendTx, keysForSigning) = Cash.generateSpend(serviceHub, builder, amount, maybeAnonymousOtherParty)

            // Verify and sign the transaction
            builder.verify(serviceHub)
            val currentTime = serviceHub.clock.instant()
            builder.setTimeWindow(currentTime, 30.seconds)

            // Sign the transaction.
            progressTracker.currentStep = SIGNING
            val partSignedTx = serviceHub.signInitialTransaction(spendTx, keysForSigning)
            val session = initiateFlow(otherParty)
            subFlow(IdentitySyncFlow.Send(session, partSignedTx.tx))

            // Collect signatures
            progressTracker.currentStep = COLLECTING
            // Finalise the transaction
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(partSignedTx, FINALISING.childProgressTracker()))
            // Handle not enough cash by automatically issuing Obligation to lender
        } catch (ex: Exception) {
            return if (ex is InsufficientBalanceException || (ex is IllegalArgumentException && ex.message == "Higher priority payments exist in queue.")) {
                val lender = otherParty
                subFlow(IssueObligation.Initiator(amount, lender, priority))
            } else {
                logger.error("Exception CashFlows.Pay: ${ex.message.toString()}")
                throw ex
            }
        }
    }
}

/**
 * The other side of the above flow. For the purposes of this PoC, we won't add any additional checking.
 */
@InitiatingFlow
@InitiatedBy(Pay::class)
class AcceptPayment(private val otherFlow: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        logger.info("Pay.AcceptPayment: Syncing identities")
        subFlow(IdentitySyncFlow.Receive(otherFlow))
    }
}

/**
 * Flow for API to call to post transfer transactions
 */
@StartableByRPC
@InitiatingFlow
class PostTransfersFlow(val value: TransactionModel) : FlowLogic<TransactionModel>() {
    @Suspendable
    override fun call(): TransactionModel {
        logger.info("PostTransfersFlow: Running logic to determine to settle pay immediately or putting in queue")
        if (value.enqueue == 1) {
            return subFlow(PostIssueObligationFlow(value))
        }
        val maybeOtherParty = serviceHub.identityService.partiesFromName(value.receiver!!, exactMatch = true)
        if (maybeOtherParty.size != 1) throw IllegalArgumentException("Unknown Party")
        if (maybeOtherParty.first() == ourIdentity) throw IllegalArgumentException("Failed requirement: The payer and payee cannot be the same identity")
        val otherParty = maybeOtherParty.single()
        val transferAmount = KRW(value.transactionAmount!!)
        val stx = subFlow(Pay(otherParty, transferAmount, value.priority!!))

        // If Pay flow successfully moved cash state, the output state is of type Cash.State
        // Model the response based on successful transfer fo funds
        // Else model the response based on Obligation.State that was issued
        val txId = stx.tx.id
        when {
            stx.tx.commands.none { it.value == (Obligation.Issue()) } -> {
                logger.info("PostTransfersFlow: Transfer successful. Returning transaction details")
                val state = stx.tx.outputsOfType<Cash.State>().first()
                val sender = serviceHub.identityService.partyFromKey(stx.tx.commands.first().signers.first())!!
                val receiver = serviceHub.identityService.requireWellKnownPartyFromAnonymous(state.owner)
                return TransactionModel(
                        transId = txId.toString(),
                        sender = sender.name.organisation,
                        receiver = receiver.name.organisation,
                        transactionAmount = state.amount.quantity.to2Decimals(),
                        currency = state.amount.token.product.toString(),
                        status = OBLIGATION_STATUS.SETTLED.name.toLowerCase(),
                        priority = value.priority)
            }
            stx.tx.commands.any { it.value == (Obligation.Issue()) } -> {
                logger.info("PostTransfersFlow: Queue successful. Returning transaction details")
                val state = stx.tx.outputsOfType<Obligation.State>().first()
                val sender = serviceHub.identityService.requireWellKnownPartyFromAnonymous(state.borrower)
                val receiver = serviceHub.identityService.requireWellKnownPartyFromAnonymous(state.lender)
                return TransactionModel(
                        transId = txId.toString(),
                        linearId = state.linearId.toString(),
                        sender = sender.name.organisation,
                        receiver = receiver.name.organisation,
                        transactionAmount = state.amount.quantity.to2Decimals(),
                        currency = state.amount.token.currencyCode.toString(),
                        status = OBLIGATION_STATUS.ACTIVE.name.toLowerCase(),
                        priority = value.priority)
            }
            else -> throw IllegalStateException("Unexpected State exception: " + stx.tx.outputs.first())
        }
    }
}

/**
* 지급정산 코드 부분
**/

/**
 * Flow to send digital currencies to the other party
 */
@StartableByRPC
@InitiatingFlow
class PayDemo(val value: TransferTransactionModel,
              val anonymous: Boolean = true) : FlowLogic<TransferTransactionModel>() {

    companion object {
        object BUILDING : ProgressTracker.Step("Building payment to be sent to other party")
        object SIGNING : ProgressTracker.Step("Signing the payment")
        object COLLECTING : ProgressTracker.Step("Collecting signature from the counterparty") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : ProgressTracker.Step("Finalising the transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(BUILDING, SIGNING, COLLECTING, FINALISING)
    }

    override val progressTracker: ProgressTracker = tracker()
    @Suspendable
    override fun call(): TransferTransactionModel {
        logger.info("PayDemo: Building pay transaction")
        val notary = serviceHub.networkMapCache.notaryIdentities.firstOrNull()
                ?: throw IllegalStateException("No available notary.")
        // Exchange certs and keys.
        val otherParty = value.receiverBankParty!!
        val txIdentities = if (anonymous) {
            subFlow(SwapIdentitiesFlow(otherParty))
        } else {
            emptyMap<Party, AnonymousParty>()
        }
        val maybeAnonymousOtherParty = txIdentities[otherParty] ?: otherParty
        progressTracker.currentStep = BUILDING
        val builder = TransactionBuilder(notary = notary)
        // Check we have enough cash to transfer the requested amount in a try block
        try {
            val amount = value.transferAmount!!
            serviceHub.getCashBalances()[amount.token]?.let { if (it < amount) throw InsufficientBalanceException(amount - it) }

            val (spendTx, keysForSigning) = Cash.generateSpend(serviceHub, builder, amount, maybeAnonymousOtherParty)
            val transferTransactionState = TransferTransaction.State(transId = value.transId!!, msgCode = value.msgCode!!,
                    workCode = value.workCode!!, senderBankCode = value.senderBankCode!!, senderBankBranchCode = value.senderBankBranchCode!!,
                    receiverBankCode = value.receiverBankCode!!, senderBankRequestDate = value.senderBankRequestDate!!, senderBankRequestTime = value.senderBankRequestTime!!,
                    receiverAccountNum = value.receiverAccountNum!!, amount = amount, senderAccountNum = value.senderAccountNum!!, receiverName = value.receiverName!!,
                    senderName = value.senderName!!, sender = serviceHub.myInfo.legalIdentities.first(), receiver = maybeAnonymousOtherParty)

            builder.addOutputState(transferTransactionState, TRANSFER_TRANSACTION_CONTRACT_ID)
                    .addCommand(TransferTransaction.Issue(), keysForSigning.map { it })

            // Verify and sign the transaction
            builder.verify(serviceHub)
            val currentTime = serviceHub.clock.instant()
            builder.setTimeWindow(currentTime, 30.seconds)

            // Sign the transaction.
            progressTracker.currentStep = SIGNING
            val partSignedTx = serviceHub.signInitialTransaction(spendTx, keysForSigning)
            val session = initiateFlow(otherParty)


            //TODO: 응답 flow로 데이터 보내기
            session.send(value)
            //TODO: 응답 flow로부터 데이터 받아오기
            val responseData = session.receive<TransferTransactionModel>()
            var resTransferTxModel = TransferTransactionModel()
            responseData.unwrap { data ->
                resTransferTxModel = data
            }
//            subFlow(IdentitySyncFlow.Send(session, partSignedTx.tx))


            // Collect signatures
            progressTracker.currentStep = COLLECTING
            // Finalise the transaction
            progressTracker.currentStep = FINALISING
            subFlow(FinalityFlow(partSignedTx, FINALISING.childProgressTracker()))

            return resTransferTxModel
            // Handle not enough cash by automatically issuing Obligation to lender
        } catch (ex: Exception) {
            logger.error("Exception CashFlows.PayDemo: ${ex}")
            logger.error("Exception CashFlows.PayDemo: ${ex.message.toString()}")
            ex.printStackTrace()
            throw ex
        }
    }
}

/**
 * The other side of the above flow. For the purposes of this PoC, we won't add any additional checking.
 */
@InitiatingFlow
@InitiatedBy(PayDemo::class)
class AcceptPaymentDemo(private val session: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        try {
            logger.info("PayDemo.AcceptPaymentDemo: Syncing identities")
            val untrustworthyData = session.receive<TransferTransactionModel>()
            var transferTxModel = TransferTransactionModel()
            untrustworthyData.unwrap({
                transferTxModel = it
            })
//            val resTransferTxModel = this.connectLegacyBankSWForTransfer(transferTxModel)
            val resTransferTxModel = this.connectLegacyBankSWForTransferByOkHTTP(transferTxModel)
//            val resTransferTxModel = this.connectLegacyBankSWTCP(transferTxModel)
            println("resTransferTxModel: ".plus(resTransferTxModel))
            when {
                !resTransferTxModel.status.equals(TRANSFER_PROCESSING_RESPONSE_CODE) && !resTransferTxModel.status.equals(TRANSFER_SUCCESS_RESPONSE_CODE) -> {
                    throw IllegalStateException("Legacy SW fail exception ")
                }
            }
            session.send(resTransferTxModel)
//            subFlow(IdentitySyncFlow.Receive(session))
        } catch (ex: Exception) {
            println("PayDemo.AcceptPaymentDemo: ".plus(ex.message.toString()))
            logger.error("PayDemo.AcceptPaymentDemo: ".plus(ex.message.toString()))
            logger.error("PayDemo.AcceptPaymentDemo: ".plus(ex))
        }
    }

    private fun connectLegacyBankSWTCP(value: TransferTransactionModel) : TransferTransactionModel {
        val socket = Socket("10.100.10.66", 1337)

        val outputStream = socket.getOutputStream()
        val osw = OutputStreamWriter(outputStream)
        val jsonParam = JSONObject()
        jsonParam["RETVAL_REF_NUM"] = value.transId
        jsonParam["MSG_TYPE"] = value.msgCode
        jsonParam["PROC_CD"] = value.workCode
        jsonParam["ACQ_BK"] = value.senderBankCode
        jsonParam["ACQ_BR"] = value.senderBankBranchCode
        jsonParam["ISS_BK"] = value.receiverBankCode
        jsonParam["ACQ_REQ_DATE"] = value.senderBankRequestDate
        jsonParam["ACQ_REQ_TIME"] = value.senderBankRequestTime
        jsonParam["ACCT_NUM"] = value.receiverAccountNum
        jsonParam["TRAN_AMT"] = value.amount
        jsonParam["SND_ACCT_NUM"] = value.senderAccountNum
        jsonParam["SND_NAME"] = value.senderName
        jsonParam["RCV_NAME"] = value.receiverName

//        osw.write(jsonParam.toString())
        osw.flush()

        val input = socket.getInputStream()
        val reader = BufferedReader(InputStreamReader(input))
        var inputLine = reader.readLine()
//        val response = ""
//        while (inputLine != null) {
//            response.plus(inputLine)
//            inputLine = reader.readLine()
//        }
//        val jsonParser = JSONParser()
//        val resMessage = inputLine
//        val resJSONObject = jsonParser.parse(resMessage) as JSONObject
//        println("resJSONObject: ".plus(resJSONObject))
//
//        value.receiverBankBranchCode = resJSONObject["ISS_BR"] as String
//        value.status = resJSONObject["RESP_CD"] as String

        value.receiverBankBranchCode = "088190"
        value.status = TRANSFER_SUCCESS_RESPONSE_CODE

        val currentTime = LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul"))
        DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
        value.receiverBankRequestDate = DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
        value.receiverBankRequestTime = DateTimeFormatter.ofPattern("HHmmss").format(currentTime)

        return value
    }

    private fun readFile() {
        val inFile = Scanner(FileReader("D:\\workspace\\ubin-corda-master\\test.txt"))
        val str = inFile.next()
        println("Input File: ".plus(str))
        logger.error("Print!! ".plus(str))
    }

    private fun connectLegacyBankSWForTransferByOkHTTP(value:TransferTransactionModel) : TransferTransactionModel {
        try {
            val url = BANK_SIMULATOR_URL.plus(TRANSFER_URL)
            val client = OkHttpClient()
            val jsonParam = JSONObject()
            jsonParam["RETVAL_REF_NUM"] = value.transId
            jsonParam["MSG_TYPE"] = value.msgCode
            jsonParam["PROC_CD"] = value.workCode
            jsonParam["ACQ_BK"] = value.senderBankCode
            jsonParam["ACQ_BR"] = value.senderBankBranchCode
            jsonParam["ISS_BK"] = value.receiverBankCode
            jsonParam["ACQ_REQ_DATE"] = value.senderBankRequestDate
            jsonParam["ACQ_REQ_TIME"] = value.senderBankRequestTime
            jsonParam["ACCT_NUM"] = value.receiverAccountNum
            jsonParam["TRAN_AMT"] = value.amount
            jsonParam["SND_ACCT_NUM"] = value.senderAccountNum
            jsonParam["SND_NAME"] = value.senderName
            jsonParam["RCV_NAME"] = value.receiverName

            val body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), jsonParam.toString())
            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()

            val jsonParser = JSONParser()
            val resJSONObject = jsonParser.parse(response.body()?.string()) as JSONObject
            value.receiverBankBranchCode = resJSONObject["ISS_BR"] as String
            value.status = resJSONObject["RESP_CD"] as String

            val currentTime = LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul"))
            DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
            value.receiverBankRequestDate = DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
            value.receiverBankRequestTime = DateTimeFormatter.ofPattern("HHmmss").format(currentTime)

        } catch(ex: Exception) {
            println("connectLegacyBankSWForTransferByOkHTTP: ".plus(ex))
            println("connectLegacyBankSWForTransferByOkHTTP: ".plus(ex.message.toString()))
            logger.error("connectLegacyBankSWForTransferByOkHTTP: ".plus(ex.message.toString()))
        } finally {
            println("connectLegacyBankSWForTransferByOkHTTP: Finally")
            return value
        }
    }

    private fun connectLegacyBankSWForTransfer(value:TransferTransactionModel) : TransferTransactionModel {
        try {
            val url = BANK_SIMULATOR_URL.plus(TRANSFER_URL)
            val urlObj = URL(url)
            var resMessage = ""
            val jsonParser = JSONParser()
            var resJSONObject = JSONObject()

            with(urlObj.openConnection() as HttpURLConnection) {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000 //ms
                setRequestProperty("charset", "utf-8")
                setRequestProperty("Content-Type", "application/json")

                println("----------------Starting Legacy Connection Test----------------")
                val jsonParam = JSONObject()
                jsonParam["RETVAL_REF_NUM"] = value.transId
                jsonParam["MSG_TYPE"] = value.msgCode
                jsonParam["PROC_CD"] = value.workCode
                jsonParam["ACQ_BK"] = value.senderBankCode
                jsonParam["ACQ_BR"] = value.senderBankBranchCode
                jsonParam["ISS_BK"] = value.receiverBankCode
                jsonParam["ACQ_REQ_DATE"] = value.senderBankRequestDate
                jsonParam["ACQ_REQ_TIME"] = value.senderBankRequestTime
                jsonParam["ACCT_NUM"] = value.receiverAccountNum
                jsonParam["TRAN_AMT"] = value.amount
                jsonParam["SND_ACCT_NUM"] = value.senderAccountNum
                jsonParam["SND_NAME"] = value.senderName
                jsonParam["RCV_NAME"] = value.receiverName

                val os = outputStream
                val osw = OutputStreamWriter(os)
                osw.write(jsonParam.toString())
                osw.flush()
                osw.close()

                println("Sending '" + requestMethod.toString() + "' request to URL $url")
                println("Response Code : $responseCode")

                val istr = inputStream
                val br = BufferedReader(InputStreamReader(istr))
                val response = StringBuffer()
                var inputLine = br.readLine()
                while (inputLine != null) {
                    response.append(inputLine)
                    inputLine = br.readLine()
                }
                resMessage = response.toString()
                resJSONObject = jsonParser.parse(resMessage) as JSONObject
//            br.close()
                println("resJSONObject: ".plus(resJSONObject))

//                BufferedReader(InputStreamReader(inputStream)).use {
//                    val response = StringBuffer()
//                    var inputLine = it.readLine()
//                    while (inputLine != null) {
//                        response.append(inputLine)
//                        inputLine = it.readLine()
//                    }
//                    resMessage = response.toString()
//                }
//                resJSONObject = jsonParser.parse(resMessage) as JSONObject
//                println("resJSONObject: " .plus(resJSONObject))
            }
            value.receiverBankBranchCode = resJSONObject["ISS_BR"] as String
            value.status = resJSONObject["RESP_CD"] as String

            val currentTime = LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul"))
            DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
            value.receiverBankRequestDate = DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
            value.receiverBankRequestTime = DateTimeFormatter.ofPattern("HHmmss").format(currentTime)

            println("----------------Exiting Legacy Connection Test----------------")
            return value
        } catch(ex: Exception) {
            println("connectLegacyBankSWForTransfer: ".plus(ex))
            println("connectLegacyBankSWForTransfer: ".plus(ex.message.toString()))
            logger.error("connectLegacyBankSWForTransfer: ".plus(ex.message.toString()))
        } finally {
            println("connectLegacyBankSWForTransfer: Finally")
            return value
        }
    }
}

/**
 * Flow for API to call to post transfer transactions
 */
@StartableByRPC
@InitiatingFlow
class PostTransfersFlowDemo(val value: TransferTransactionModel) : FlowLogic<TransferTransactionModel>() {
    @Suspendable
    override fun call(): TransferTransactionModel {
        logger.info("PostTransfersFlowDemo: Running logic to determine to settle pay immediately or putting in queue")
        val receiverBankName = bankCodeToNameMap[value.receiverBankCode]
        val maybeOtherParty = serviceHub.identityService.partiesFromName(receiverBankName!!, exactMatch = true)
        if (maybeOtherParty.size != 1) throw IllegalArgumentException("Unknown Party")
        if (maybeOtherParty.first() == ourIdentity) throw IllegalArgumentException("Failed requirement: The payer and payee cannot be the same identity")

        val otherParty = maybeOtherParty.single()
        val transferAmount = KRW(value.amount!!)
        value.receiverBankParty = otherParty
        value.transferAmount = transferAmount

        try {
            val transferTransaction = subFlow(PayDemo(value))

            when {
                transferTransaction.status.equals(TRANSFER_PROCESSING_RESPONSE_CODE) -> {
                    return transferTransaction
                }
                transferTransaction.status.equals(TRANSFER_SUCCESS_RESPONSE_CODE) -> {
                    return transferTransaction
                }
                else -> throw IllegalStateException("Transfer fail exception ")
            }
        } catch (ex: Exception) {
            logger.error("Exception CashFlows.PostTransfersFlowDemo: ${ex.message.toString()}")
            ex.printStackTrace()
            throw ex
        }
    }
}

/**
 * Flow for API to call to post check existence of receiving account transactions
 */
@StartableByRPC
@InitiatingFlow
class PostCheckReceivingFlowDemo(val value: TransferTransactionModel) : FlowLogic<TransferTransactionModel>() {
    @Suspendable
    override fun call(): TransferTransactionModel {
        logger.info("PostCheckReceivingFlowDemo: Running logic to determine to settle pay immediately or putting in queue")
        val receiverBankName = bankCodeToNameMap[value.receiverBankCode]
        val maybeOtherParty = serviceHub.identityService.partiesFromName(receiverBankName!!, exactMatch = true)
        if (maybeOtherParty.size != 1) throw IllegalArgumentException("Unknown Party")
        if (maybeOtherParty.first() == ourIdentity) throw IllegalArgumentException("Failed requirement: The payer and payee cannot be the same identity")

        val otherParty = maybeOtherParty.single()
        val transferAmount = KRW(value.amount!!)
        value.receiverBankParty = otherParty
        value.transferAmount = transferAmount

        try {
            val session = initiateFlow(otherParty)
            //TODO: 응답 flow로 데이터 보내기
            session.send(value)
            //TODO: 응답 flow로부터 데이터 받아오기
            val responseData = session.receive<TransferTransactionModel>()
            var resTransferTxModel = TransferTransactionModel()
            responseData.unwrap { data ->
                resTransferTxModel = data
            }

            when {
                resTransferTxModel.status.equals(TRANSFER_PROCESSING_RESPONSE_CODE) -> {
                    return resTransferTxModel
                }
                resTransferTxModel.status.equals(TRANSFER_SUCCESS_RESPONSE_CODE) -> {
                    return resTransferTxModel
                }
                else -> throw IllegalStateException("Receiving fail exception ")
            }
        } catch (ex: Exception) {
            logger.error("Exception CashFlows.PostCheckReceivingFlowDemo: ${ex}")
            logger.error("Exception CashFlows.PostCheckReceivingFlowDemo: ${ex.message.toString()}")
            ex.printStackTrace()
            throw ex
        }
    }
}

/**
 * The other side of the above flow. For the purposes of this PoC, we won't add any additional checking.
 */
@InitiatingFlow
@InitiatedBy(PostCheckReceivingFlowDemo::class)
private class CheckReceivingDemo(private val session: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        try {
            logger.info("CheckReceivingDemo.PostCheckReceivingFlowDemo: Syncing identities")
            val untrustworthyData = session.receive<TransferTransactionModel>()
            var transferTxModel = TransferTransactionModel()
            untrustworthyData.unwrap({
                transferTxModel = it
            })

            val resTransferTxModel = this.connectLegacyBankSWForReceiving(transferTxModel)
            println("resTransferTxModel: ".plus(resTransferTxModel))
            when {
                !resTransferTxModel.status.equals(TRANSFER_PROCESSING_RESPONSE_CODE) && !resTransferTxModel.status.equals(TRANSFER_SUCCESS_RESPONSE_CODE) -> {
                    throw IllegalStateException("Legacy SW fail exception ")
                }
            }

            session.send(resTransferTxModel)
        } catch(ex: Exception) {
            ex.printStackTrace()
            logger.error("Exception CashFlows.CheckReceivingDemo: ${ex}")
            logger.error(ex.message.toString())
        }
    }

    private fun connectLegacyBankSWForReceiving(value:TransferTransactionModel) : TransferTransactionModel {
        try {
            val url = BANK_SIMULATOR_URL.plus(RECEIVING_URL)
            val urlObj = URL(url)
            var resMessage = ""
            val jsonParser = JSONParser()
            var resJSONObject: JSONObject

//            val connection = urlObj.openConnection() as HttpURLConnection
//            connection.requestMethod = "POST"
//            connection.doOutput = true
//            connection.connectTimeout = 3000 //ms
//            connection.setRequestProperty("charset", "utf-8")
//            connection.setRequestProperty("Content-Type", "application/json")

            with(urlObj.openConnection() as HttpURLConnection) {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000 //ms
                setRequestProperty("charset", "utf-8")
                setRequestProperty("Content-Type", "application/json")

                println("----------------Starting Legacy Connection Test----------------")
                val jsonParam = JSONObject()
                jsonParam["RETVAL_REF_NUM"] = value.transId
                jsonParam["MSG_TYPE"] = value.msgCode
                jsonParam["PROC_CD"] = value.workCode
                jsonParam["ACQ_BK"] = value.senderBankCode
                jsonParam["ACQ_BR"] = value.senderBankBranchCode
                jsonParam["ISS_BK"] = value.receiverBankCode
                jsonParam["ACQ_REQ_DATE"] = value.senderBankRequestDate
                jsonParam["ACQ_REQ_TIME"] = value.senderBankRequestTime
                jsonParam["ACCT_NUM"] = value.receiverAccountNum
                jsonParam["TRAN_AMT"] = value.amount
                jsonParam["SND_ACCT_NUM"] = value.senderAccountNum
                jsonParam["SND_NAME"] = value.senderName
                jsonParam["RCV_NAME"] = value.receiverName

                val os = outputStream
                val osw = OutputStreamWriter(os)
                osw.write(jsonParam.toString())
                osw.flush()
//                osw.close()

                println("Sending '" + requestMethod.toString() + "' request to URL $url")
                println("Response Code : $responseCode")

                val istr = inputStream
                val br = BufferedReader(InputStreamReader(istr))
                val response = StringBuffer()
                var inputLine = br.readLine()
                while (inputLine != null) {
                    response.append(inputLine)
                    inputLine = br.readLine()
                }
                resMessage = response.toString()
                resJSONObject = jsonParser.parse(resMessage) as JSONObject
//                br.close()
                println("resJSONObject: ".plus(resJSONObject))

//            BufferedReader(InputStreamReader(inputStream)).use {
//                val response = StringBuffer()
//                var inputLine = it.readLine()
//                while (inputLine != null) {
//                    response.append(inputLine)
//                    inputLine = it.readLine()
//                }
//                resMessage = response.toString()
//            }
//            resJSONObject = jsonParser.parse(resMessage) as JSONObject
//            println("resJSONObject: " .plus(resJSONObject))

                value.receiverBankBranchCode = resJSONObject["ISS_BR"] as String
                value.status = resJSONObject["RESP_CD"] as String

                val currentTime = LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul"))
                DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
                value.senderBankRequestDate = DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
                value.senderBankRequestTime = DateTimeFormatter.ofPattern("HHmmss").format(currentTime)

                println("----------------Exiting Legacy Connection Test----------------")
            }
            return value
        } catch (ex: Exception) {
            println("connectLegacyBankSWForReceiving: ".plus(ex.message.toString()))
            throw ex
        } finally {
            println("connectLegacyBankSWForReceiving: Finally")
            return value
        }
    }
}

/**
 * Flow for API to call to post cancel transfer transactions
 */
@StartableByRPC
@InitiatingFlow
class PostCancelTransfersFlowDemo(val value: TransferTransactionModel) : FlowLogic<TransferTransactionModel>() {
    companion object {
        object QUERYING : ProgressTracker.Step("Querying the vault for obligation.")
        object BUILDING : ProgressTracker.Step("Building and verifying transaction.")
        object SIGNING : ProgressTracker.Step("signing transaction.")
        object COLLECTING : ProgressTracker.Step("Collecting counterparty signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : ProgressTracker.Step("Finalising transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(QUERYING, BUILDING, SIGNING, COLLECTING, FINALISING)
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): TransferTransactionModel {
        logger.info("SettleObligation.Initiator: Building obligation settlement transaction")
        // Step 1. Retrieve the obligation state from the vault.
        progressTracker.currentStep = QUERYING

        val transferTransactionState = builder {
            val transIdIndex = TransferTransaction.State.TransferTransactionSchemaV1.TransferTransactionEntity::transId.equal(value.transId)
            val criteria = QueryCriteria.VaultCustomQueryCriteria(transIdIndex)
            serviceHub.vaultService.queryBy<TransferTransaction.State>(criteria).states.single()
        }

        val sender = transferTransactionState.state.data.sender
        val receiver = transferTransactionState.state.data.receiver

        // Step 2. Check the party running this flow is the sender.
        val senderIdentity = serviceHub.identityService.requireWellKnownPartyFromAnonymous(sender!!)
        if (ourIdentity != senderIdentity)
            throw IllegalArgumentException("Cancel Transfer flow must be initiated by the sender.")
        //거래 취소 시, 원 거래의 송신자가 취소 거래의 수신자가 됨
        value.receiverBankParty = senderIdentity

        // Step 3. Create a transaction builder.
        progressTracker.currentStep = BUILDING
        val notary = transferTransactionState.state.notary
        val utx = TransactionBuilder(notary = notary)

        // Step 5. Exchange certs and keys.
        val receiverIdentity = serviceHub.identityService.requireWellKnownPartyFromAnonymous(receiver!!)

        // Step 7. Add the tx cancel input state and settle command to the transaction builder.
        // Don't add an output tx cancel as we are fully settling them.
        val transferTransactionOutputState = TransferTransaction.State(transId = value.transId!!, msgCode = value.msgCode!!,
                workCode = value.workCode!!, senderBankCode = value.senderBankCode!!, receiverBankCode = value.receiverBankCode!!, senderBankRequestDate = value.senderBankRequestDate!!,
                amount = KRW(value.amount!!), status = TRANSFER_CANCEL_SUCCESS_RESPONSE_CODE, sender = serviceHub.myInfo.legalIdentities.first(),
                receiver = receiverIdentity)

        utx.addCommand(TransferTransaction.Cancel(), transferTransactionState.state.data.participants.map { it.owningKey })
        utx.addInputState(transferTransactionState)
        utx.addOutputState(transferTransactionOutputState, TRANSFER_TRANSACTION_CONTRACT_ID)

        // Step 8. Verify and sign the transaction.
        progressTracker.currentStep = SIGNING
        utx.verify(serviceHub)
        val ptx = serviceHub.signInitialTransaction(utx, sender.owningKey)

        // Step 9. Sync identities.
        val session = initiateFlow(receiverIdentity)
        //TODO: 응답 flow로 데이터 보내기
        session.send(value)
        //TODO: 응답 flow로부터 데이터 받아오기
        val responseData = session.receive<TransferTransactionModel>()
        var resTransferTxModel = TransferTransactionModel()
        responseData.unwrap { data ->
            resTransferTxModel = data
        }
        subFlow(IdentitySyncFlow.Send(session, ptx.tx))

        // Step 10. Get counterparty signature.
        progressTracker.currentStep = COLLECTING
        val stx = subFlow(CollectSignaturesFlow(ptx,
                setOf(session),
                transferTransactionState.state.data.participants.map { it.owningKey },
                COLLECTING.childProgressTracker())
        )

        // Step 11. Finalise the transaction.
        progressTracker.currentStep = FINALISING
        subFlow(FinalityFlow(stx, FINALISING.childProgressTracker()))

        return resTransferTxModel
    }
}

/**
 * The other side of the above flow. For the purposes of this PoC, we won't add any additional checking.
 */
@InitiatingFlow
@InitiatedBy(PostCancelTransfersFlowDemo::class)
class CancelPaymentDemo(private val session: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        logger.info("CancelPaymentDemo: Signing received transaction")
        val untrustworthyData = session.receive<TransferTransactionModel>()
        var transferTxModel = TransferTransactionModel()
        untrustworthyData.unwrap({
            transferTxModel = it
        })

        var resTransferTxModel = this.connectLegacyBankSWForCancelTransfer(transferTxModel)
        println("cancelResTransferTxModel: ".plus(resTransferTxModel))
        when {
            !resTransferTxModel.status.equals(TRANSFER_CANCEL_PROCESSING_RESPONSE_CODE) && !resTransferTxModel.status.equals(TRANSFER_CANCEL_SUCCESS_RESPONSE_CODE) -> {
                throw IllegalStateException("Legacy SW fail exception ")
            }
        }
        subFlow(CancelPayDemo(transferTxModel))
        session.send(resTransferTxModel)
        subFlow(IdentitySyncFlow.Receive(session))

        val flow = object : SignTransactionFlow(session) {
            @Suspendable
            override fun checkTransaction(stx: SignedTransaction) = Unit
        }
        val stx = subFlow(flow)
        return waitForLedgerCommit(stx.id)
    }

    //Legacy를 이용하여 취소에 대한 거래 이체 완료
    private fun connectLegacyBankSWForCancelTransfer(value:TransferTransactionModel) : TransferTransactionModel {
        try {
            val url = BANK_SIMULATOR_URL.plus(CANCEL_TRANSFER_URL)
            val urlObj = URL(url)
            var resMessage = ""
            val jsonParser = JSONParser()
            var resJSONObject:JSONObject

            with(urlObj.openConnection() as HttpURLConnection) {
                println("----------------Starting Legacy Connection Test----------------")
                var jsonParam = JSONObject()
                jsonParam["RETVAL_REF_NUM"] = value.transId
                jsonParam["MSG_TYPE"] = value.msgCode
                jsonParam["PROC_CD"] = value.workCode
                jsonParam["ACQ_BK"] = value.senderBankCode
                jsonParam["ISS_BK"] = value.receiverBankCode
                jsonParam["ACQ_REQ_DATE"] = value.senderBankRequestDate
                jsonParam["TRAN_AMT"] = value.amount
                println("Json Parameter ********** ")
                println(jsonParam.toString())

                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000 //ms
                setRequestProperty("charset", "utf-8")
                setRequestProperty("Content-Length", jsonParam.size.toString())
                setRequestProperty("Content-Type", "application/json")

                val osw = OutputStreamWriter(outputStream)
                osw.write(jsonParam.toString())
                osw.flush()
//                osw.close()

                println("Sending '" + requestMethod.toString() + "' request to URL $url")
                println("Response Code : $responseCode")

                val br = BufferedReader(InputStreamReader(inputStream))
                val response = StringBuffer()
                var inputLine = br.readLine()
                while (inputLine != null) {
                    response.append(inputLine)
                    inputLine = br.readLine()
                }
                resMessage = response.toString()
                resJSONObject = jsonParser.parse(resMessage) as JSONObject
                br.close()
                println("resJSONObject: ".plus(resJSONObject))

    //            BufferedReader(InputStreamReader(inputStream)).use {
    //                val response = StringBuffer()
    //                var inputLine = it.readLine()
    //                while (inputLine != null) {
    //                    response.append(inputLine)
    //                    inputLine = it.readLine()
    //                }
    //                resMessage = response.toString()
    //            }
    //            resJSONObject = jsonParser.parse(resMessage) as JSONObject
    //            println("resJSONObject: " .plus(resJSONObject))

                value.status = resJSONObject["RESP_CD"] as String
                val currentTime = LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul"))
                DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)
                value.receiverBankRequestDate = DateTimeFormatter.ofPattern("yyyyMMdd").format(currentTime)

                println("----------------Exiting Legacy Connection Test----------------")
            }
            return value
        } catch (ex: Exception) {
            logger.error("connectLegacyBankSWForCancelTransfer: ${ex}")
            println("connectLegacyBankSWForCancelTransfer: ".plus(ex.message.toString()))
            logger.error("connectLegacyBankSWForCancelTransfer: ".plus(ex.message.toString()))
        } finally {
            println("connectLegacyBankSWForCancelTransfer: Finally")
            return value
        }
    }
}

/**
 * Flow to send digital currencies to the other party
 */
@StartableByRPC
@InitiatingFlow
class CancelPayDemo(val value:TransferTransactionModel,
                    val anonymous: Boolean = true) : FlowLogic<SignedTransaction>() {

    companion object {
        object BUILDING : ProgressTracker.Step("Building payment to be sent to other party")
        object SIGNING : ProgressTracker.Step("Signing the payment")
        object COLLECTING : ProgressTracker.Step("Collecting signature from the counterparty") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : ProgressTracker.Step("Finalising the transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(BUILDING, SIGNING, COLLECTING, FINALISING)
    }

    override val progressTracker: ProgressTracker = tracker()
    @Suspendable
    override fun call(): SignedTransaction {
        logger.info("CancelPayDemo: Building pay transaction")
        val notary = serviceHub.networkMapCache.notaryIdentities.firstOrNull()
                ?: throw IllegalStateException("No available notary.")
        val otherParty = value.receiverBankParty!!
        val amount = KRW(value.amount!!)

        // Exchange certs and keys.
        val txIdentities = if (anonymous) {
            subFlow(SwapIdentitiesFlow(otherParty))
        } else {
            emptyMap<Party, AnonymousParty>()
        }
        val maybeAnonymousOtherParty = txIdentities[otherParty] ?: otherParty
        progressTracker.currentStep = BUILDING
        val builder = TransactionBuilder(notary = notary)
        // Check we have enough cash to transfer the requested amount in a try block
        try {
            serviceHub.getCashBalances()[amount.token]?.let { if (it < amount) throw InsufficientBalanceException(amount - it) }
            val (spendTx, keysForSigning) = Cash.generateSpend(serviceHub, builder, amount, maybeAnonymousOtherParty)

            // Verify and sign the transaction
            builder.verify(serviceHub)
            val currentTime = serviceHub.clock.instant()
            builder.setTimeWindow(currentTime, 30.seconds)

            // Sign the transaction.
            progressTracker.currentStep = SIGNING
            val partSignedTx = serviceHub.signInitialTransaction(spendTx, keysForSigning)
            val session = initiateFlow(otherParty)
            subFlow(IdentitySyncFlow.Send(session, partSignedTx.tx))

            // Collect signatures
            progressTracker.currentStep = COLLECTING
            // Finalise the transaction
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(partSignedTx, FINALISING.childProgressTracker()))
            // Handle not enough cash by automatically issuing Obligation to lender
        } catch (ex: Exception) {
            logger.error("Exception CashFlows.CancelPayDemo: ${ex}")
            logger.error("Exception CashFlows.CancelPayDemo: ${ex.message.toString()}")
            throw ex
        }
    }
}

/**
 * The other side of the above flow. For the purposes of this PoC, we won't add any additional checking.
 */
@InitiatingFlow
@InitiatedBy(CancelPayDemo::class)
class CancelAcceptPaymentDemo(private val session: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        logger.info("Pay.CancelAcceptPaymentDemo: Syncing identities")
        subFlow(IdentitySyncFlow.Receive(session))
    }
}

/**
 * Flow for API to call to search transfer transaction
 */
@StartableByRPC
@InitiatingFlow
class SearchTransferTx(val value: String) : FlowLogic<TransferTransactionModel>() {
    @Suspendable
    override fun call(): TransferTransactionModel {
        val transferTxState = builder {
            val transIdIndex = TransferTransaction.State.TransferTransactionSchemaV1.TransferTransactionEntity::transId.equal(value)
            val criteria = QueryCriteria.VaultCustomQueryCriteria(transIdIndex)
            serviceHub.vaultService.queryBy<TransferTransaction.State>(criteria).states.single()
        }

        val transferTx = TransferTransactionModel(
                transId = transferTxState.state.data.transId,
                msgCode = transferTxState.state.data.msgCode,
                workCode = transferTxState.state.data.workCode,
                senderBankCode = transferTxState.state.data.senderBankCode,
                senderBankBranchCode = transferTxState.state.data.senderBankBranchCode,
                receiverBankCode = transferTxState.state.data.receiverBankCode,
                receiverBankBranchCode = transferTxState.state.data.receiverBankBranchCode,
                senderBankRequestDate = transferTxState.state.data.senderBankRequestDate,
                senderBankRequestTime = transferTxState.state.data.senderBankRequestTime,
                receiverBankRequestDate = transferTxState.state.data.receiverBankRequestDate,
                receiverBankRequestTime = transferTxState.state.data.receiverBankRequestTime,
                status = transferTxState.state.data.status,
                receiverAccountNum = transferTxState.state.data.receiverAccountNum,
                amount = transferTxState.state.data.amount.quantity.toDouble(),
                senderAccountNum = transferTxState.state.data.senderAccountNum,
                receiverName = transferTxState.state.data.receiverName,
                senderName = transferTxState.state.data.senderName,

                sender = transferTxState.state.data.sender.toString(),
                receiver = transferTxState.state.data.receiver.toString(),
                transferAmount = transferTxState.state.data.amount)

        return transferTx
    }
}

/**
 * Flow for API to call to search transfer transactions
 */
@StartableByRPC
@InitiatingFlow
class SearchTransferTxList : FlowLogic<List<TransferTransactionModel>>() {
    @Suspendable
    override fun call(): List<TransferTransactionModel> {
        val transferTxList = ArrayList<TransferTransactionModel>()
        val transferTxStates = serviceHub.vaultService.queryBy<TransferTransaction.State>().states

        transferTxStates.forEach {
            val eachTransferTx = TransferTransactionModel(
                    transId = it.state.data.transId,
                    msgCode = it.state.data.msgCode,
                    workCode = it.state.data.workCode,
                    senderBankCode = it.state.data.senderBankCode,
                    senderBankBranchCode = it.state.data.senderBankBranchCode,
                    receiverBankCode = it.state.data.receiverBankCode,
                    receiverBankBranchCode = it.state.data.receiverBankBranchCode,
                    senderBankRequestDate = it.state.data.senderBankRequestDate,
                    senderBankRequestTime = it.state.data.senderBankRequestTime,
                    receiverBankRequestDate = it.state.data.receiverBankRequestDate,
                    receiverBankRequestTime = it.state.data.receiverBankRequestTime,
                    status = it.state.data.status,
                    receiverAccountNum = it.state.data.receiverAccountNum,
                    amount = it.state.data.amount.quantity.toDouble(),
                    senderAccountNum = it.state.data.senderAccountNum,
                    receiverName = it.state.data.receiverName,
                    senderName = it.state.data.senderName,
                    sender = it.state.data.sender.toString(),
                    receiver = it.state.data.receiver.toString(),
                    transferAmount = it.state.data.amount
            )
            transferTxList.add(eachTransferTx)
        }
        return transferTxList
    }
}