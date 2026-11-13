package com.frank.geminilite

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Project information and the widget appearance setting. */
class AboutActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DARK = "com.frank.geminilite.extra.ABOUT_DARK"
        private const val GITHUB_URL = "https://github.com/franekk3/Gemini-lite"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getBooleanExtra(EXTRA_DARK, isSystemDark())
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val root = findViewById<ScrollView>(R.id.about_root)
        val background = if (dark) Color.rgb(19, 19, 20) else Color.WHITE
        val primary = if (dark) Color.WHITE else Color.rgb(24, 24, 27)
        val secondary = if (dark) Color.rgb(168, 171, 175) else Color.rgb(95, 99, 104)
        val accent = ContextCompat.getColor(this, R.color.brand_violet)

        window.setBackgroundDrawable(ColorDrawable(background))
        root.setBackgroundColor(background)

        val back = findViewById<ImageButton>(R.id.about_back)
        back.setColorFilter(primary)
        back.setOnClickListener { finish() }

        findViewById<ImageView>(R.id.about_logo).setImageResource(
            if (dark) R.drawable.ic_spark_white else R.drawable.ic_spark
        )

        val title = findViewById<TextView>(R.id.about_title)
        val version = findViewById<TextView>(R.id.about_version)
        val description = findViewById<TextView>(R.id.about_description)
        val projectSection = findViewById<TextView>(R.id.about_section_project)
        val github = findViewById<TextView>(R.id.about_github)
        val widgetsSection = findViewById<TextView>(R.id.about_section_widgets)
        val widgetHint = findViewById<TextView>(R.id.about_widget_hint)
        val aboutSection = findViewById<TextView>(R.id.about_section_about)
        val disclaimer = findViewById<TextView>(R.id.about_disclaimer)
        val license = findViewById<TextView>(R.id.about_license)

        title.setTextColor(primary)
        version.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        version.setTextColor(secondary)
        description.setTextColor(primary)
        projectSection.setTextColor(secondary)
        widgetsSection.setTextColor(secondary)
        widgetHint.setTextColor(secondary)
        aboutSection.setTextColor(secondary)
        disclaimer.setTextColor(secondary)
        license.setTextColor(secondary)

        github.setTextColor(accent)
        github.paintFlags = github.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        github.setOnClickListener { openUrl(GITHUB_URL) }

        val group = findViewById<RadioGroup>(R.id.about_widget_group)
        val buttons = listOf(
            findViewById<RadioButton>(R.id.about_theme_auto),
            findViewById<RadioButton>(R.id.about_theme_bright),
            findViewById<RadioButton>(R.id.about_theme_dark)
        )
        buttons.forEach { button ->
            button.setTextColor(primary)
            button.buttonTintList = ColorStateList.valueOf(accent)
        }

        group.check(
            when (WidgetThemePrefs.get(this)) {
                WidgetAppearance.AUTO -> R.id.about_theme_auto
                WidgetAppearance.BRIGHT -> R.id.about_theme_bright
                WidgetAppearance.DARK -> R.id.about_theme_dark
            }
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            val appearance = when (checkedId) {
                R.id.about_theme_bright -> WidgetAppearance.BRIGHT
                R.id.about_theme_dark -> WidgetAppearance.DARK
                else -> WidgetAppearance.AUTO
            }
            WidgetThemePrefs.set(this, appearance)
            WidgetThemeUpdater.refreshAll(this)
            Toast.makeText(this, R.string.about_widget_saved, Toast.LENGTH_SHORT).show()
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)

        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // No browser available; nothing to do.
        }
    }

    private fun isSystemDark(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
