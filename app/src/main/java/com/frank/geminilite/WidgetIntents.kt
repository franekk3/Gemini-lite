package com.frank.geminilite

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Shared launch intents for the home-screen widgets. */
internal object WidgetIntents {

    fun newChat(context: Context, requestCode: Int): PendingIntent =
        activity(context, MainActivity.ACTION_NEW_CHAT, MainActivity.EXTRA_NEW_CHAT, requestCode)

    fun askVoice(context: Context, requestCode: Int): PendingIntent =
        activity(context, MainActivity.ACTION_ASK_VOICE, MainActivity.EXTRA_VOICE, requestCode)

    private fun activity(
        context: Context,
        action: String,
        extraKey: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            putExtra(extraKey, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
