package com.mineserve.mobile.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PluginManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/** 模组与插件组件的列表数据源（含 jar 内图标提取与缓存）。 */
class ModPluginWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = ModPluginFactory(applicationContext)

    class ModPluginFactory(private val context: Context) : RemoteViewsFactory {

        private data class Row(val title: String, val subtitle: String, val icon: Bitmap?, val initial: String)

        private var rows: List<Row> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            val app = McApplication.get(context)
            val pluginManager = PluginManager(app.termuxRuntime, app)
            val config = runCatching { kotlinx.coroutines.runBlocking { app.repository.configFlow.first() } }.getOrNull()
            val core = config?.installedCores?.find { it.name == config.activeCoreName }
            val dirName = core?.dirName
            if (dirName == null) {
                rows = emptyList()
                return
            }
            val root = app.termuxRuntime.installer.rootDir
            val modsDir = File(root, "home/servers/$dirName/mods")
            val pluginsDir = File(root, "home/servers/$dirName/plugins")

            val list = mutableListOf<Row>()
            val mods = runCatching { pluginManager.readMods(dirName) }.getOrDefault(emptyList())
            if (mods.isNotEmpty()) {
                list += Row(
                    context.getString(R.string.widget_mod_label),
                    context.getString(R.string.widget_mod_count, mods.size), null, ""
                )
                mods.forEach { mod ->
                    list += Row(
                        mod.baseName,
                        statusText(mod.isEnabled),
                        WidgetIconCache.modIcon(File(modsDir, mod.fileName)),
                        mod.baseName.take(1).uppercase()
                    )
                }
            }
            val plugins = runCatching {
                kotlinx.coroutines.runBlocking { pluginManager.scan(dirName) }
            }.getOrDefault(emptyList())
            if (plugins.isNotEmpty()) {
                list += Row(
                    context.getString(R.string.widget_plugin_label),
                    context.getString(R.string.widget_plugin_count, plugins.size), null, ""
                )
                plugins.forEach { plugin ->
                    list += Row(
                        plugin.meta?.name ?: plugin.baseName,
                        statusText(plugin.isEnabled),
                        WidgetIconCache.pluginIcon(File(pluginsDir, plugin.fileName)),
                        (plugin.meta?.name ?: plugin.baseName).take(1).uppercase()
                    )
                }
            }
            rows = list
        }

        private fun statusText(enabled: Boolean): String =
            context.getString(if (enabled) R.string.widget_status_enabled else R.string.widget_status_disabled)

        override fun onDestroy() {}
        override fun getCount(): Int = rows.size
        override fun getViewTypeCount(): Int = 1
        override fun hasStableIds(): Boolean = false
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewAt(position: Int): RemoteViews {
            val row = rows[position]
            val views = RemoteViews(context.packageName, R.layout.widget_mod_plugin_item)
            views.setTextViewText(R.id.item_title, row.title)
            views.setTextViewText(R.id.item_subtitle, row.subtitle)
            if (row.icon != null) {
                views.setImageViewBitmap(R.id.item_icon, row.icon)
                views.setViewVisibility(R.id.item_badge, android.view.View.INVISIBLE)
            } else {
                views.setViewVisibility(R.id.item_icon, android.view.View.INVISIBLE)
                views.setViewVisibility(R.id.item_badge, android.view.View.VISIBLE)
                views.setTextViewText(R.id.item_badge, row.initial)
            }
            return views
        }

        override fun getLoadingView(): RemoteViews? = null
    }
}

/** jar 内图标 → Bitmap；按 (绝对路径+lastModified+entry) 缓存，失败用哨兵占位避免重复解压。 */
object WidgetIconCache {
    // ConcurrentHashMap 不接受 null key/value；解码失败用哨兵占位表示“已尝试过”。
    private val cache = ConcurrentHashMap<String, Any>()
    private val MISS = Any()

    fun pluginIcon(jar: File): Bitmap? = jarIcon(jar, listOf("icon.png"))

    /** 模组图标：fabric.mod.json 的 icon（字符串或 {src}），回退常见固定路径。 */
    fun modIcon(jar: File): Bitmap? {
        val fabricIcon = runCatching {
            JarFile(jar).use { jf ->
                val entry = jf.getEntry("fabric.mod.json") ?: return@use null
                val obj = Json.parseToJsonElement(
                    jf.getInputStream(entry).bufferedReader().readText()
                ).jsonObject
                obj["icon"]?.let { el ->
                    when {
                        el.toString().startsWith("\"") -> el.toString().trim('"')
                        else -> runCatching { el.jsonObject["src"]?.toString()?.trim('"') }.getOrNull()
                    }
                }
            }
        }.getOrNull()
        val candidates = buildList {
            fabricIcon?.takeIf { it.isNotBlank() }?.let { add(it.trimStart('/')) }
            add("icon.png")
            add("assets/icon.png")
        }.distinct()
        return jarIcon(jar, candidates)
    }

    private fun jarIcon(jar: File, entryNames: List<String>): Bitmap? {
        val key = "${jar.absolutePath}:${jar.lastModified()}:${entryNames.firstOrNull() ?: ""}"
        val cached = cache[key]
        if (cached !== null) return cached as? Bitmap
        val bitmap = runCatching {
            JarFile(jar).use { jf ->
                entryNames.firstNotNullOfOrNull { name ->
                    jf.getEntry(name)?.let { entry ->
                        jf.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                    }
                }
            }
        }.getOrNull()
        cache[key] = bitmap ?: MISS
        return bitmap
    }
}
