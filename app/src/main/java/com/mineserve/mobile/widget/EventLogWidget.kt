package com.mineserve.mobile.widget

import android.appwidget.AppWidgetProvider
import com.mineserve.mobile.data.WidgetUpdater

/** 事件日志（2×4 竖条）：玩家进离服与聊天公告的实时列表。 */
class EventLogWidget : AppWidgetProvider() {
    override fun onUpdate(context: android.content.Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.refresh(context)
    }
}
