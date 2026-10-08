/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.service.SchedulerDiagnostics
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.core.context.GlobalContext

/**
 * Manifest-declared receiver for BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT_POWERON / TIME_SET / TIMEZONE_CHANGED.
 * 处理开机触发的工作流，并在开机、系统升级、系统时间更改或时区变更时，自动重新同步并下发所有定时工作流的精确闹钟。
 *
 * 使用 [goAsync] 保护异步重调度协程，防止系统在操作未完成前杀死进程。
 */
class WorkflowBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        SchedulerDiagnostics.log("WorkflowBootReceiver", "Received system broadcast: action=$action")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    "android.intent.action.QUICKBOOT_POWERON" -> {
                        WorkflowBootDispatcher.onBoot()
                        rescheduleAllTimeCronWorkflows(context)
                    }
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        rescheduleAllTimeCronWorkflows(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun rescheduleAllTimeCronWorkflows(context: Context) {
        try {
            val repo = GlobalContext.get().getOrNull<WorkflowRepository>() ?: return
            val enabled = repo.listEnabled()
            val timeCronWorkflows = enabled.filter { it.definition.trigger is TriggerSpec.TimeCron }
            SchedulerDiagnostics.log(
                "WorkflowSchedule",
                "Rescheduling ${timeCronWorkflows.size} time_cron workflows after system event",
            )
            timeCronWorkflows.forEach { loaded ->
                TimeCronAlarmScheduler.reconcileNextOccurrence(context, loaded.definition.id)
            }
        } catch (e: Exception) {
            SchedulerDiagnostics.log(
                "WorkflowSchedule",
                "Failed to reschedule time_cron workflows after system event",
                e,
            )
        }
    }
}
