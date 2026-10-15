package com.wajub.mobile.ui

import com.wajub.mobile.WajubMobile
import com.wajub.mobile.WajubSession
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import android.content.Context

/** The core module's copy of the spec fixtures. */
object Fixtures {
    private val dir = File("../wajub-mobile-core/src/test/resources/fixtures")

    fun file(name: String): JSONObject = JSONObject(File(dir, "$name.json").readText())
}

/**
 * The API, answered in place of the network from fixtures, on the calling
 * thread: the sheet's requests complete on the main looper, in its order.
 */
class FakeApi {
    private val routes = mutableMapOf<String, ArrayDeque<() -> Pair<Int, String>>>()
    val requests: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val bodies: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()

    fun on(route: String, fixture: String): FakeApi = answer(route) {
        val file = Fixtures.file(fixture)
        file.getInt("http_status") to file.getJSONObject("body").toString()
    }

    fun fail(route: String): FakeApi = answer(route) { throw IOException("offline") }

    /** A fixture's body, changed by [mutate]. */
    fun on(route: String, fixture: String, mutate: (JSONObject) -> Unit): FakeApi = answer(route) {
        val file = Fixtures.file(fixture)
        val body = file.getJSONObject("body").also(mutate)
        file.getInt("http_status") to body.toString()
    }

    fun lastBody(route: String): JSONObject = JSONObject(bodies.filter { it.first == route }.last().second)

    private fun answer(route: String, block: () -> Pair<Int, String>): FakeApi {
        routes.getOrPut(route) { ArrayDeque() }.add(block)
        return this
    }

    fun count(route: String): Int = requests.count { it == route }

    fun session(token: String = "test_tok"): WajubSession =
        WajubMobile.createSession(
            token,
            OkHttpClient.Builder()
                .dispatcher(Dispatcher(DirectExecutor))
                .addInterceptor { chain ->
                    val route = "${chain.request().method} ${chain.request().url.encodedPath}"
                    requests += route
                    val sent = chain.request().body?.let { body -> okio.Buffer().also { body.writeTo(it) }.readUtf8() }
                    if (sent != null) bodies += route to sent
                    val queue = routes[route] ?: error("No fake answer for $route")
                    val (status, body) = (if (queue.size > 1) queue.removeFirst() else queue.first())()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("fake")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
        )

    private object DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}

/** Logos never load in tests: every image request fails. */
fun failingImages(context: Context): ImageLoader =
    ImageLoader.Builder(context).components {
        add(object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult = ErrorResult(null, chain.request, IOException("no network"))
        })
    }.build()
