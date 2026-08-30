package com.mineserve.mobile.widget

import android.appwidget.AppWidgetProvider
import com.mineserve.mobile.data.WidgetUpdater

/** 状态总览（4×1 长条）：状态 + 在线人数 + TPS/内存/运行时长/CPU 指标。 */
class OverviewWidget : AppWidgetProvider() {
    override fun onUpdate(context: android.content.Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.refresh(context)
    }
}
