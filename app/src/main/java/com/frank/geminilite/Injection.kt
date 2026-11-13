package com.frank.geminilite

import android.content.Context

/** Caches the injected JavaScript so page loads never touch the disk twice. */
object Injection {

    private const val SCRIPT_ASSET = "gemini.js"

    /** Requests composer focus; waits for the page script if it is still initialising. */
    const val FOCUS_COMMAND =
        "(function(){var end=Date.now()+9000;function go(){" +
            "try{if(window.__gl&&window.__gl.focus){window.__gl.focus(9000);return;}}catch(e){}" +
            "if(Date.now()<end)setTimeout(go,100);}go();})()"

    /** Starts a fresh conversation using the in-page control, with a reload fallback. */
    const val NEW_CHAT_COMMAND =
        "(function(){try{return !!(window.__gl&&window.__gl.newChat&&window.__gl.newChat());}" +
            "catch(e){return false;}})()"

    /** Clicks Gemini's dictation button as soon as it is available. */
    const val VOICE_COMMAND =
        "(function(){try{return !!(window.__gl&&window.__gl.voice&&window.__gl.voice());}" +
            "catch(e){return false;}})()"

    @Volatile
    private var cachedScript: String? = null

    fun script(context: Context): String {
        cachedScript?.let { return it }
        return synchronized(this) {
            cachedScript ?: runCatching {
                context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
            }.getOrDefault("").also { cachedScript = it }
        }
    }
}
