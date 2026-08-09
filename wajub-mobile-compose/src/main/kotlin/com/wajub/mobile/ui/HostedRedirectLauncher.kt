package com.wajub.mobile.ui

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens PSP URLs in Chrome Custom Tabs — never an embedded WebView. */
object HostedRedirectLauncher {
    fun open(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
            true
        } catch (_: Exception) {
            false
        }
    }
}
