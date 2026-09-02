package com.mineserve.mobile.data

import android.content.Context

/** App-wide widget preferences shared by all widget instances. */
object WidgetPreferences {
    private const val PREFS = "widget_preferences"
    private const val ACTIONS_ENABLED = "actions_enabled"

    fun areActionsEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(ACTIONS_ENABLED, true)

    fun setActionsEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ACTIONS_ENABLED, enabled)
            .apply()
    }
}
