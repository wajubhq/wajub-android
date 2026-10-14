package com.wajub.mobile.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.util.DisplayMetrics
import android.view.WindowManager
import com.wajub.mobile.WajubMobile
import com.wajub.mobile.device.DeviceEnvironment
import com.wajub.mobile.device.FileInstallIdStore
import java.io.File
import java.util.TimeZone

/** Tells the SDK about the Android device. Done at app start by [WajubInitProvider]. */
object WajubAndroid {
    @Volatile
    private var installed = false

    /** Safe to call more than once; the first call wins. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        WajubMobile.device = DeviceEnvironment(
            platform = "android",
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            languageTags = {
                val locales = LocaleList.getDefault()
                (0 until locales.size()).map { locales[it].toLanguageTag() }
            },
            timeZone = { TimeZone.getDefault().id },
            screen = { screenSize(app) },
            // Private to the app, kept across updates, never backed up to another device.
            installIds = FileInstallIdStore(File(app.noBackupFilesDir, "wajub-install-id")),
        )
    }

    // The main display in physical pixels, portrait: "1080x2400".
    @Suppress("DEPRECATION")
    private fun screenSize(context: Context): String? =
        runCatching {
            val metrics = DisplayMetrics()
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.defaultDisplay.getRealMetrics(metrics)
            val short = minOf(metrics.widthPixels, metrics.heightPixels)
            val long = maxOf(metrics.widthPixels, metrics.heightPixels)
            "${short}x$long"
        }.getOrNull()
}

/** Runs [WajubAndroid.install] when the app starts; it serves no data. */
class WajubInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { WajubAndroid.install(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
