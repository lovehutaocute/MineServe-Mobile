# -*- coding: utf-8 -*-
import re

path = r"d:\zm\AI\VSCode2\MCServerManager\app\src\main\java\com\mcserver\manager\ui\screens\PluginsScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

replacements = []
def r(old, new):
    replacements.append((old, new))

# === 1. Enum PluginTab ===
r(
    'private enum class PluginTab(val label: String) { Installed("\u5df2\u5b89\u88c5"), Upload("\u672c\u5730\u4e0a\u4f20") }',
    'private enum class PluginTab(val labelRes: Int) { Installed(R.string.s735), Upload(R.string.s736) }'
)

# === 2. Enum ResourceType ===
r(
    'private enum class ResourceType(val label: String) { Plugin("\u63d2\u4ef6"), Mod("\u6a21\u7ec4") }',
    'private enum class ResourceType(val labelRes: Int) { Plugin(R.string.s737), Mod(R.string.s738) }'
)

# === 3. PluginSite data class ===
r(
    'private data class PluginSite(val name: String, val desc: String, val url: String)',
    'private data class PluginSite(val name: String, val descRes: Int, val url: String)'
)

# === 4. pluginSites descriptions ===
r(
    '"\u8001\u724c\u5927\u578b\u63d2\u4ef6\u8d44\u6e90\u5e73\u53f0\uff0c\u7edd\u5927\u591a\u6570 Bukkit/Spigot \u63d2\u4ef6\u9996\u53d1\u7ad9\u70b9\uff0c\u542b\u514d\u8d39\u4e0e\u4ed8\u8d39\u8d44\u6e90\uff08\u5168\u82f1\u6587\u3001\u56fd\u5185\u8bbf\u95ee\u8f83\u6162\uff09"',
    'R.string.s739'
)
r(
    '"Paper \u5b98\u65b9\u642d\u5efa\u5e73\u53f0\uff0c\u9762\u5411 Paper / Velocity / Waterfall\uff0c\u754c\u9762\u7b80\u6d01\u3001\u7b5b\u9009\u5b8c\u5584"',
    'R.string.s741'
)
r(
    '"\u73b0\u4ee3\u5316\u5f00\u6e90\u8d44\u6e90\u5e73\u53f0\uff0c\u6536\u5f55\u63d2\u4ef6/\u6a21\u7ec4/\u6570\u636e\u5305\uff0c\u4e0b\u8f7d\u5feb\uff0c\u53ef\u6309\u6e38\u620f\u7248\u672c\u4e0e\u52a0\u8f7d\u5668\u7b5b\u9009"',
    'R.string.s742'
)
r(
    '"\u5546\u4e1a\u4ed8\u8d39\u63d2\u4ef6\u805a\u96c6\u5730\uff0c\u4e5f\u63d0\u4f9b\u514d\u8d39\u8d44\u6e90\u4e0e\u6210\u5957\u670d\u52a1\u7aef\u914d\u7f6e\u65b9\u6848"',
    'R.string.s744'
)
r(
    '"\u8001\u724c\u8d44\u6e90\u7ad9\u70b9\uff0c\u63d2\u4ef6\u603b\u6570\u7565\u5c11\u4e8e SpigotMC Resources"',
    'R.string.s746'
)

# === 5. Enum InstalledFilter ===
r(
    'private enum class InstalledFilter(val label: String) { All("\u5168\u90e8"), Enabled("\u542f\u7528"), Disabled("\u7981\u7528"), Local("\u672c\u5730") }',
    'private enum class InstalledFilter(val labelRes: Int) { All(R.string.s747), Enabled(R.string.s748), Disabled(R.string.s749), Local(R.string.s127) }'
)

# === 6. "刷新" -> stringResource(R.string.s333) ===
r(
    'Text(\n                        "\u5237\u65b0",\n                        color = Indigo,',
    'Text(\n                        stringResource(R.string.s333),\n                        color = Indigo,'
)

# === 7. "尚未选择服务端核心..." -> s754 ===
r(
    '"\u5c1a\u672a\u9009\u62e9\u670d\u52a1\u7aef\u6838\u5fc3\uff0c\u8bf7\u5728\u300c\u6982\u89c8\u300d\u9875\u9009\u7528"',
    'stringResource(R.string.s754)'
)

# === 8. "插件目录：$pluginsPath" -> s755 ===
r(
    '"\u63d2\u4ef6\u76ee\u5f55\uff1a$pluginsPath"',
    'stringResource(R.string.s755, pluginsPath)'
)

# === 9. "已安装 X 个插件" + "（X 个已禁用）" -> s756, s757 ===
r(
    '"\u5df2\u5b89\u88c5 ${installedPlugins.size} \u4e2a\u63d2\u4ef6" +\n                                if (installedPlugins.count { !it.isEnabled } > 0)\n                                    "\uff08${installedPlugins.count { !it.isEnabled }} \u4e2a\u5df2\u7981\u7528\uff09"\n                                else "",',
    'stringResource(R.string.s756, installedPlugins.size) +\n                                if (installedPlugins.count { !it.isEnabled } > 0)\n                                    stringResource(R.string.s757, installedPlugins.count { !it.isEnabled })\n                                else "",'
)

# === 10. "✓ 支持模组" / "✗ 不支持模组" -> s469, s470 ===
r(
    'if (coreType.supportsMods) "\u2713 \u652f\u6301\u6a21\u7ec4" else "\u2717 \u4e0d\u652f\u6301\u6a21\u7ec4",',
    'if (coreType.supportsMods) stringResource(R.string.s469) else stringResource(R.string.s470),'
)

# === 11. t.label -> stringResource(t.labelRes) (ResourceType call site) ===
r(
    'Text(\n                                t.label,\n                                color = if (resourceType == t) Color.White else Muted,',
    'Text(\n                                stringResource(t.labelRes),\n                                color = if (resourceType == t) Color.White else Muted,'
)

# === 12. tab.label -> stringResource(tab.labelRes) (PluginTab call site) ===
r(
    'val label = if (count > 0) "${tab.label} $count" else tab.label',
    'val label = if (count > 0) "${stringResource(tab.labelRes)} $count" else stringResource(tab.labelRes)'
)

# === 13. "服务器运行中..." / "服务器未运行..." -> s760, s761 ===
r(
    'if (isServerRunning)\n                        "\u670d\u52a1\u5668\u8fd0\u884c\u4e2d\uff0c\u53ef\u53d1\u9001 reload \u6307\u4ee4\u91cd\u65b0\u52a0\u8f7d\u6240\u6709\u63d2\u4ef6"\n                    else\n                        "\u670d\u52a1\u5668\u672a\u8fd0\u884c\uff0c\u70ed\u91cd\u8f7d\u6309\u94ae\u4e0d\u53ef\u7528\u3002\u8bf7\u5148\u5728\u300c\u6982\u89c8\u300d\u9875\u542f\u52a8\u670d\u52a1\u7aef",',
    'if (isServerRunning)\n                        stringResource(R.string.s760)\n                    else\n                        stringResource(R.string.s761),'
)

# === 14. "发送 reload 指令" / "服务器未运行" -> s763, s280 ===
r(
    'if (isServerRunning) "\u53d1\u9001 reload \u6307\u4ee4" else "\u670d\u52a1\u5668\u672a\u8fd0\u884c",',
    'if (isServerRunning) stringResource(R.string.s763) else stringResource(R.string.s280),'
)

# === 15. site.desc -> stringResource(site.descRes) (two occurrences) ===
r(
    'Text(\n                                site.desc,\n                                color = Muted,',
    'Text(\n                                stringResource(site.descRes),\n                                color = Muted,'
)
r(
    'Text(\n                        site.desc,\n                        color = Muted,',
    'Text(\n                        stringResource(site.descRes),\n                        color = Muted,'
)

# === 16. "即将删除模组..." -> s769 ===
r(
    '"\u5373\u5c06\u5220\u9664\u6a21\u7ec4\uff1a\\n${mod.baseName}\\n\\n\u6b64\u64cd\u4f5c\u4e0d\u53ef\u64a4\u9500\u3002"',
    'stringResource(R.string.s769, mod.baseName)'
)

# === 17. "$filter $count" -> stringResource(filter.labelRes) ===
r(
    'val label = "$filter $count"',
    'val label = "${stringResource(filter.labelRes)} $count"'
)

# === 18. "没有符合筛选条件的插件" -> s773 ===
r(
    'EmptyHint("\u6ca1\u6709\u7b26\u5408\u7b5b\u9009\u6761\u4ef6\u7684\u63d2\u4ef6")',
    'EmptyHint(stringResource(R.string.s773))'
)

# === 19. "  ·  已禁用" -> s774 ===
r(
    'if (!plugin.isEnabled) append("  \u00b7  \u5df2\u7981\u7528")',
    'if (!plugin.isEnabled) append(stringResource(R.string.s774))'
)

# === 20. contentDescription = "详情" -> s734 ===
r(
    'contentDescription = "\u8be6\u60c5", tint = Indigo, modifier = Modifier.size(14.dp))',
    'contentDescription = stringResource(R.string.s734), tint = Indigo, modifier = Modifier.size(14.dp))'
)

# === 21. "内置 8 款常用插件..." -> s778 ===
r(
    '"\u5185\u7f6e 8 \u6b3e\u5e38\u7528\u63d2\u4ef6\uff0c\u81ea\u52a8\u4ece GitHub Releases \u8ddf\u968f\u6700\u65b0\u7248\u672c\u4e0b\u8f7d"',
    'stringResource(R.string.s778)'
)

# === 22. "✓ 本核心支持插件" / "✗ 本核心不支持插件" -> s779, s780 ===
r(
    'if (coreType.supportsPlugins) "\u2713 \u672c\u6838\u5fc3\u652f\u6301\u63d2\u4ef6" else "\u2717 \u672c\u6838\u5fc3\u4e0d\u652f\u6301\u63d2\u4ef6",',
    'if (coreType.supportsPlugins) stringResource(R.string.s779) else stringResource(R.string.s780),'
)

# === 23. "下载中，请耐心等待..." -> s788 ===
r(
    '"\u4e0b\u8f7d\u4e2d\uff0c\u8bf7\u8010\u5fc3\u7b49\u5f85..."',
    'stringResource(R.string.s788)'
)

# === 24. "上传后会自动复制..." -> s794 ===
r(
    '"\u4e0a\u4f20\u540e\u4f1a\u81ea\u52a8\u590d\u5236\u5230\u5f53\u524d\u6838\u5fc3\u7684 plugins/ \u76ee\u5f55\uff0c\u9700\u8981 reload \u6216\u91cd\u542f\u670d\u52a1\u5668\u540e\u751f\u6548"',
    'stringResource(R.string.s794)'
)

# === 25. contentDescription = "上传" -> s497 ===
r(
    'contentDescription = "\u4e0a\u4f20", tint = Indigo, modifier = Modifier.size(36.dp)',
    'contentDescription = stringResource(R.string.s497), tint = Indigo, modifier = Modifier.size(36.dp)'
)

# === 26. "按下载量" / "按相关性" / "按最新" -> s825, s826, s827 ===
r(
    'val sortOptions = listOf("downloads" to "\u6309\u4e0b\u8f7d\u91cf", "relevance" to "\u6309\u76f8\u5173\u6027", "newest" to "\u6309\u6700\u65b0")',
    'val sortOptions = listOf("downloads" to stringResource(R.string.s825), "relevance" to stringResource(R.string.s826), "newest" to stringResource(R.string.s827))'
)

# === 27. "从 Modrinth 开放平台搜索并一键安装模组" -> s832 ===
r(
    '"\u4ece Modrinth \u5f00\u653e\u5e73\u53f0\u641c\u7d22\u5e76\u4e00\u952e\u5b89\u88c5\u6a21\u7ec4"',
    'stringResource(R.string.s832)'
)

# === Execute replacements ===
errors = []
for i, (old, new) in enumerate(replacements, 1):
    count = content.count(old)
    if count == 0:
        errors.append(f"REPLACEMENT {i}: NOT FOUND - {repr(old[:80])}")
    elif count > 1:
        errors.append(f"REPLACEMENT {i}: FOUND {count} TIMES (expected 1) - {repr(old[:80])}")
    else:
        content = content.replace(old, new)

if errors:
    print("ERRORS:")
    for e in errors:
        print(e)
    print(f"\nTotal: {len(errors)} errors out of {len(replacements)} replacements")
else:
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"SUCCESS: All {len(replacements)} replacements completed.")
