package com.mineserve.mobile.widget

import android.appwidget.AppWidgetProvider
import com.mineserve.mobile.data.WidgetUpdater

/** 控制台管理（4×3）：核心/Java 选择 + 启停 + 存档保存与备份。 */
class ConsoleWidget : AppWidgetProvider() {
    override fun onUpdate(context: android.content.Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.refresh(context)
    }
}
