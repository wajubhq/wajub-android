package com.wajub.mobile.client

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One HTTP exchange with the API, below the retry policy. */
internal data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    /** JSON text; null for a GET. */
    val body: String?,
    /** Whole-request timeout (spec §2.4). */
    val timeoutMs: Long,
)

internal class HttpResponse(
    val status: Int,
    val body: String,
    private val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** Thrown when no answer came back: [timedOut] for a timeout, else a network failure. */
internal class TransportException(val timedOut: Boolean, cause: Throwable?) : IOException(cause?.message, cause)

internal interface Transport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** OkHttp, connect timeout 10 s and the request's own whole-call timeout. */
internal class OkHttpTransport(private val http: OkHttpClient) : Transport {
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    private fun client(timeoutMs: Long): OkHttpClient =
        clients.getOrPut(timeoutMs) {
            http.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        if (request.method == "GET") {
            builder.get()
        } else {
            builder.method(request.method, (request.body ?: "{}").toRequestBody(JSON))
        }
        val call = client(request.timeoutMs).newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // OkHttp reports a timeout as an InterruptedIOException
                    // (SocketTimeoutException is one).
                    cont.resumeWithException(TransportException(e is InterruptedIOException, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            HttpResponse(
                                status = it.code,
                                body = it.body?.string().orEmpty(),
                                headers = it.headers.associate { (name, value) -> name.lowercase() to value },
                            )
                        }
                    } catch (e: IOException) {
                        cont.resumeWithException(TransportException(e is InterruptedIOException, e))
                        return
                    }
                    cont.resume(result)
                }
            })
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS: Long = 10_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
