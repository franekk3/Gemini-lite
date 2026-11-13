package com.frank.geminilite

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/** Pushes the current widget appearance to every placed widget. */
object WidgetThemeUpdater {

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)

        val newChatIds = manager.getAppWidgetIds(
            ComponentName(context, NewChatWidgetProvider::class.java)
        )
        if (newChatIds.isNotEmpty()) {
            val views = NewChatWidgetProvider.buildRemoteViews(context)
            newChatIds.forEach { manager.updateAppWidget(it, views) }
        }

        val askIds = manager.getAppWidgetIds(
            ComponentName(context, AskGeminiWidgetProvider::class.java)
        )
        if (askIds.isNotEmpty()) {
            val views = AskGeminiWidgetProvider.buildRemoteViews(context)
            askIds.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
