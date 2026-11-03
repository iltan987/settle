package com.iltan.settle.data.backup

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_DEFAULT
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW
import com.iltan.settle.MainActivity
import com.iltan.settle.R
import com.iltan.settle.domain.backup.BackupPause

/**
 * Backup's notifications (ADR-014). Automatic backup posts none: it runs on the charger and is
 * meant to go unnoticed. A backup the user started shows one silent progress notification, which
 * Android requires for that kind of job. Problems that need the user get a notification of their
 * own.
 */
class BackupNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                channel(PROGRESS, R.string.notification_channel_progress, IMPORTANCE_LOW),
                channel(PROBLEMS, R.string.notification_channel_problems, IMPORTANCE_DEFAULT),
            )
        )
    }

    private fun channel(id: String, name: Int, importance: Int) =
        NotificationChannelCompat.Builder(id, importance).setName(context.getString(name)).build()

    /** The progress notification of a backup the user started. */
    fun progress(items: Int, sentBytes: Long, totalBytes: Long): Notification {
        val percent = if (totalBytes > 0) (sentBytes * 100 / totalBytes).toInt() else 0
        return NotificationCompat.Builder(context, PROGRESS)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.notification_backing_up, items, items)
            )
            .setContentText(
                context.getString(
                    R.string.notification_progress,
                    Formatter.formatShortFileSize(context, sentBytes),
                    Formatter.formatShortFileSize(context, totalBytes),
                )
            )
            .setProgress(100, percent, totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(openSettings = false, reconnect = false))
            .addAction(
                0,
                context.getString(R.string.notification_pause),
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, BackupActionReceiver::class.java)
                        .setAction(BackupActionReceiver.PAUSE),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    /** The progress notification of a restore the user started (ADR-015). */
    fun restoreProgress(items: Int, receivedBytes: Long, totalBytes: Long): Notification {
        val percent = if (totalBytes > 0) (receivedBytes * 100 / totalBytes).toInt() else 0
        return NotificationCompat.Builder(context, PROGRESS)
            .setSmallIcon(R.drawable.ic_cloud_download)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.notification_restoring, items, items)
            )
            .setContentText(
                context.getString(
                    R.string.notification_progress,
                    Formatter.formatShortFileSize(context, receivedBytes),
                    Formatter.formatShortFileSize(context, totalBytes),
                )
            )
            .setProgress(100, percent, totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(openSettings = false, reconnect = false))
            .build()
    }

    /** Tells the user why backup stopped, if it needs them. */
    fun showPause(reason: BackupPause) {
        val (title, text) =
            when (reason) {
                BackupPause.AUTH_NEEDED ->
                    R.string.notification_reconnect_title to R.string.notification_reconnect_text
                BackupPause.QUOTA_FULL ->
                    R.string.notification_drive_full_title to R.string.notification_drive_full_text
                else -> return
            }
        notify(
            PROBLEM_ID,
            NotificationCompat.Builder(context, PROBLEMS)
                .setSmallIcon(R.drawable.ic_cloud_off)
                .setContentTitle(context.getString(title))
                .setContentText(context.getString(text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
                .setContentIntent(
                    openApp(openSettings = true, reconnect = reason == BackupPause.AUTH_NEEDED)
                )
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearPause() = manager.cancel(PROBLEM_ID)

    private fun notify(id: Int, notification: Notification) {
        if (
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(id, notification)
        }
    }

    private fun openApp(openSettings: Boolean, reconnect: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            (if (openSettings) 1 else 0) + (if (reconnect) 2 else 0),
            Intent()
                .setClassName(context, MainActivity.NOTIFICATION_TARGET)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, openSettings)
                .putExtra(MainActivity.EXTRA_RECONNECT, reconnect),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val PROGRESS = "backup_progress"
        private const val PROBLEMS = "backup_problems"
        const val PROGRESS_ID = 1
        const val RESTORE_ID = 3
        private const val PROBLEM_ID = 2
    }
}
