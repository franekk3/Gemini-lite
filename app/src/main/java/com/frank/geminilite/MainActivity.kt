package com.frank.geminilite

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_NEW_CHAT = "com.frank.geminilite.action.NEW_CHAT"
        const val ACTION_ASK_VOICE = "com.frank.geminilite.action.ASK_VOICE"
        const val EXTRA_NEW_CHAT = "com.frank.geminilite.extra.NEW_CHAT"
        const val EXTRA_VOICE = "com.frank.geminilite.extra.VOICE"
        const val EXTRA_FOCUS = "com.frank.geminilite.extra.FOCUS"

        private const val STATE_WEBVIEW = "state_webview"
        private const val STATE_PENDING_FOCUS = "state_pending_focus"
        private const val STATE_PENDING_VOICE = "state_pending_voice"
        private const val STATE_INTENT_CONSUMED = "state_intent_consumed"
        private const val STATE_LAST_URL = "state_last_url"
        private const val TAG = "GeminiLite"
    }

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var popupContainer: FrameLayout
    private lateinit var loadProgress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button

    private lateinit var webView: GeminiWebView
    private var popupWebView: GeminiWebView? = null

    private var pendingFocus = false
    private var pendingVoice = false
    private var activityResumed = false
    private var currentDark = false
    private var lastThemeKey: Int? = null
    private var injectedUrl: String? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var awaitingMicrophonePermission = false
    private var pendingCapture: PendingCapture? = null

    private data class PendingCapture(
        val callback: ValueCallback<Array<Uri>>,
        val params: WebChromeClient.FileChooserParams,
        val file: File,
        val uri: Uri
    )

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            // Chromium's FileChooserParams.parseResult() only looks at Intent.getData()
            // and returns null for the Android Photo Picker, which delivers its picks
            // through ClipData. Parse both forms ourselves.
            val uris = extractFileChooserUris(result.resultCode, result.data)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "File chooser result: code=${result.resultCode}, " +
                        "uri=${result.data?.data}, " +
                        "clip=${result.data?.clipData?.itemCount}, " +
                        "uris=${uris?.size}"
                )
            }
            stageFilesForWebView(callback, uris)
        }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val capture = pendingCapture ?: return@registerForActivityResult
            if (granted) {
                launchCamera(capture)
            } else {
                pendingCapture = null
                capture.file.delete()
                launchPicker(capture.callback, capture.params)
            }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val capture = pendingCapture ?: return@registerForActivityResult
            pendingCapture = null
            if (result.resultCode == RESULT_OK && capture.file.length() > 0L) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Camera capture: ${capture.file.length()} bytes")
                }
                capture.callback.onReceiveValue(arrayOf(Uri.fromFile(capture.file)))
            } else {
                capture.file.delete()
                capture.callback.onReceiveValue(null)
            }
        }

    private fun extractFileChooserUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val uris = LinkedHashSet<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let { uris.add(it) }
            }
        }
        data.data?.let { uris.add(it) }
        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * WebView's renderer frequently cannot open content:// URIs delivered by the
     * Android Photo Picker (the preview ends up as a broken image). Staging the
     * bytes in the app cache and passing file:// URIs is the reliable path.
     */
    private fun stageFilesForWebView(
        callback: ValueCallback<Array<Uri>>?,
        uris: Array<Uri>?
    ) {
        if (callback == null) return
        if (uris.isNullOrEmpty()) {
            callback.onReceiveValue(null)
            return
        }
        Thread {
            val localFiles = uris.map { uri -> copyToUploadCache(uri) ?: uri }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Staged uploads: ${localFiles.joinToString()}")
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    callback.onReceiveValue(localFiles.toTypedArray())
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun copyToUploadCache(uri: Uri): Uri? {
        return try {
            val resolver = contentResolver
            val mime = resolver.getType(uri)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?: displayName(uri)?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }
                ?: "bin"
            val directory = File(cacheDir, "uploads").apply { mkdirs() }
            val target = File.createTempFile("upload_", ".$extension", directory)
            val copied = resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied || target.length() == 0L) {
                target.delete()
                null
            } else {
                Uri.fromFile(target)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not stage $uri", error)
            null
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanUploadCache() {
        try {
            val directory = File(cacheDir, "uploads")
            if (!directory.isDirectory) return
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            directory.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        } catch (_: Exception) {
            // Cache cleanup is best effort.
        }
    }

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            awaitingMicrophonePermission = false
            val request = pendingPermissionRequest ?: return@registerForActivityResult
            pendingPermissionRequest = null
            if (granted) {
                if (::webView.isInitialized) webView.resumeTimers()
                runCatching {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                }
            } else {
                request.deny()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        webContainer = findViewById(R.id.web_container)
        popupContainer = findViewById(R.id.popup_container)
        loadProgress = findViewById(R.id.load_progress)
        errorView = findViewById(R.id.error_view)
        errorTitle = findViewById(R.id.error_title)
        errorMessage = findViewById(R.id.error_message)
        retryButton = findViewById(R.id.retry_button)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        root.postDelayed(
            { Thread { cleanUploadCache() }.apply { isDaemon = true }.start() },
            4_000
        )
        applyWindowInsets()
        setupBackNavigation()

        retryButton.setOnClickListener {
            errorView.visibility = View.GONE
            if (::webView.isInitialized) {
                if (webView.url == null) {
                    webView.loadUrl(GeminiWebView.HOME_URL)
                } else {
                    webView.reload()
                }
            }
        }

        val savedWebViewState = savedInstanceState?.getBundle(STATE_WEBVIEW)
        val intentAlreadyConsumed =
            savedInstanceState?.getBoolean(STATE_INTENT_CONSUMED, false) == true
        val wantsNewChat = !intentAlreadyConsumed && isNewChatIntent(intent)
        val wantsVoice = !intentAlreadyConsumed && isVoiceIntent(intent)
        val savedUrl = savedInstanceState?.getString(STATE_LAST_URL)
        val savedUrlIsFreshChat = savedUrl != null && isFreshChatUrl(savedUrl)
        // A saved conversation is restored into a fresh WebView. A saved fresh
        // chat (or no saved state at all) can reuse the already-loading /app page.
        val shouldUsePreloaded =
            wantsNewChat || wantsVoice || savedWebViewState == null || savedUrlIsFreshChat

        val preloaded = if (shouldUsePreloaded) WebViewPreloader.take() else null
        if (!shouldUsePreloaded) WebViewPreloader.discard()
        val usingPreloaded = preloaded != null
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "usingPreloaded=$usingPreloaded, savedState=${savedWebViewState != null}, " +
                    "newChat=$wantsNewChat, voice=$wantsVoice"
            )
        }

        webView = createWebView(preloaded)
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView.syncSidebarSwipeState()
        if (usingPreloaded && webView.progress < 100) {
            loadProgress.visibility = View.VISIBLE
        }

        applyPageTheme(
            dark = isSystemInDarkMode(),
            color = ContextCompat.getColor(this, R.color.window_bg)
        )

        loadInitialPage(savedInstanceState, usingPreloaded)
        clearLaunchIntentFlags(intent)

        if (usingPreloaded) {
            // The preloaded page may have finished before the activity callbacks
            // were attached, so replay the usual finish handling once.
            webView.post {
                if (!::webView.isInitialized) return@post
                if (webView.url == null && webView.progress >= 100) {
                    webView.loadUrl(GeminiWebView.HOME_URL)
                    return@post
                }
                if (webView.progress >= 100) {
                    loadProgress.visibility = View.GONE
                    injectPageScript()
                    when {
                        pendingVoice -> requestVoiceInput()
                        pendingFocus -> requestComposerFocus()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        clearLaunchIntentFlags(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
        popupWebView?.onResume()
        activityResumed = true
        if (pendingVoice) requestVoiceInput()
    }

    override fun onPause() {
        activityResumed = false
        popupWebView?.onPause()
        // Keep the page alive while the system microphone permission dialog is up,
        // otherwise Chromium can drop the pending getUserMedia request.
        if (::webView.isInitialized && !awaitingMicrophonePermission) {
            webView.onPause()
            webView.pauseTimers()
        }
        // Persist cookies right away so an authenticated session survives task death.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            runCatching {
                val state = Bundle()
                webView.saveState(state)
                outState.putBundle(STATE_WEBVIEW, state)
                outState.putBoolean(STATE_PENDING_FOCUS, pendingFocus)
                outState.putBoolean(STATE_PENDING_VOICE, pendingVoice)
                outState.putBoolean(STATE_INTENT_CONSUMED, true)
                outState.putString(STATE_LAST_URL, webView.url)
            }
        }
    }

    override fun onDestroy() {
        closePopup()
        if (!isChangingConfigurations && ::webView.isInitialized) {
            webContainer.removeAllViews()
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
        super.onDestroy()
    }

    // region WebView setup

    private fun createWebView(preloaded: GeminiWebView? = null): GeminiWebView {
        val view = preloaded ?: GeminiWebView(this)
        if (preloaded != null) {
            // The WebView was created with an application context while preloading;
            // switch it to the activity for dialogs, pickers and other UI.
            (preloaded.context as? MutableContextWrapper)?.baseContext = this
        }
        view.apply {
            onPageStartedCallback = {
                injectedUrl = null
                errorView.visibility = View.GONE
                loadProgress.visibility = View.VISIBLE
            }
            onProgressChanged = { value ->
                loadProgress.visibility = if (value in 0..99) View.VISIBLE else View.GONE
            }
            onPageCommitCallback = { injectPageScript() }
            onPageFinishedCallback = {
                loadProgress.visibility = View.GONE
                injectPageScript()
                when {
                    pendingVoice -> requestVoiceInput()
                    pendingFocus -> requestComposerFocus()
                }
            }
            onMainFrameError = { _, description ->
                loadProgress.visibility = View.GONE
                showError(description)
            }
            onExternalUrl = { uri -> openExternal(uri) }
            onThemeChanged = { dark, color -> applyPageTheme(dark, color) }
            onKeyboardRequested = {
                pendingFocus = false
                showKeyboardFor(this)
            }
            onFileChooserRequest = { callback, params -> launchFileChooser(callback, params) }
            onPermissionRequestCallback = { request -> handlePermissionRequest(request) }
            onCreateWindowRequest = { message -> showPopup(message) }
            onRendererGoneCallback = { recoverFromRendererCrash() }
            onSidebarSwipe = { openSidebar() }
            onSidebarSwipeClose = { closeSidebar() }
            onAboutRequested = { openAboutScreen() }
        }
        view.syncSidebarSwipeState()
        return view
    }

    private fun loadInitialPage(savedInstanceState: Bundle?, usingPreloaded: Boolean) {
        val intentAlreadyConsumed =
            savedInstanceState?.getBoolean(STATE_INTENT_CONSUMED, false) == true

        if (!intentAlreadyConsumed && isVoiceIntent(intent)) {
            pendingFocus = false
            pendingVoice = true
            if (!usingPreloaded) webView.loadUrl(GeminiWebView.HOME_URL)
            return
        }

        if (!intentAlreadyConsumed && isNewChatIntent(intent)) {
            pendingFocus = true
            if (!usingPreloaded) webView.loadUrl(GeminiWebView.HOME_URL)
            return
        }

        val savedState = savedInstanceState?.getBundle(STATE_WEBVIEW)
        if (savedState != null) {
            if (usingPreloaded) return
            pendingFocus = savedInstanceState.getBoolean(STATE_PENDING_FOCUS, false)
            pendingVoice = savedInstanceState.getBoolean(STATE_PENDING_VOICE, false)
            if (webView.restoreState(savedState) == null) {
                webView.loadUrl(GeminiWebView.HOME_URL)
            }
            return
        }

        val deepLink = intent?.data
        when {
            intent?.action == Intent.ACTION_VIEW && GeminiWebView.isGeminiUrl(deepLink) ->
                webView.loadUrl(deepLink.toString())

            !usingPreloaded -> webView.loadUrl(GeminiWebView.HOME_URL)
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        when {
            isVoiceIntent(intent) -> startVoice()

            isNewChatIntent(intent) -> startNewChat()

            intent.action == Intent.ACTION_VIEW &&
                GeminiWebView.isGeminiUrl(intent.data) -> {
                pendingFocus = false
                webView.loadUrl(intent.data.toString())
            }

            intent.getBooleanExtra(EXTRA_FOCUS, false) -> {
                pendingFocus = true
                requestComposerFocus()
            }
        }
    }

    private fun isNewChatIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.action == ACTION_NEW_CHAT ||
            intent.getBooleanExtra(EXTRA_NEW_CHAT, false)
    }

    private fun isVoiceIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.action == ACTION_ASK_VOICE ||
            intent.getBooleanExtra(EXTRA_VOICE, false)
    }

    /**
     * The launch intent is consumed once handled so that a later activity
     * recreation restores the page instead of starting another new chat.
     */
    private fun clearLaunchIntentFlags(intent: Intent?) {
        intent ?: return
        if (intent.action == ACTION_NEW_CHAT || intent.action == ACTION_ASK_VOICE) {
            intent.action = null
        }
        intent.removeExtra(EXTRA_NEW_CHAT)
        intent.removeExtra(EXTRA_VOICE)
        intent.removeExtra(EXTRA_FOCUS)
    }

    private fun startNewChat() {
        if (!::webView.isInitialized) return
        closePopup()
        pendingFocus = true
        val current = webView.url
        if (current.isNullOrBlank() || current == "about:blank") {
            webView.loadUrl(GeminiWebView.HOME_URL)
            return
        }
        webView.evaluateJavascript(Injection.NEW_CHAT_COMMAND) { result ->
            if (result?.trim('"') != "true") {
                webView.loadUrl(GeminiWebView.HOME_URL)
            }
        }
    }

    private fun requestComposerFocus() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(Injection.FOCUS_COMMAND, null)
    }

    private fun openSidebar() {
        if (!::webView.isInitialized) return
        val current = webView.url?.let { Uri.parse(it) }
        if (current == null || !GeminiWebView.isGeminiUrl(current)) return
        injectPageScript()
        webView.evaluateJavascript(Injection.OPEN_SIDEBAR_COMMAND, null)
    }

    private fun openAboutScreen() {
        startActivity(
            Intent(this, AboutActivity::class.java)
                .putExtra(AboutActivity.EXTRA_DARK, currentDark)
        )
    }

    private fun closeSidebar() {
        if (!::webView.isInitialized) return
        val current = webView.url?.let { Uri.parse(it) }
        if (current == null || !GeminiWebView.isGeminiUrl(current)) return
        injectPageScript()
        webView.evaluateJavascript(Injection.CLOSE_SIDEBAR_COMMAND) { result ->
            if (result?.trim('"') == "true") {
                webView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    /**
     * Opens a fresh chat and starts Gemini's own dictation. The page script
     * waits for the mic button and clicks it; no keyboard is requested.
     */
    private fun startVoice() {
        if (!::webView.isInitialized) return
        closePopup()
        pendingFocus = false
        pendingVoice = true
        if (isFreshChatUrl(webView.url)) {
            if (activityResumed) requestVoiceInput()
        } else {
            webView.loadUrl(GeminiWebView.HOME_URL)
        }
    }

    private fun requestVoiceInput() {
        if (!::webView.isInitialized || !activityResumed) return
        val current = webView.url?.let { Uri.parse(it) }
        if (current == null || !GeminiWebView.isGeminiUrl(current)) return
        // A page that is still loading will be handled from onPageFinished().
        if (webView.progress < 100) return

        pendingVoice = false
        injectPageScript()
        webView.evaluateJavascript(Injection.VOICE_COMMAND, null)
    }

    private fun isFreshChatUrl(url: String?): Boolean {
        val uri = url?.let { Uri.parse(it) } ?: return false
        if (!GeminiWebView.isGeminiUrl(uri)) return false
        return (uri.path ?: return false).trimEnd('/') == "/app"
    }

    private fun injectPageScript() {
        if (!::webView.isInitialized) return
        val current = webView.url ?: return
        if (!GeminiWebView.isGeminiUrl(Uri.parse(current))) return
        // onPageCommitVisible and onPageFinished both call this; a page only needs
        // the script once. onPageStarted clears the marker for reloads.
        if (current == injectedUrl) return
        val script = Injection.script(applicationContext)
        if (script.isNotEmpty()) {
            injectedUrl = current
            webView.evaluateJavascript(script, null)
        }
    }

    private fun showKeyboardFor(target: WebView) {
        if (isFinishing || isDestroyed) return
        target.requestFocus()
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        val show = Runnable {
            if (!isFinishing && !isDestroyed) {
                manager.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        show.run()
        target.postDelayed(show, 140)
        target.postDelayed(show, 450)
        target.postDelayed(show, 900)
    }

    // endregion

    // region Navigation

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (closePopup()) return
                    if (::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    private fun openExternal(uri: Uri) {
        if (GeminiWebView.isBlockedStoreUri(uri)) return
        val intent = runCatching {
            if (uri.scheme.equals("intent", ignoreCase = true)) {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
        }.getOrNull() ?: return

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing handles it; stay in the app.
        } catch (_: Exception) {
            // Malformed external intent; ignore.
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyPageTheme(dark: Boolean, color: Int) {
        val key = (color shl 1) or if (dark) 1 else 0
        if (lastThemeKey == key) return
        lastThemeKey = key
        currentDark = dark

        window.setBackgroundDrawable(ColorDrawable(color))
        root.setBackgroundColor(color)
        errorView.setBackgroundColor(color)
        popupContainer.setBackgroundColor(color)
        loadProgress.indeterminateTintList = android.content.res.ColorStateList.valueOf(
            if (dark) Color.rgb(176, 164, 255) else Color.rgb(98, 84, 214)
        )
        if (::webView.isInitialized) webView.setBackgroundColor(color)

        val primaryText = if (dark) Color.WHITE else Color.rgb(24, 24, 27)
        val secondaryText = if (dark) Color.rgb(196, 196, 204) else Color.rgb(95, 95, 104)
        errorTitle.setTextColor(primaryText)
        errorMessage.setTextColor(secondaryText)
        retryButton.setTextColor(if (dark) Color.rgb(196, 184, 255) else Color.rgb(88, 74, 200))

        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun isSystemInDarkMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    // endregion

    // region Errors and recovery

    private fun showError(@Suppress("UNUSED_PARAMETER") description: String) {
        errorMessage.text = getString(R.string.error_message)
        errorView.visibility = View.VISIBLE
    }

    private fun recoverFromRendererCrash() {
        root.post {
            if (isFinishing || isDestroyed) return@post
            val old = if (::webView.isInitialized) webView else null
            webContainer.removeAllViews()
            if (old != null) runCatching { old.destroy() }
            webView = createWebView()
            webContainer.addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            pendingFocus = false
            webView.loadUrl(GeminiWebView.HOME_URL)
        }
    }

    // endregion

    // region Popups, files, permissions

    private fun showPopup(resultMsg: Message): Boolean {
        if (popupWebView != null) return false
        val popup = GeminiWebView(this).apply {
            onExternalUrl = { uri -> openExternal(uri) }
            onThemeChanged = { dark, color -> applyPageTheme(dark, color) }
            onKeyboardRequested = { showKeyboardFor(this) }
            onFileChooserRequest = { callback, params -> launchFileChooser(callback, params) }
            onPermissionRequestCallback = { request -> handlePermissionRequest(request) }
            onCloseWindowRequest = { root.post { closePopup() } }
        }
        popupContainer.removeAllViews()
        popupContainer.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        popupContainer.visibility = View.VISIBLE
        popupWebView = popup

        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport == null) {
            closePopup()
            return false
        }
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun closePopup(): Boolean {
        val popup = popupWebView ?: return false
        popupWebView = null
        popupContainer.removeAllViews()
        popupContainer.visibility = View.GONE
        runCatching {
            popup.stopLoading()
            popup.destroy()
        }
        return true
    }

    private fun launchFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ) {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        pendingCapture?.let { old ->
            old.callback.onReceiveValue(null)
            old.file.delete()
        }
        pendingCapture = null

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "File chooser: mode=${params.mode}, capture=${params.isCaptureEnabled}, " +
                    "accept=${params.acceptTypes?.joinToString()}"
            )
        }

        if (params.isCaptureEnabled && isImageCapture(params)) {
            startCameraCapture(callback, params)
        } else {
            launchPicker(callback, params)
        }
    }

    private fun isImageCapture(params: WebChromeClient.FileChooserParams): Boolean {
        val types = params.acceptTypes ?: return false
        val hasImage = types.any { it.equals("*/*", true) || it.startsWith("image/", true) }
        val onlyVideo = types.isNotEmpty() && types.all { it.startsWith("video/", true) }
        return hasImage && !onlyVideo
    }

    private fun startCameraCapture(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ) {
        val directory = File(cacheDir, "uploads").apply { mkdirs() }
        val file = File(directory, "camera_${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (error: Exception) {
            Log.w(TAG, "Could not create camera capture file", error)
            launchPicker(callback, params)
            return
        }

        val capture = PendingCapture(callback, params, file, uri)
        pendingCapture = capture

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera(capture)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera(capture: PendingCapture) {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, capture.uri)
            clipData = ClipData.newRawUri("camera-output", capture.uri)
            addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        try {
            val cameraPackage = intent.resolveActivity(packageManager)?.packageName
            if (cameraPackage != null) {
                grantUriPermission(
                    cameraPackage,
                    capture.uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            cameraLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pendingCapture = null
            capture.file.delete()
            launchPicker(capture.callback, capture.params)
        } catch (_: Exception) {
            pendingCapture = null
            capture.file.delete()
            launchPicker(capture.callback, capture.params)
        }
    }

    private fun launchPicker(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ) {
        fileChooserCallback = callback

        // Use the intent WebView built for this input directly. Wrapping it in a
        // chooser can swallow the result on some OEM photo pickers.
        val intent = runCatching { params.createIntent() }.getOrNull()
        if (intent == null) {
            fileChooserCallback = null
            callback.onReceiveValue(null)
            return
        }

        try {
            filePicker.launch(intent)
        } catch (_: ActivityNotFoundException) {
            fileChooserCallback = null
            callback.onReceiveValue(null)
        }
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val resources = request.resources ?: emptyArray()
        val wantsAudio = resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        if (!wantsAudio) {
            request.deny()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            runCatching {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            }.onFailure { request.deny() }
            return
        }

        pendingPermissionRequest?.deny()
        pendingPermissionRequest = request
        awaitingMicrophonePermission = true
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // endregion
}
