package com.wajub.mobile.device

import com.wajub.mobile.SDK_VERSION
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Where the SDK keeps a random identifier of this app install: private to the app, kept across updates. */
interface InstallIdStore {
    fun read(): String?

    fun write(id: String)
}

/** Kept in memory only: a new identifier each time the app starts. */
class MemoryInstallIdStore : InstallIdStore {
    @Volatile
    private var id: String? = null

    override fun read(): String? = id

    override fun write(id: String) {
        this.id = id
    }
}

/** Kept in [file], private to your app. */
class FileInstallIdStore(private val file: File) : InstallIdStore {
    override fun read(): String? =
        runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    override fun write(id: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(id)
        }
    }
}

/**
 * The device the SDK runs on, for the payment and for fraud prevention. The
 * Compose module fills it from the Android context on its own; an app that
 * uses only `wajub-mobile-core` sets [com.wajub.mobile.WajubMobile.device].
 */
class DeviceEnvironment(
    /** `android`. */
    val platform: String = "android",
    /** The OS release, `14`. */
    val osVersion: String = defaultOsVersion(),
    /** The device's languages, BCP 47 with region, preferred first. */
    private val languageTags: () -> List<String> = { listOf(Locale.getDefault().toLanguageTag()) },
    /** The device's IANA time zone. */
    private val timeZone: () -> String = { TimeZone.getDefault().id },
    /** `<width>x<height>` of the main display in physical pixels, portrait; null when unknown. */
    private val screen: () -> String? = { null },
    val colorDepth: Int = 24,
    /** Where the install identifier is kept; an app using only `wajub-mobile-core` passes a [FileInstallIdStore]. */
    private val installIds: InstallIdStore = defaultInstallIds(),
) {
    @Volatile
    private var cachedFingerprint: String? = null

    // `wajub-kotlin/1.3.0 (android 14)`: X-Wajub-Client and _client.client.
    internal val clientHeader: String get() = "wajub-kotlin/$SDK_VERSION ($platform $osVersion)"

    internal fun languages(): List<String> = languageTags().filter { it.isNotBlank() }.ifEmpty { listOf("en") }

    // `fr-CM, fr;q=0.9, en;q=0.8`: the device's languages, each followed by its base language, then English.
    internal fun acceptLanguage(): String {
        val tags = linkedSetOf<String>()
        for (tag in languages()) {
            tags += tag
            tag.substringBefore('-').takeIf { it.isNotEmpty() }?.let { tags += it }
        }
        tags += "en"
        return tags.mapIndexed { index, tag ->
            if (index == 0) tag else "$tag;q=${String.format(Locale.ROOT, "%.1f", maxOf(0.1, 1.0 - index * 0.1))}"
        }.joinToString(", ")
    }

    // Hex SHA-256 of "device:" + install id: stable for one install, never
    // derived from a hardware or advertising id.
    internal fun fingerprint(): String =
        cachedFingerprint ?: synchronized(this) {
            cachedFingerprint ?: run {
                val id = installIds.read() ?: UUID.randomUUID().toString().also { installIds.write(it) }
                val digest = MessageDigest.getInstance("SHA-256").digest("device:$id".toByteArray(Charsets.UTF_8))
                digest.joinToString("") { "%02x".format(it) }.also { cachedFingerprint = it }
            }
        }

    // Reads (or creates) the install id on a background thread, so payments never wait on the file.
    internal fun prewarm() {
        if (cachedFingerprint != null) return
        Thread({ fingerprint() }, "wajub-install-id").apply { isDaemon = true }.start()
    }

    // `data._client` of POST /pay/process.
    internal fun clientSignals(): Map<String, Any?> = linkedMapOf(
        "timezone" to timeZone(),
        "language" to languages().first(),
        "screen" to (screen() ?: "0x0"),
        "color_depth" to colorDepth,
        "fingerprint" to fingerprint(),
        "client" to clientHeader,
    )

    companion object {
        private fun onAndroid(): Boolean = runCatching { Class.forName("android.os.Build") }.isSuccess

        private fun defaultInstallIds(): InstallIdStore =
            if (onAndroid()) {
                MemoryInstallIdStore()
            } else {
                FileInstallIdStore(File(System.getProperty("user.home") ?: ".", ".wajub/install-id"))
            }

        // android.os.Build.VERSION.RELEASE when running on Android.
        private fun defaultOsVersion(): String =
            runCatching {
                Class.forName("android.os.Build\$VERSION").getField("RELEASE").get(null) as? String
            }.getOrNull() ?: System.getProperty("os.version") ?: "unknown"
    }
}
