package com.mineserve.mobile.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.EventLogStore
import com.mineserve.mobile.data.WidgetEventType
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 事件日志组件的列表数据源。 */
class EventLogWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = EventLogFactory(applicationContext)

    class EventLogFactory(private val context: Context) : RemoteViewsFactory {
        private var events: List<Triple<String, String, String>> = emptyList() // (类型, 主文本, 时间)

        override fun onCreate() {}
        override fun onDataSetChanged() {
            val app = McApplication.get(context)
            val config = runCatching { kotlinx.coroutines.runBlocking { app.repository.configFlow.first() } }.getOrNull()
            val dirName = config?.installedCores
                ?.find { it.name == config.activeCoreName }?.dirName
            if (dirName == null) {
                events = emptyList()
                return
            }
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            events = EventLogStore.snapshot(context, dirName).map { e ->
                val text = when (e.type) {
                    WidgetEventType.JOIN -> context.getString(R.string.widget_event_join, e.player)
                    WidgetEventType.LEAVE -> context.getString(R.string.widget_event_leave, e.player)
                    else -> if (e.message.isBlank()) e.player else "${e.player}: ${e.message}"
                }
                Triple(e.type, text, fmt.format(Date(e.time)))
            }
        }

        override fun onDestroy() {}
        override fun getCount(): Int = events.size
        override fun getViewTypeCount(): Int = 1
        override fun hasStableIds(): Boolean = false
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewAt(position: Int): RemoteViews {
            val (type, text, time) = events[position]
            val views = RemoteViews(context.packageName, R.layout.widget_event_log_item)
            views.setTextViewText(R.id.item_text, text)
            views.setTextViewText(R.id.item_time, time)
            val color = when (type) {
                WidgetEventType.JOIN -> 0xFF2FBF87.toInt()
                WidgetEventType.LEAVE -> 0xFFFFA726.toInt()
                else -> 0xFF3B4C9C.toInt()
            }
            views.setInt(R.id.item_dot, "setColorFilter", color)
            return views
        }

        override fun getLoadingView(): RemoteViews? = null
    }
}
