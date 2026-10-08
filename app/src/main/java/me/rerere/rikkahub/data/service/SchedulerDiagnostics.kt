/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.util.Log
import me.rerere.common.android.Logging
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 定时任务与工作流全链路诊断日志记录器。
 *
 * 统一输出带有 [SchedulerDiag] 标识的 Logcat 日志，
 * 并沉淀到应用内内存日志列表 (可在 Logs 页面查看)，
 * 便于排查整夜息屏/深度 Doze 场景下的唤醒、插件加载、工作流执行与通知投递状态。
 *
 * 使用不可变且天然线程安全的 [DateTimeFormatter]。
 */
object SchedulerDiagnostics {
    private const val TAG = "SchedulerDiag"
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private fun formatTimestamp(epochMillis: Long): String {
        return runCatching { timeFormatter.format(Instant.ofEpochMilli(epochMillis)) }
            .getOrDefault(epochMillis.toString())
    }

    fun log(category: String, message: String, error: Throwable? = null) {
        val timestampStr = formatTimestamp(System.currentTimeMillis())
        val formattedMsg = "[$timestampStr] [$category] $message" + (if (error != null) " | Error: ${error.message}" else "")

        if (error != null) {
            Log.e(TAG, formattedMsg, error)
        } else {
            Log.i(TAG, formattedMsg)
        }

        // 记录到应用内内存日志列表，在 LogPage 中显示
        Logging.log(
            tag = "SchedulerDiag:$category",
            message = "$formattedMsg" + (if (error != null) "\n${error.stackTraceToString()}" else ""),
        )
    }

    fun logSchedule(target: String, triggerAtMillis: Long, exact: Boolean) {
        val timeStr = formatTimestamp(triggerAtMillis)
        val mode = if (exact) "ExactAlarm" else "InexactAlarm"
        log("Schedule", "Scheduled $target at $timeStr (mode=$mode, epoch=$triggerAtMillis)")
    }

    fun logAlarmReceived(target: String, scheduledAtMillis: Long) {
        val now = System.currentTimeMillis()
        val driftMs = if (scheduledAtMillis > 0) now - scheduledAtMillis else 0L
        log(
            "AlarmReceived",
            "Receiver received alarm for $target (scheduledAt=${if (scheduledAtMillis > 0) formatTimestamp(scheduledAtMillis) else "N/A"}, driftMs=${driftMs}ms)",
        )
    }

    fun logPluginInit(durationMs: Long, success: Boolean, details: String) {
        log(
            "PluginInit",
            "Plugin initialization ${if (success) "succeeded" else "failed/timed_out"} in ${durationMs}ms: $details",
        )
    }

    fun logHookExecution(pluginId: String, handler: String, result: String) {
        log("PluginHook", "Dispatched hook $pluginId.$handler: result=$result")
    }

    fun logWorkflowExecution(
        workflowId: String,
        workflowName: String,
        status: String,
        durationMs: Long,
        error: String? = null,
    ) {
        log(
            "WorkflowExec",
            "Workflow '$workflowName' ($workflowId) finished with status=$status in ${durationMs}ms" +
                (if (error != null) " (error: $error)" else ""),
        )
    }

    fun logAiWake(target: String, status: String, details: String) {
        log("AiWake", "AI wake for $target status=$status: $details")
    }

    fun logNotification(target: String, shown: Boolean, details: String) {
        log("Notification", "Notification for $target shown=$shown: $details")
    }
}
