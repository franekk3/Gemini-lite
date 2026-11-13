package com.frank.geminilite

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * A single-purpose launcher widget: it always opens Gemini in a brand new
 * conversation with the composer focused, ready for the keyboard.
 */
class NewChatWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context))
        }
    }

    companion object {

        fun buildRemoteViews(context: Context): RemoteViews {
            val dark = WidgetThemePrefs.resolveDark(context)
            val views = RemoteViews(context.packageName, R.layout.widget_new_chat)
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                if (dark) R.drawable.widget_background_dark else R.drawable.widget_background
            )
            views.setImageViewResource(
                R.id.widget_icon,
                if (dark) R.drawable.ic_spark_white else R.drawable.ic_spark
            )
            views.setTextColor(
                R.id.widget_text,
                context.getColor(if (dark) R.color.widget_text_dark else R.color.widget_text)
            )
            views.setOnClickPendingIntent(
                R.id.widget_root,
                WidgetIntents.newChat(context, requestCode = 1001)
            )
            views.setContentDescription(
                R.id.widget_root,
                context.getString(R.string.widget_description)
            )
            return views
        }
    }
}
