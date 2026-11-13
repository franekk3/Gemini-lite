package com.frank.geminilite

import android.content.Context
import android.content.res.Configuration

enum class WidgetAppearance {
    AUTO,
    BRIGHT,
    DARK
}

/** Global appearance setting for both home-screen widgets. */
object WidgetThemePrefs {

    private const val PREFS = "gemini_lite_widgets"
    private const val KEY_APPEARANCE = "widget_appearance"

    fun get(context: Context): WidgetAppearance {
        val raw = prefs(context).getString(KEY_APPEARANCE, WidgetAppearance.AUTO.name)
        return runCatching { WidgetAppearance.valueOf(raw ?: "") }
            .getOrDefault(WidgetAppearance.AUTO)
    }

    fun set(context: Context, appearance: WidgetAppearance) {
        prefs(context).edit().putString(KEY_APPEARANCE, appearance.name).apply()
    }

    fun resolveDark(context: Context): Boolean = when (get(context)) {
        WidgetAppearance.AUTO -> isSystemDark(context)
        WidgetAppearance.BRIGHT -> false
        WidgetAppearance.DARK -> true
    }

    private fun isSystemDark(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
