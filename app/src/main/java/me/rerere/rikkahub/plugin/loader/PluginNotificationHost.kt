/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.atomic.AtomicInteger

/**
 * notification.show 的宿主实现。
 *
 * 插件只提供 title/text；Intent、通知渠道和通知 ID 全部由宿主控制，
 * 避免插件借通知接口构造任意 Intent/URI。
 */
internal object PluginNotificationHost {
    private const val OLD_CHANNEL_ID = "plugin_runtime"
    private const val CHANNEL_ID = "plugin_runtime_v2"
    private const val MAX_TITLE_LENGTH = 120
    private const val MAX_TEXT_LENGTH = 2_000
    private val notificationSequence = AtomicInteger(40_000)

    @Volatile
    private var channelInitialized = false

    private fun ensureChannel(context: Context) {
        if (channelInitialized) return
        synchronized(this) {
            if (channelInitialized) return
            val notificationManager = NotificationManagerCompat.from(context)
            try {
                notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID)
            } catch (_: Exception) {}

            notificationManager.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_HIGH,
                )
                    .setName("插件通知")
                    .setDescription("来自已启用插件的实时通知与提醒")
                    .setVibrationEnabled(true)
                    .setShowBadge(true)
                    .build()
            )
            channelInitialized = true
        }
    }

    fun show(
        context: Context,
        pluginName: String,
        title: String?,
        text: String,
    ): Boolean {
        val safeText = text.trim().take(MAX_TEXT_LENGTH)
        if (safeText.isBlank()) return false

        val safeTitle = title
            ?.trim()
            ?.take(MAX_TITLE_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: pluginName.take(MAX_TITLE_LENGTH)

        ensureChannel(context)

        val notificationId = notificationSequence.incrementAndGet()
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, RouteActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return context.sendNotification(
            channelId = CHANNEL_ID,
            notificationId = notificationId,
        ) {
            this.title = safeTitle
            content = safeText
            subText = pluginName
            autoCancel = true
            useBigTextStyle = true
            priority = androidx.core.app.NotificationCompat.PRIORITY_HIGH
            visibility = androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
            this.contentIntent = contentIntent
        }
    }
}
