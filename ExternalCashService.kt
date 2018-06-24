package com.r3.demos.ubin2a.cash

import com.fasterxml.jackson.databind.ObjectMapper
import com.r3.demos.ubin2a.base.TransactionModel
import com.sun.jersey.api.client.Client
import com.sun.jersey.api.client.ClientResponse
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.loggerFor
import org.eclipse.jetty.http.HttpStatus
import javax.ws.rs.core.MediaType

/**
 * Created by daegon on 2018. 6. 24..
 */

object ExternalCashService {

    @CordaService
    class Service(val services: ServiceHub) : SingletonSerializeAsToken() {

        private companion object {
            val logger = loggerFor<Service>()
        }

        fun approveTransferTx(value: TransactionModel): TransactionModel {
            try {
//                val client = Client.create()
                val url = "http://192.168.35.101:3000/transfer-demo"
                println("Cash URI from properties " + url)
                val client = Client.create()
                val webResource = client.resource(url)
                val mapper = ObjectMapper()
                val response = webResource.accept(MediaType.APPLICATION_JSON)
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .post(ClientResponse::class.java, mapper.writeValueAsString(value))
                if (response.status != HttpStatus.OK_200) {
                    println("RESPONSE CODE".plus(response.status))
                    throw RuntimeException("Failed : HTTP error code : "
                            + response.status)
                }
                println("RESPONSE CODE: ".plus(response.status))
                val result = response.getEntity(TransactionModel::class.java)
                println("RESULT: ".plus(result))
                if (result != null && result.status != null) {
                    value.status = result.status
                }
                println("VALUE: ".plus(value))

//                val jsonParam = JSONObject()
//                jsonParam["sender"] = value.sender
//                jsonParam["receiver"] = value.receiver
//                jsonParam["transactionAmount"] = value.transactionAmount
//
//                println("okhttp client")
//                val client = OkHttpClient()
//                val body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), jsonParam.toString())
//                val request = Request.Builder().url(url).post(body).build()
//                val response = client.newCall(request).execute()
//                println("response execute")
//                val jsonParser = JSONParser()
//                println("jsonParser")
//                val resJSONObject = jsonParser.parse(response.body()?.string()) as JSONObject
//                println("resJSONObject: ".plus(resJSONObject))
//                value.status = resJSONObject["status"] as String

                return value
//                return true
            } catch (ex: Exception) {
                logger.error(ex.message)
                println(ex)
                return TransactionModel()
//                return false
            }
        }
    }
}