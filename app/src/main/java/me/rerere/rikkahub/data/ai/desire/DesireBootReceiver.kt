/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 专职接收受系统保护的系统级广播（开机、升级、时间与时区变化），
 * 重新计算并编排欲望心跳。不接受任何自定义 action。
 */
class DesireBootReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        private const val TAG = "DesireBootReceiver"
    }

    private val scheduler: DesireScheduler by inject()
    private val autonomousScheduler: AutonomousWakeScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "DesireBootReceiver onReceive action: $action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        scheduler.rescheduleAll()
                        autonomousScheduler.rescheduleAll()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error rescheduling all desire ticks", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
