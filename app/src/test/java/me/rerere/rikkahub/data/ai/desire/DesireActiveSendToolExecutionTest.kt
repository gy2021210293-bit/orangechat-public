/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.service.canExecuteProactiveTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesireActiveSendToolExecutionTest {

    @Test
    fun testCanExecuteProactiveTool_withApprovalRequirements() {
        // 普通不需要审批的工具无论何种配置都允许执行
        assertTrue(
            canExecuteProactiveTool(
                needsApproval = false,
                allowAllToolsAndPlugins = false,
                globallyAutoApproved = false,
            )
        )

        // 需要审批的工具在未开启授权时自动拒绝
        assertFalse(
            canExecuteProactiveTool(
                needsApproval = true,
                allowAllToolsAndPlugins = false,
                globallyAutoApproved = false,
            )
        )

        // 开启 allowAllToolsAndPlugins 时，需要审批的工具允许执行
        assertTrue(
            canExecuteProactiveTool(
                needsApproval = true,
                allowAllToolsAndPlugins = true,
                globallyAutoApproved = false,
            )
        )

        // 开启 globallyAutoApproved 时，需要审批的工具允许执行
        assertTrue(
            canExecuteProactiveTool(
                needsApproval = true,
                allowAllToolsAndPlugins = false,
                globallyAutoApproved = true,
            )
        )
    }

    @Test
    fun testDesireActiveSend_toolAuthorizationResolution() {
        val defaultProactive = ProactiveMessageSetting(allowAllToolsAndPlugins = false)
        val authorizedProactive = ProactiveMessageSetting(allowAllToolsAndPlugins = true)

        // 1. 全局主动消息未允许全部工具 -> 主动联络受限
        val resolvedDefault = me.rerere.rikkahub.data.service.resolveProactiveAllowAllTools(
            isWorkflowWake = false,
            workflowExtra = false,
            proactiveSetting = defaultProactive,
        )
        assertFalse("全局主动消息未开启工具授权时，主动联络不得调用受限工具", resolvedDefault)

        // 2. 全局主动消息开启全部工具 -> 主动联络统一允许工具与插件
        val resolvedAuthorized = me.rerere.rikkahub.data.service.resolveProactiveAllowAllTools(
            isWorkflowWake = false,
            workflowExtra = false,
            proactiveSetting = authorizedProactive,
        )
        assertTrue("全局主动消息开启全部工具时，主动联络统一获得工具与插件权限", resolvedAuthorized)

        // 3. 工作流唤醒独立遵从自身配置
        val resolvedWorkflowWake = me.rerere.rikkahub.data.service.resolveProactiveAllowAllTools(
            isWorkflowWake = true,
            workflowExtra = true,
            proactiveSetting = defaultProactive,
        )
        assertTrue("工作流唤醒独立遵从自身显式工具配置", resolvedWorkflowWake)
    }

    @Test
    fun testDesireActiveSend_bypassGlobalProactiveDisabled() {
        fun shouldStopProactiveService(
            proactiveEnabled: Boolean,
            isFromDeviceEvent: Boolean,
            isWorkflowWake: Boolean,
            isDesireActive: Boolean,
        ): Boolean {
            return !proactiveEnabled && !isFromDeviceEvent && !isWorkflowWake && !isDesireActive
        }

        // 全局主动消息关闭时，普通主动消息必须停止
        assertTrue(
            shouldStopProactiveService(
                proactiveEnabled = false,
                isFromDeviceEvent = false,
                isWorkflowWake = false,
                isDesireActive = false,
            )
        )

        // 全局主动消息关闭时，Desire 主动联络绝不停止（独立运行）
        assertFalse(
            shouldStopProactiveService(
                proactiveEnabled = false,
                isFromDeviceEvent = false,
                isWorkflowWake = false,
                isDesireActive = true,
            )
        )

        // 全局主动消息开启时，Desire 正常运行
        assertFalse(
            shouldStopProactiveService(
                proactiveEnabled = true,
                isFromDeviceEvent = false,
                isWorkflowWake = false,
                isDesireActive = true,
            )
        )
    }
}
