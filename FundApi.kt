package com.r3.demos.ubin2a.api.controller

import com.r3.demos.ubin2a.base.ExceptionModel
import com.r3.demos.ubin2a.base.TransactionModel
import com.r3.demos.ubin2a.base.TransferTransactionModel
import com.r3.demos.ubin2a.cash.*
import com.r3.demos.ubin2a.record.RecordFlows
import net.corda.core.contracts.StateAndRef
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.finance.contracts.asset.Cash
import org.slf4j.Logger
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import kotlin.to


// TODO: Add a basic front-end to issue cash and list all cash issued.
// TODO: Use the Bank of Corda instead of self issuing cash.

@Path("fund")
class FundApi(val services: CordaRPCOps) {
    companion object {
        private val logger: Logger = loggerFor<FundApi>()
    }

    /** Returns all cash states in the vault. */
    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCash(): List<StateAndRef<Cash.State>> {
        val vaultStates = services.vaultQueryBy<Cash.State>()
        return vaultStates.states
    }

    /**
     * Transfers digital currency between commercial banks
     * JSON(application/json):
     * { "priority": 1, "receiver": "BANKSWIFT","transactionAmount": 200.00 }
     */
    @POST
    @Path("transfer")
    @Produces(MediaType.APPLICATION_JSON)
    fun transferCash(value: TransactionModel): Response {
        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(PostTransfersFlow::class.java, value)
            flowHandle.progress.subscribe { logger.info("FundApi.transferCash: $it") }
            val result = flowHandle.use {
                it.returnValue.getOrThrow()
            }
            Response.Status.CREATED to result
        } catch (ex: Exception) {
            logger.error("Exception during transferCash: $ex")
            Response.Status.INTERNAL_SERVER_ERROR to ExceptionModel(statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode, msg = ex.message.toString())
        }
        return Response.status(status).entity(message).build()
    }

    /**
     * Transfers digital currency between commercial banks
     * JSON(application/json):
     * { RETVAL_REF_NUM = "0020000000001", MSG_TYPE = "0200", PROC_CD = "400000", ACQ_BK = "011",
     *   ACQ_BR = "0110590", ISS_BK = "088", ACQ_REQ_DATE = senderBankRequestDate, ACQ_REQ_TIME = senderBankRequestTime, ACCT_NUM = "10103354412",
     *   TRAN_AMT = 300.0, SND_ACCT_NUM = "11990000112", SND_NAME = "농협일", RCV_NAME = "신한일"}
     */
    @POST
    @Path("receiving-demo")
    @Produces(MediaType.APPLICATION_JSON)
    fun receivingDemo(value: TransferTransactionModel): Response {
        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(PostCheckReceivingFlowDemo::class.java, value)
            flowHandle.progress.subscribe { logger.info("FundApi.receivingDemo: $it") }
            val result = flowHandle.use {
                it.returnValue.getOrThrow()
            }

            Response.Status.CREATED to result
        } catch (ex: Exception) {
            logger.error("Exception during receivingDemo: $ex")
            Response.Status.INTERNAL_SERVER_ERROR to ExceptionModel(statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode, msg = ex.message.toString())
        }
        return Response.status(status).entity(message).build()
    }

    /**
     * Transfers digital currency between commercial banks
     * JSON(application/json):
     * { "RETVAL_REF_NUM": "0020000000001", "MSG_TYPE": "0200", "PROC_CD": "400000", "ACQ_BK": "011", "ACQ_BR": "0110590",
     *   "ISS_BK": "088", "ISS_BR": "", "ACQ_REQ_DATE": "20180608", "ACQ_REQ_TIME": "233208", "RESP_CD": "", "ACCT_NUM": "10103354412",
     *   "TRAN_AMT": 200.00, "SND_ACCT_NUM": "11990000112", "SND_NAME": "농협일", "RCV_NAME": "신한일" }
     */
    @POST
    @Path("transfer-demo")
    @Produces(MediaType.APPLICATION_JSON)
    fun transferCashDemo(value: TransferTransactionModel): Response {
        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(PostTransfersFlowDemo::class.java, value)
            flowHandle.progress.subscribe { logger.info("FundApi.transferCashDemo: $it") }
            val result = flowHandle.use {
                it.returnValue.getOrThrow()
            }

            val recordFlowHandle = services.startFlowDynamic(RecordFlows::class.java, result)
            val recordResult = recordFlowHandle.use {
                it.returnValue.getOrThrow()
            }

            Response.Status.CREATED to result
        } catch (ex: Exception) {
            logger.error("Exception during transferCashDemo: $ex")
            ex.printStackTrace()
            Response.Status.INTERNAL_SERVER_ERROR to ExceptionModel(statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode, msg = ex.message.toString())
        }
        return Response.status(status).entity(message).build()
    }

    /**
     * Cancel transfers digital currency between commercial banks
     * JSON(application/json):
     * { "RETVAL_REF_NUM": "0020000000001", "MSG_TYPE": "0200", "PROC_CD": "400000", "ACQ_BK": "011", "ACQ_BR": "",
     *   "ISS_BK": "088", "ISS_BR": "", "REQ_DATE": "20180608", "REQ_TIME": "", "RESP_CD": "", "ACCT_NUM": "",
     *   "TRAN_AMT": 200.00, "SND_ACCT_NUM": "", "SND_NAME": "", "RCV_NAME": "" }
     */
    @POST
    @Path("transfer-demo/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    fun cancelTransferCashDemo(value: TransferTransactionModel): Response {
        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(PostCancelTransfersFlowDemo::class.java, value)
            flowHandle.progress.subscribe { logger.info("FundApi.cancelTransferCashDemo: $it") }
            val result = flowHandle.use {
                it.returnValue.getOrThrow()
            }

            val recordFlowHandle = services.startFlowDynamic(RecordFlows::class.java, result)
            val recordResult = recordFlowHandle.use {
                it.returnValue.getOrThrow()
            }

            Response.Status.CREATED to result
        } catch (ex: Exception) {
            logger.error("Exception during transferCashCancelDemo: $ex")
            Response.Status.INTERNAL_SERVER_ERROR to ExceptionModel(statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode, msg = ex.message.toString())
        }
        return Response.status(status).entity(message).build()
    }
}