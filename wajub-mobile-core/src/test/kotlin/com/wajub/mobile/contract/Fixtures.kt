package com.wajub.mobile.contract

import com.wajub.mobile.client.HttpRequest
import com.wajub.mobile.client.HttpResponse
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.client.Transport
import com.wajub.mobile.client.TransportException
import com.wajub.mobile.WajubSession
import com.wajub.mobile.device.DeviceEnvironment
import com.wajub.mobile.device.InstallIdStore
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** The SDK's copy of the spec fixtures (`src/test/resources/fixtures`). */
object Fixtures {
    val dir: File = File("src/test/resources/fixtures")

    fun names(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".json") && f.name != "mobile-money.vectors.json" }!!.map { it.name }.sorted()

    fun file(name: String): JSONObject = JSONObject(File(dir, name.withJson()).readText())

    /** The fixture's `body` (the realtime ones have `data` instead). */
    fun body(name: String): JSONObject = file(name).let { it.optJSONObject("body") ?: it.getJSONObject("data") }

    fun status(name: String): Int = file(name).optInt("http_status", 200)

    fun headers(name: String): Map<String, String> {
        val h = file(name).optJSONObject("response_headers") ?: return emptyMap()
        return h.keys().asSequence().associateWith { h.getString(it) }
    }

    /** The fixture as the fake API answers it. */
    fun response(name: String, mutate: (JSONObject) -> Unit = {}): FakeResponse {
        val body = body(name)
        mutate(body)
        return FakeResponse(status(name), body.toString(), headers(name))
    }

    fun vectors(): JSONObject = JSONObject(File(dir, "mobile-money.vectors.json").readText())

    private fun String.withJson() = if (endsWith(".json")) this else "$this.json"
}

class FakeResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    /** Virtual milliseconds before it answers. */
    val delayMs: Long = 0,
    /** No answer: a timeout (true) or a network failure (false). */
    val failure: Boolean? = null,
) {
    companion object {
        fun timeout(delayMs: Long = 0) = FakeResponse(0, "", failure = true, delayMs = delayMs)

        fun network() = FakeResponse(0, "", failure = false)

        fun status(code: Int, body: String = """{"code":$code}""", headers: Map<String, String> = emptyMap()) =
            FakeResponse(code, body, headers)
    }
}

/**
 * The fake HTTP layer: answers each "METHOD /path" from a queue (the last
 * answer repeats) and records every request.
 */
internal class FakeApi : Transport {
    val requests = mutableListOf<HttpRequest>()

    /** Virtual time of each request; set [clock] to the test scheduler's. */
    val times = mutableListOf<Long>()
    var clock: () -> Long = { 0L }
    private val routes = mutableMapOf<String, ArrayDeque<FakeResponse>>()

    /** Queues answers for a route, e.g. `on("GET /pay/session", Fixtures.response("session-cm-aggregate"))`. */
    fun on(route: String, vararg responses: FakeResponse): FakeApi {
        routes.getOrPut(route) { ArrayDeque() }.addAll(responses)
        return this
    }

    /** Replaces the answers of a route. */
    fun set(route: String, vararg responses: FakeResponse): FakeApi {
        routes[route] = ArrayDeque(responses.toList())
        return this
    }

    fun requests(route: String): List<HttpRequest> = requests.filter { route(it) == route }

    fun count(route: String): Int = requests(route).size

    fun lastBody(route: String): JSONObject = JSONObject(requests(route).last().body!!)

    private fun route(r: HttpRequest) = "${r.method} ${r.url.substringAfter("://").substringAfter('/').let { "/$it" }}"

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        times += clock()
        val key = route(request)
        val queue = routes[key] ?: error("No fake answer for $key")
        val response = if (queue.size > 1) queue.removeFirst() else queue.first()
        if (response.delayMs > 0) delay(response.delayMs)
        response.failure?.let { throw TransportException(it, null) }
        return HttpResponse(response.status, response.body, response.headers.mapKeys { it.key.lowercase() })
    }
}

class MemoryInstallIds(var id: String? = null) : InstallIdStore {
    override fun read(): String? = id

    override fun write(id: String) {
        this.id = id
    }
}

fun testDevice(installIds: InstallIdStore = MemoryInstallIds("5f0c2b1e-8d4a-4c3b-9e7f-1a2b3c4d5e6f")) = DeviceEnvironment(
    platform = "android",
    osVersion = "14",
    languageTags = { listOf("fr-CM") },
    timeZone = { "Africa/Douala" },
    screen = { "1080x2400" },
    installIds = installIds,
)

/** A session over the fake API; retries wait in virtual time. */
internal fun fakeSession(api: FakeApi, device: DeviceEnvironment = testDevice(), clock: () -> Long = System::currentTimeMillis): WajubSession =
    WajubSession("test_tok", PayClient(api, environment = { device }, pause = { delay(it) }, random = Random(7), clock = clock))
