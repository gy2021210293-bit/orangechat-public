/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.model

sealed interface PluginQuickEntryTarget {
    data object DeclarativeUi : PluginQuickEntryTarget
    data class WebView(val entryPath: String) : PluginQuickEntryTarget
    data object MemoryBank : PluginQuickEntryTarget
}

data class PluginQuickEntry(
    val pluginId: String,
    val pluginName: String,
    val icon: String = "",
    val isEnabled: Boolean,
    val target: PluginQuickEntryTarget,
)

fun PluginInfo.hasSupportedQuickEntryUi(): Boolean =
    manifest.ui != null ||
        manifest.customPageWebView != null ||
        manifest.customPage == "memory_bank"

fun PluginInfo.toQuickEntryOrNull(): PluginQuickEntry? {
    if (!showInQuickEntry || loadError != null) return null

    val target = when {
        manifest.ui != null -> PluginQuickEntryTarget.DeclarativeUi
        manifest.customPageWebView != null -> {
            PluginQuickEntryTarget.WebView(manifest.customPageWebView.entry)
        }
        manifest.customPage == "memory_bank" -> PluginQuickEntryTarget.MemoryBank
        else -> return null
    }
    return PluginQuickEntry(
        pluginId = manifest.id,
        pluginName = manifest.name,
        icon = manifest.icon,
        isEnabled = isEnabled,
        target = target,
    )
}
