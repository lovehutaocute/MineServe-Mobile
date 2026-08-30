package com.mineserve.mobile.widget

import android.appwidget.AppWidgetProvider
import com.mineserve.mobile.data.WidgetUpdater

/** 模组与插件预览（4×3）：图标 + 名称 + 启用状态列表。 */
class ModPluginWidget : AppWidgetProvider() {
    override fun onUpdate(context: android.content.Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.refresh(context)
    }
}
