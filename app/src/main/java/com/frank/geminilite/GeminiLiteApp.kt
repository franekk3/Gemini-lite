package com.frank.geminilite

import android.app.Application
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Kicks off the real Gemini WebView as early as possible. The preloaded page is
 * adopted by [MainActivity], so the heavy Chromium initialization and the network
 * request happen while the system splash screen is still visible.
 */
class GeminiLiteApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        WebViewPreloader.prepare(this)
        // Widget appearance is not on the cold-start critical path.
        Handler(Looper.getMainLooper()).postDelayed(
            { WidgetThemeUpdater.refreshAll(this) },
            2_000
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WidgetThemeUpdater.refreshAll(this)
    }
}
