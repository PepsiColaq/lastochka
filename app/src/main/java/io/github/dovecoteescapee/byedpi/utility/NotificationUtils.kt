package io.github.dovecoteescapee.byedpi.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.PAUSE_ACTION
import io.github.dovecoteescapee.byedpi.data.RESUME_ACTION
import io.github.dovecoteescapee.byedpi.data.STOP_ACTION

fun registerNotificationChannel(context: Context, id: String, @StringRes name: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            id,
            context.getString(name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setShowBadge(false)

        manager.createNotificationChannel(channel)
    }
}

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    @StringRes content: Int,
    service: Class<*>,
    paused: Boolean = false,
): Notification {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentTitle(context.getString(title))
        .setContentText(context.getString(content))
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                flags,
            ),
        )

    if (paused) {
        builder.addAction(
            0,
            context.getString(R.string.notification_resume),
            PendingIntent.getService(
                context,
                1,
                Intent(context, service).setAction(RESUME_ACTION),
                flags,
            ),
        )
    } else {
        builder.addAction(
            0,
            context.getString(R.string.notification_pause),
            PendingIntent.getService(
                context,
                2,
                Intent(context, service).setAction(PAUSE_ACTION),
                flags,
            ),
        )
    }
    builder.addAction(
        0,
        context.getString(R.string.notification_stop),
        PendingIntent.getService(
            context,
            3,
            Intent(context, service).setAction(STOP_ACTION),
            flags,
        ),
    )
    return builder.build()
}
