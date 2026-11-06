package com.example.tangeml2.data.rpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Transport- or node-level JSON-RPC failure, with the node's own error text. */
class RpcException(
    message: String,
    val rpcCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Minimal, dependency-free JSON-RPC 2.0 client.
 *
 * Handles retries with backoff for transient failures and always surfaces the
 * node's error body (a bare HTTP 400 carrying a JSON-RPC error object is common).
 */
class JsonRpcClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 25_000,
    private val maxAttempts: Int = 3,
) {

    suspend fun raw(rpcUrl: String, method: String, params: List<Any?>): JSONObject =
        withContext(Dispatchers.IO) {
            var last: Throwable? = null
            for (attempt in 1..maxAttempts) {
                coroutineContext.ensureActive()
                try {
                    return@withContext postOnce(rpcUrl, method, params)
                } catch (e: RpcException) {
                    // The node answered with a protocol error: retrying will not help.
                    throw e
                } catch (e: Exception) {
                    last = e
                    if (attempt < maxAttempts) delay(250L * attempt)
                }
            }
            throw RpcException(
                "RPC 请求失败（$method）：${last?.message ?: "未知错误"}",
                cause = last,
            )
        }

    suspend fun result(rpcUrl: String, method: String, params: List<Any?>): Any? {
        val envelope = raw(rpcUrl, method, params)
        if (!envelope.has("result") || envelope.isNull("result")) return null
        val value = envelope.opt("result") ?: return null
        return if (value === JSONObject.NULL) null else value
    }

    suspend fun resultString(rpcUrl: String, method: String, params: List<Any?>): String {
        val value = result(rpcUrl, method, params)
            ?: throw RpcException("RPC 返回空结果（$method）")
        return value.toString()
    }

    suspend fun resultStringOrNull(rpcUrl: String, method: String, params: List<Any?>): String? =
        result(rpcUrl, method, params)?.toString()

    private fun postOnce(rpcUrl: String, method: String, params: List<Any?>): JSONObject {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", JSONArray(params.map { it ?: JSONObject.NULL }))
        }.toString()

        val connection = URL(rpcUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            // HTTP >= 400 still carries the useful JSON-RPC error body.
            val stream = if (code >= 400) (connection.errorStream ?: connection.inputStream) else connection.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code == 429 || code >= 500) throw IOException("RPC HTTP $code")
            if (text.isBlank()) throw IOException("RPC HTTP $code（空响应体）")

            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("RPC 响应不是合法 JSON（HTTP $code）：${text.take(180)}")
            }

            val error = json.optJSONObject("error")
            if (error != null) {
                val message = error.optString("message").ifBlank { "节点返回未知错误" }
                val data = error.optString("data")
                throw RpcException(
                    message = if (data.isBlank()) message else "$message（${data.take(160)}）",
                    rpcCode = error.optInt("code").takeIf { it != 0 },
                )
            }
            if (code >= 400) throw IOException("RPC HTTP $code")
            return json
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
