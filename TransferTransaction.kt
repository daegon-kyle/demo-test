package com.r3.demos.ubin2a.cash

import com.r3.demos.ubin2a.base.KRW
import com.r3.demos.ubin2a.base.TRANSFER_SUCCESS_RESPONSE_CODE
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.transactions.LedgerTransaction
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Lob
import javax.persistence.Table

class TransferTransaction : Contract {
    companion object {
        @JvmStatic
        val TRANSFER_TRANSACTION_CONTRACT_ID = "com.r3.demos.ubin2a.cash.TransferTransaction"
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<TypeOnlyCommandData>()
        when (command.value) {
            is TransferTransaction.Issue -> requireThat {
            }
            is TransferTransaction.Cancel -> requireThat {
            }
        }
    }

    // Commands.
    interface Commands : CommandData

    class Issue : Commands, TypeOnlyCommandData()
    class Cancel : Commands, TypeOnlyCommandData()
    class Exit : Commands, TypeOnlyCommandData()

    data class State(val transId: String? = null,
                     val msgCode: String? = null,
                     val workCode: String? = null,
                     val senderBankCode: String? = null,
                     val senderBankBranchCode: String? = null,
                     val receiverBankCode: String? = null,
                     val receiverBankBranchCode: String? = null,
                     val senderBankRequestDate: String? = null,
                     val senderBankRequestTime: String? = null,
                     val receiverBankRequestDate: String? = null,
                     val receiverBankRequestTime: String? = null,
                     val status: String = TRANSFER_SUCCESS_RESPONSE_CODE,
                     val receiverAccountNum: String? = null,
                     val amount: Amount<Currency> = KRW(0.00),
                     val currency: Currency = KRW,
                     val senderAccountNum: String? = null,
                     val receiverName: String? = null,
                     val senderName: String? = null,
                     val sender: AbstractParty? = null,
                     val receiver: AbstractParty? = null,
                     val issueDate: ZonedDateTime = LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")),
                     override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState, QueryableState {
        override val participants: List<AbstractParty> get() = listOf(sender!!, receiver!!)

        // Object relational mapper.
        override fun supportedSchemas() = listOf(TransferTransactionSchemaV1)
        override fun generateMappedObject(schema: MappedSchema) = TransferTransactionSchemaV1.TransferTransactionEntity(this)

        object TransferTransactionSchemaV1 : MappedSchema(State::class.java, 1, listOf(TransferTransactionEntity::class.java)) {
            @Entity
            @Table(name = "transferTransaction")
            class TransferTransactionEntity(transferTransaction: State) : PersistentState() {
                constructor() : this(TransferTransaction.State())
                constructor(transId: String?) : this(TransferTransaction.State(transId = transId!!))
                @Column
                var transId: String? = transferTransaction.transId
                @Column
                var msgCode: String? = transferTransaction.msgCode
                @Column
                var workCode: String? = transferTransaction.workCode
                @Column
                var senderBankCode: String? = transferTransaction.senderBankCode
                @Column
                var senderBankBranchCode: String? = transferTransaction.senderBankBranchCode
                @Column
                var receiverBankCode: String? = transferTransaction.receiverBankCode
                @Column
                var receiverBankBranchCode: String? = transferTransaction.receiverBankBranchCode
                @Column
                var senderBankRequestDate: String? = transferTransaction.senderBankRequestDate
                @Column
                var senderBankRequestTime: String? = transferTransaction.senderBankRequestTime
                @Column
                var receiverBankRequestDate: String? = transferTransaction.receiverBankRequestDate
                @Column
                var receiverBankRequestTime: String? = transferTransaction.receiverBankRequestTime
                @Column
                var status: String = transferTransaction.status
                @Column
                var receiverAccountNum: String? = transferTransaction.receiverAccountNum
                @Column
                var amount: Long = transferTransaction.amount.quantity
                @Column
                var currency: String = transferTransaction.amount.token.toString()
                @Column
                var senderAccountNum: String? = transferTransaction.senderAccountNum
                @Column
                var receiverName: String? = transferTransaction.receiverName
                @Column
                var senderName: String? = transferTransaction.senderName
                @Column
                @Lob
                var sender: ByteArray? = transferTransaction.sender?.owningKey?.encoded
                @Column
                @Lob
                var receiver: ByteArray? = transferTransaction.receiver?.owningKey?.encoded
                @Column
                var issueDate: ZonedDateTime = transferTransaction.issueDate
                @Column
                var linearId: String = transferTransaction.linearId.id.toString()
            }
        }
    }
}