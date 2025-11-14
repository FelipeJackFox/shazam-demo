package com.example.soundlens.aws

import android.content.Context
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model.InvokeRequest
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object LambdaInvoker {

    fun identifyFromS3(
        context: Context,
        functionName: String,
        bucket: String,
        key: String
    ): String {

        val creds = BasicAWSCredentials(AwsConfig.ACCESS_KEY, AwsConfig.SECRET_KEY)
        val client = AWSLambdaClient(creds)
        client.setRegion(Region.getRegion(AwsConfig.REGION))

        val payloadJson = JSONObject()
            .put("s3_bucket", bucket)
            .put("s3_key", key)
            .toString()

        val payloadBuffer: ByteBuffer =
            ByteBuffer.wrap(payloadJson.toByteArray(StandardCharsets.UTF_8))

        val req = InvokeRequest()
            .withFunctionName(functionName)
            .withPayload(payloadBuffer)

        val res = client.invoke(req)

        // si la lambda misma falló, viene en functionError
        if (res.functionError != null) {
            // regresamos un json para que la app lo muestre
            return JSONObject()
                .put("ok", false)
                .put("reason", "lambda_function_error")
                .put("functionError", res.functionError)
                .put("statusCode", res.statusCode)
                .toString()
        }

        // si no hubo payload (esto te puede dar el "Error: null")
        val bb = res.payload ?: return JSONObject()
            .put("ok", false)
            .put("reason", "empty_payload_from_lambda")
            .put("statusCode", res.statusCode)
            .toString()

        val txt = String(bb.array(), StandardCharsets.UTF_8).trim()

        // a veces la lambda devuelve "" o "null"
        if (txt.isEmpty() || txt == "null") {
            return JSONObject()
                .put("ok", false)
                .put("reason", "empty_text_payload")
                .put("statusCode", res.statusCode)
                .toString()
        }

        return txt
    }
}
