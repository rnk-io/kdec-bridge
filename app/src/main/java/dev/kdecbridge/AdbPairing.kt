package dev.kdecbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import tsbridge.Tsbridge

/**
 * One-time pairing of the app's ADB key with Wireless debugging.
 *
 * Settings shows the pairing code in a dialog, and on some phones closes it
 * as soon as Settings loses focus, even to the notification shade. The code
 * is entered in the app with both apps in split screen, or in a reply field
 * on a notification where the dialog stays open.
 */
object AdbPairing {

    const val KEY_CODE = "code"
    private const val CHANNEL_ID = "adb_pairing"
    private const val NOTIF_ID = 2
    private const val TIMEOUT_MS = 10 * 60_000L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "adb-pair") }

    /** Posts the notification that takes the pairing code. */
    fun showPrompt(ctx: Context) {
        notify(ctx, "Pair with Wireless debugging",
            "In Wireless debugging, tap Pair device with pairing code, then enter the code here.",
            input = true)
    }

    fun cancelPrompt(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /** Pairs with the code in the background. */
    fun submit(ctx: Context, code: String) {
        val app = ctx.applicationContext
        worker.execute { pair(app, code.trim()) }
    }

    private fun pair(ctx: Context, code: String) {
        notify(ctx, "Pairing…", "Keep the pairing code on screen.", input = false)
        val r = runCatching {
            require(code.length == 6 && code.all { it.isDigit() }) { "the pairing code has six digits" }
            val svc = AdbServices.find(ctx, AdbServices.PAIRING, 10_000)
                ?: error("pairing service not found - tap Pair device with pairing code first")
            AdbServices.onDevice(svc) { host, port ->
                Tsbridge.adbPair(AdbKeeper.keyDir(ctx), host, port.toLong(), code)
            }
        }
        r.onSuccess {
            Config(ctx).adbAuthorized = true
            BridgeService.log("adb: paired with Wireless debugging")
            notify(ctx, "Paired", "KDEC Bridge can now turn TCP ADB on by itself.",
                input = false, autoCancel = true)
            AdbKeeper.afterPairing(ctx)
        }.onFailure {
            BridgeService.log("adb: pairing failed - ${it.message}")
            notify(ctx, "Pairing failed", "${it.message}. Enter the code again.", input = true)
        }
    }

    private fun notify(ctx: Context, title: String, text: String, input: Boolean, autoCancel: Boolean = false) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ADB pairing", NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(autoCancel)
            .setTimeoutAfter(TIMEOUT_MS)
        if (input) {
            // RemoteInput requires a mutable PendingIntent; the intent is explicit.
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, PairingCodeReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val ri = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
            b.addAction(Notification.Action.Builder(null, "Enter code", pi).addRemoteInput(ri).build())
        }
        runCatching { nm.notify(NOTIF_ID, b.build()) }
    }
}

/**
 * Receives the pairing code typed into the notification. Pairing can take
 * longer than a receiver may run, so it continues on a worker thread; the
 * bridge's foreground service keeps the process alive.
 */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AdbPairing.KEY_CODE)?.toString() ?: return
        AdbPairing.submit(context, code)
    }
}
