package com.frank.geminilite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A WebView tuned for a native-feeling Gemini client: persistent cache, a Chrome-like
 * user agent (Google sign-in is picky about embedded WebViews), popup support for auth
 * flows, and app-store deep links blocked because the wrapper already is the app.
 */
class GeminiWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    companion object {
        const val HOME_URL = "https://gemini.google.com/app"
        private const val TAG = "GeminiWebView"
        private const val STORE_PACKAGE = "com.google.android.apps.bard"

        private val AUTH_HOSTS = setOf(
            "accounts.google.com",
            "myaccount.google.com",
            "ogs.google.com",
            "consent.google.com",
            "support.google.com",
            "policies.google.com"
        )

        fun isGeminiUrl(uri: Uri?): Boolean {
            val host = uri?.host?.lowercase() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https" && scheme != "http") return false
            return host == "gemini.google.com" || host.endsWith(".gemini.google.com")
        }

        /** Play Store / App Store links for the official Gemini app are useless here. */
        fun isBlockedStoreUri(uri: Uri): Boolean {
            val value = uri.toString().lowercase()
            if (value.contains(STORE_PACKAGE)) return true
            if (value.contains("apps.apple.com") && value.contains("google-gemini")) return true
            return false
        }

        private fun isInternalUri(uri: Uri): Boolean {
            val host = uri.host?.lowercase() ?: return false
            if (host == "gemini.google.com" || host.endsWith(".gemini.google.com")) return true
            if (host in AUTH_HOSTS) return true
            if (host.endsWith(".gstatic.com") || host == "gstatic.com") return true
            if (host.endsWith(".googleusercontent.com")) return true
            if (host.endsWith(".googleapis.com")) return true
            if (host.endsWith(".googlevideo.com")) return true
            if (host == "www.google.com") {
                val path = uri.path.orEmpty()
                return path.startsWith("/accounts") ||
                    path.startsWith("/signin") ||
                    path.startsWith("/sorry")
            }
            return false
        }

        /** Turns the "wv" WebView UA into a regular Chrome mobile UA. */
        private fun chromeLikeUserAgent(defaultUserAgent: String): String {
            var ua = defaultUserAgent
            ua = ua.replace("; wv", "")
            ua = Regex(" Build/[^;)]+").replace(ua, "")
            ua = ua.replace("Version/4.0 ", "")
            return ua.replace(Regex(" {2,}"), " ").trim()
        }
    }

    var onPageStartedCallback: (() -> Unit)? = null
    var onPageCommitCallback: (() -> Unit)? = null
    var onPageFinishedCallback: (() -> Unit)? = null
    var onProgressChanged: ((Int) -> Unit)? = null
    var onMainFrameError: ((errorCode: Int, description: String) -> Unit)? = null
    var onExternalUrl: ((Uri) -> Unit)? = null
    var onThemeChanged: ((dark: Boolean, color: Int) -> Unit)? = null
    var onKeyboardRequested: (() -> Unit)? = null
    var onFileChooserRequest: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit)? = null
    var onPermissionRequestCallback: ((PermissionRequest) -> Unit)? = null
    var onCreateWindowRequest: ((Message) -> Boolean)? = null
    var onCloseWindowRequest: (() -> Unit)? = null
    var onRendererGoneCallback: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false

        @SuppressLint("SetJavaScriptEnabled")
        settings.run {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // Uploads are staged as app-private file:// URIs because the WebView
            // renderer often cannot read Photo Picker content:// URIs directly.
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            safeBrowsingEnabled = true
            offscreenPreRaster = true
            userAgentString = chromeLikeUserAgent(WebSettings.getDefaultUserAgent(context))
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@GeminiWebView, true)
        }

        addJavascriptInterface(JsBridge(), "GeminiHost")
        webViewClient = GeminiClient()
        webChromeClient = GeminiChromeClient()
    }

    fun reportConnectionLost() {
        onMainFrameError?.invoke(-1, "")
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        return when {
            scheme == "http" || scheme == "https" -> when {
                isBlockedStoreUri(uri) -> true
                isInternalUri(uri) -> false
                else -> {
                    onExternalUrl?.invoke(uri)
                    true
                }
            }
            scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript" -> false
            else -> {
                onExternalUrl?.invoke(uri)
                true
            }
        }
    }

    private inner class GeminiClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleNavigation(request.url)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleNavigation(Uri.parse(url))

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onPageStartedCallback?.invoke()
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            super.onPageCommitVisible(view, url)
            onPageCommitCallback?.invoke()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            onPageFinishedCallback?.invoke()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                onMainFrameError?.invoke(error.errorCode, error.description?.toString().orEmpty())
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "Renderer gone (crashed=${detail.didCrash()})")
            onRendererGoneCallback?.invoke()
            return true
        }
    }

    private inner class GeminiChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            onProgressChanged?.invoke(newProgress)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "console: ${consoleMessage.message()} @${consoleMessage.lineNumber()}")
            }
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            val handler = onFileChooserRequest
            if (handler == null) {
                filePathCallback.onReceiveValue(null)
            } else {
                handler(filePathCallback, fileChooserParams)
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val handler = onPermissionRequestCallback
            if (handler == null) request.deny() else handler(request)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, false, false)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean = onCreateWindowRequest?.invoke(resultMsg) ?: false

        override fun onCloseWindow(window: WebView) {
            val handler = onCloseWindowRequest
            if (handler == null) super.onCloseWindow(window) else handler()
        }
    }

    inner class JsBridge {

        @JavascriptInterface
        fun theme(dark: Boolean, r: Int, g: Int, b: Int) {
            post {
                onThemeChanged?.invoke(
                    dark,
                    Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                )
            }
        }

        @JavascriptInterface
        fun keyboard() {
            post { onKeyboardRequested?.invoke() }
        }
    }
}
