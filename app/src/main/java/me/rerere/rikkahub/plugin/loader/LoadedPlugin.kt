/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.plugin.model.PluginInfo

/**
 * 已加载的插件
 */
data class LoadedPlugin(
    /**
     * 插件信息
     */
    val info: PluginInfo,
    
    /**
     * JS沙箱
     */
    val sandbox: PluginSandbox,

    /** 每个 QuickJS 沙箱固定在自己的串行线程，慢插件不阻塞其他插件回调。 */
    val dispatcher: ExecutorCoroutineDispatcher,

    /** 每个已加载插件专属的协程作用域，用于承载异步动作与延时任务；卸载时统一 cancel()。 */
    val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * 获取插件ID
     */
    val id: String get() = info.manifest.id
    
    /**
     * 获取插件名称
     */
    val name: String get() = info.manifest.name
    
    /**
     * 检查是否有某个工具
     */
    fun hasTool(toolName: String): Boolean {
        return info.manifest.tools.any { it.name == toolName }
    }
    
    /**
     * 获取工具定义
     */
    fun getTool(toolName: String) = info.manifest.tools.find { it.name == toolName }
}
