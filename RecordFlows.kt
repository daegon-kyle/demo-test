package com.r3.demos.ubin2a.record

import co.paralleluniverse.fibers.Suspendable
import com.r3.demos.ubin2a.base.*
import com.r3.demos.ubin2a.record.Record.Companion.RECORD_CONTRACT_ID
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import kotlin.collections.ArrayList

@StartableByRPC
@InitiatingFlow
class RecordFlows(val value: TransferTransactionModel) : FlowLogic<SignedTransaction>() {
    companion object {
        object COLLECTING : ProgressTracker.Step("Collecting counterparty signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }
        object FINALISING : ProgressTracker.Step("Finalising the transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                FINALISING)
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        println("Start Recording -------------------")
        // Step 1. Setup identities.
        val maybeOtherParty = serviceHub.identityService.partiesFromName(CENTRAL_PARTY_X500.organisation, exactMatch = true)
        if (maybeOtherParty.size != 1) throw IllegalArgumentException("Unknown Party")
        if (maybeOtherParty.first() == ourIdentity) throw IllegalArgumentException("Failed requirement: The sender and writer cannot be the same identity")

        val otherParty = maybeOtherParty.single()
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // Step 2. Build transaction.
        val utx = TransactionBuilder(notary)
        val amount = KRW(value.amount!!)
        val recordState = Record.State(transId = value.transId, workCode = value.workCode, senderBankCode = value.senderBankCode,
                senderBankBranchCode = value.senderBankBranchCode, receiverBankCode = value.receiverBankCode, receiverBankBranchCode = value.receiverBankBranchCode,
                senderBankRequestDate = value.senderBankRequestDate, senderBankRequestTime = value.senderBankRequestTime, receiverBankRequestDate = value.receiverBankRequestDate,
                receiverBankRequestTime = value.receiverBankRequestTime, status = value.status!!, receiverAccountNum = value.receiverAccountNum, receiverName = value.receiverName,
                amount = amount, senderAccountNum = value.senderAccountNum, senderName = value.senderName, sender = serviceHub.myInfo.legalIdentities.first(), writer = otherParty)
        utx.addOutputState(recordState, RECORD_CONTRACT_ID)

        if (value.status.equals(TRANSFER_PROCESSING_RESPONSE_CODE) || value.status.equals(TRANSFER_SUCCESS_RESPONSE_CODE)) {
            utx.addCommand(Record.Issue(), recordState.participants.map { it.owningKey })
        } else {
            val recordInputState = builder {
                val transIdIndex = Record.State.RecordSchemaV1.RecordEntity::transId.equal(value.transId)
                val criteria = QueryCriteria.VaultCustomQueryCriteria(transIdIndex)
                serviceHub.vaultService.queryBy<Record.State>(criteria).states.single()
            }
            utx.addInputState(recordInputState)
            utx.addCommand(Record.Cancel(), recordState.participants.map { it.owningKey })
        }
        utx.verify(serviceHub)

        // Step 3. Sign the transaction.
        val ptx = serviceHub.signInitialTransaction(utx, ourIdentity.owningKey)
        val session = initiateFlow(otherParty)
        subFlow(IdentitySyncFlow.Send(session, ptx.tx))

        // Step4. Get the counter-party signature.
        val stx = subFlow(CollectSignaturesFlow(ptx, setOf(session), recordState.participants.map { it.owningKey }, COLLECTING.childProgressTracker()))

        // Step5. Finalise the transaction.
        return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker()))
    }
}
@InitiatingFlow
@InitiatedBy(RecordFlows::class)
class ReceiveRecordFlows(val otherFlow: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call():SignedTransaction {
        logger.info("RecordFlows.ReceiveRecordFlows: Syncing identities")
        subFlow(IdentitySyncFlow.Receive(otherFlow))
        logger.info("RecordFlows.ReceiveRecordFlows: Signing received transaction")
        val flow = object : SignTransactionFlow(otherFlow) {
            @Suspendable
            override fun checkTransaction(stx: SignedTransaction) = Unit // TODO: Add some checking here.
        }
        val stx = subFlow(flow)
        return waitForLedgerCommit(stx.id)
    }
}

@StartableByRPC
@InitiatingFlow
class SearchRecord(val value: String) : FlowLogic<List<RecordModel>>() {
    @Suspendable
    override fun call(): List<RecordModel> {
        println("Start Searching Record -------------------")
        val recordList = ArrayList<RecordModel>()

        val recordStates = builder {
            val transIdIndex = Record.State.RecordSchemaV1.RecordEntity::transId.equal(value)
            val criteria = QueryCriteria.VaultCustomQueryCriteria(transIdIndex)
            serviceHub.vaultService.queryBy<Record.State>(criteria).states
        }

        recordStates.forEach {
            val eachRecord = RecordModel(
                    transId = it.state.data.transId,
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
                    receiverName = it.state.data.receiverName,
                    amount = it.state.data.amount,
                    senderAccountNum = it.state.data.senderAccountNum,
                    senderName = it.state.data.senderName,
                    sender = it.state.data.sender.toString(),
                    writer = it.state.data.writer.toString(),
                    issueDate = it.state.data.issueDate.toString()
            )
            recordList.add(eachRecord)
        }
        println("Finish Searching Record -------------------")

        return recordList
    }
}
