package com.wajub.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens PSP URLs in Chrome Custom Tabs — never an embedded WebView. */
object HostedRedirectLauncher {
    fun open(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        return try {
            val tabs = CustomTabsIntent.Builder().build()
            // Started from outside an activity, it needs a task of its own.
            if (!context.isActivity()) tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tabs.launchUrl(context, uri)
            true
        } catch (_: Exception) {
            false
        }
    }

    private tailrec fun Context.isActivity(): Boolean =
        when (this) {
            is Activity -> true
            is ContextWrapper -> baseContext.isActivity()
            else -> false
        }
}
