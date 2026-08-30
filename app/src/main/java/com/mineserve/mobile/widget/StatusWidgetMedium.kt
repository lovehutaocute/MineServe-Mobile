package com.mineserve.mobile.widget

import android.appwidget.AppWidgetProvider
import com.mineserve.mobile.data.WidgetUpdater

/** 中尺寸（4×2）服务器状态组件：状态 + TPS/内存 + 启停按钮。 */
class StatusWidgetMedium : AppWidgetProvider() {
    override fun onUpdate(context: android.content.Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.refresh(context)
    }
}
