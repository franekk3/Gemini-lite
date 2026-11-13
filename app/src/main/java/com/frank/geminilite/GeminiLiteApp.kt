package com.frank.geminilite

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Warms up the Chromium engine while the system splash screen is still visible.
 * Creating the first WebView is one of the slowest parts of a cold start; doing it
 * here means the real WebView in [MainActivity] attaches noticeably faster.
 */
class GeminiLiteApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        try {
            val warmUp = WebView(this)
            warmUp.settings.javaScriptEnabled = true
            warmUp.loadUrl("about:blank")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    warmUp.stopLoading()
                    warmUp.destroy()
                } catch (_: Throwable) {
                    // Never let warm-up cleanup affect app startup.
                }
            }, 5_000)
        } catch (_: Throwable) {
            // A failed warm-up only costs a little startup time.
        }
    }
}
