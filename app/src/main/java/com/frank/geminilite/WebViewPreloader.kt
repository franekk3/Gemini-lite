package com.frank.geminilite

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Starts loading Gemini before the activity exists. WebView/Chromium creation is one
 * of the most expensive parts of a cold start; doing it while the system splash is
 * showing and letting the network request start immediately removes it from the
 * critical path. The warmed WebView is handed to [MainActivity] and reparented.
 */
internal object WebViewPreloader {

    private const val TAG = "GeminiPreload"
    private const val DESTROY_DELAY_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private var prepared: GeminiWebView? = null
    private var consumed = false

    fun prepare(context: Context) {
        if (consumed || prepared != null) return
        try {
            val webView = GeminiWebView(MutableContextWrapper(context.applicationContext))
            prepared = webView
            webView.loadUrl(GeminiWebView.HOME_URL)
            if (BuildConfig.DEBUG) {
                val elapsed = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                Log.d(TAG, "Preloading Gemini at +${elapsed}ms")
            }
            handler.postDelayed({ discard() }, DESTROY_DELAY_MS)
        } catch (error: Throwable) {
            // A failed preload must never stop the app from starting normally.
            Log.w(TAG, "WebView preload failed", error)
            prepared = null
        }
    }

    /** Hands the preloaded WebView to the activity, or null if none is ready. */
    fun take(): GeminiWebView? {
        val webView = prepared ?: return null
        prepared = null
        consumed = true
        handler.removeCallbacksAndMessages(null)
        if (BuildConfig.DEBUG) Log.d(TAG, "Adopting preloaded WebView")
        return webView
    }

    fun discard() {
        consumed = true
        handler.removeCallbacksAndMessages(null)
        val webView = prepared ?: return
        if (BuildConfig.DEBUG) Log.d(TAG, "Discarding preloaded WebView")
        prepared = null
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Throwable) {
            // Best-effort cleanup.
        }
    }
}
