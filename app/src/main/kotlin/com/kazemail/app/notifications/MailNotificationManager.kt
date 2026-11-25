package com.kazemail.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kazemail.app.MainActivity
import com.kazemail.app.R
import com.kazemail.app.data.db.MessageMetaEntity

class MailNotificationManager(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.new_mail_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.new_mail_channel_desc)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notifyNewMessages(accountId: Long, newUnreadMessages: List<MessageMetaEntity>) {
        if (newUnreadMessages.isEmpty()) return
        if (!hasNotificationPermission()) return
        ensureChannel()

        val sorted = newUnreadMessages.sortedByDescending { it.receivedAt }
        val latest = sorted.first()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAV_ACCOUNT_ID, accountId)
            if (sorted.size == 1) {
                putExtra(EXTRA_NAV_META_ID, latest.id)
                putExtra(EXTRA_NAV_THREAD_ID, latest.threadId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            accountId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kazemail)
            .setColor(NOTIFICATION_ACCENT_COLOR)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (sorted.size == 1) {
            val senderLabel = latest.senderName?.takeIf { it.isNotBlank() } ?: latest.senderAddress
            val subjectLabel = latest.subject?.takeIf { it.isNotBlank() } ?: "(件名なし)"
            builder
                .setContentTitle(senderLabel)
                .setContentText(subjectLabel)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(buildString {
                            append(subjectLabel)
                            if (!latest.snippet.isNullOrBlank()) {
                                append('\n')
                                append(latest.snippet)
                            }
                        }),
                )
        } else {
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle("新着メール ${sorted.size} 件")
            sorted.take(5).forEach { msg ->
                val sender = msg.senderName?.takeIf { it.isNotBlank() } ?: msg.senderAddress
                val subj = msg.subject?.takeIf { it.isNotBlank() } ?: "(件名なし)"
                inboxStyle.addLine("$sender: $subj")
            }
            if (sorted.size > 5) {
                inboxStyle.setSummaryText("他 ${sorted.size - 5} 件")
            }
            builder
                .setContentTitle("新着メール ${sorted.size} 件")
                .setContentText(latest.subject?.takeIf { it.isNotBlank() } ?: latest.senderAddress)
                .setStyle(inboxStyle)
                .setNumber(sorted.size)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(
                BASE_NOTIFICATION_ID + accountId.toInt(),
                builder.build(),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "kazemail_new_mail_channel"
        private const val BASE_NOTIFICATION_ID = 2000
        private const val NOTIFICATION_ACCENT_COLOR = 0xFF0B556B.toInt()
        const val EXTRA_NAV_ACCOUNT_ID = "kazemail_nav_account_id"
        const val EXTRA_NAV_META_ID = "kazemail_nav_meta_id"
        const val EXTRA_NAV_THREAD_ID = "kazemail_nav_thread_id"
    }
}
