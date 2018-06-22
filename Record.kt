package com.r3.demos.ubin2a.record

import com.r3.demos.ubin2a.base.KRW
import com.r3.demos.ubin2a.base.TRANSFER_SUCCESS_RESPONSE_CODE
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.transactions.LedgerTransaction
import java.time.*
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Lob
import javax.persistence.Table

class Record : Contract {
    companion object {
        @JvmStatic
        val RECORD_CONTRACT_ID = "com.r3.demos.ubin2a.record.Record"
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<TypeOnlyCommandData>()
        when (command.value) {
            is Record.Issue -> requireThat {
            }
            is Record.Cancel -> requireThat {
            }
        }
    }

    // Commands.
    interface Commands : CommandData

    class Issue : Commands, TypeOnlyCommandData()
    class Cancel : Commands, TypeOnlyCommandData()
    class Exit : Commands, TypeOnlyCommandData()

    data class State(val transId: String? = null,
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
                     val receiverName: String? = null,
                     val amount: Amount<Currency> = KRW(0.00),
                     val currency: Currency = KRW,
                     val senderAccountNum: String? = null,
                     val senderName: String? = null,
                     val sender: AbstractParty? = null,
                     val writer: AbstractParty? = null,
                     val issueDate: ZonedDateTime = LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")),
                     override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState, QueryableState {
        override val participants: List<AbstractParty> get() = listOf(sender!!, writer!!)

        // Object relational mapper.
        override fun supportedSchemas() = listOf(RecordSchemaV1)
        override fun generateMappedObject(schema: MappedSchema) = RecordSchemaV1.RecordEntity(this)

        object RecordSchemaV1 : MappedSchema(State::class.java, 1, listOf(RecordEntity::class.java)) {
            @Entity
            @Table(name = "record")
            class RecordEntity(record: State) : PersistentState() {
                constructor() : this(Record.State())
                constructor(transId: String?) : this(Record.State(transId = transId))
                @Column
                var transId: String? = record.transId
                @Column
                var workCode: String? = record.workCode
                @Column
                var senderBankCode: String? = record.senderBankCode
                @Column
                var senderBankBranchCode: String? = record.senderBankBranchCode
                @Column
                var receiverBankCode: String? = record.receiverBankCode
                @Column
                var receiverBankBranchCode: String? = record.receiverBankBranchCode
                @Column
                var senderBankRequestDate: String? = record.senderBankRequestDate
                @Column
                var senderBankRequestTime: String? = record.senderBankRequestTime
                @Column
                var receiverBankRequestDate: String? = record.receiverBankRequestDate
                @Column
                var receiverBankRequestTime: String? = record.receiverBankRequestTime
                @Column
                var status: String = record.status
                @Column
                var receiverAccountNum: String? = record.receiverAccountNum
                @Column
                var amount: Long = record.amount.quantity
                @Column
                var currency: String = record.amount.token.toString()
                @Column
                var senderAccountNum: String? = record.senderAccountNum
                @Column
                var senderName: String? = record.senderName
                @Column
                @Lob
                var sender: ByteArray? = record.sender?.owningKey?.encoded
                @Column
                @Lob
                var writer: ByteArray? = record.writer?.owningKey?.encoded
                @Column
                var issueDate: ZonedDateTime = record.issueDate
                @Column
                var linearId: String = record.linearId.id.toString()
            }
        }
    }
}