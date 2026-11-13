package com.frank.geminilite

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * Search-bar style widget: the body opens a new chat with the composer focused,
 * while the microphone starts Gemini dictation automatically.
 */
class AskGeminiWidgetProvider : AppWidgetProvider() {

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
            val views = RemoteViews(context.packageName, R.layout.widget_ask_gemini)
            val openChat = WidgetIntents.newChat(context, requestCode = 1002)
            views.setOnClickPendingIntent(R.id.ask_widget_root, openChat)
            views.setOnClickPendingIntent(R.id.ask_widget_bar, openChat)
            views.setOnClickPendingIntent(
                R.id.ask_widget_mic,
                WidgetIntents.askVoice(context, requestCode = 1003)
            )
            views.setContentDescription(
                R.id.ask_widget_root,
                context.getString(R.string.widget_ask_content_description)
            )
            views.setContentDescription(
                R.id.ask_widget_mic,
                context.getString(R.string.widget_voice_description)
            )
            return views
        }
    }
}
