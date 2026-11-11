package dev.kdecbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Notifications for problems that only the user can fix, such as allowing
 * the app's ADB key again. They use their own channel, so they stay visible
 * when the status notification is turned off.
 */
object Attention {

    /** TCP ADB needs the user. Tapping runs "Turn on TCP ADB". */
    const val ADB = 3
    /** The bridge or its tailnet node needs the user. Tapping opens the app. */
    const val BRIDGE = 4

    /** Intent extra that tells [MainActivity] what the notification was about. */
    const val EXTRA_ACTION = "attention"
    const val ACTION_ADB_ON = "adb_on"

    private const val CHANNEL_ID = "attention"

    fun show(ctx: Context, id: Int, title: String, text: String, action: String? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Action needed", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) open.putExtra(EXTRA_ACTION, action)
        val pi = PendingIntent.getActivity(
            ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    fun cancel(ctx: Context, id: Int) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(id) }
    }
}
