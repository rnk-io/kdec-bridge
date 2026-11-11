package dev.kdecbridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Restarts the bridge if Android ended it without a request.
 *
 * A repeating alarm runs [WatchdogReceiver] about every 15 minutes while the
 * bridge is meant to be running. The alarm does not wake the phone: it is
 * delivered the next time the phone is awake.
 */
object Watchdog {

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, intent(ctx))
        }
    }

    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(intent(ctx)) }
    }

    /** Whether the bridge should be running but is not. */
    fun bridgeIsDown(cfg: Config): Boolean =
        cfg.enabled && !cfg.cleanStop && cfg.host.isNotBlank() && !BridgeService.isRunning
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val cfg = Config(ctx)
        if (!cfg.enabled || cfg.cleanStop) {
            Watchdog.cancel(ctx)
            return
        }
        if (!Watchdog.bridgeIsDown(cfg)) return
        EventLog.init(ctx)
        EventLog.add("watchdog: bridge is not running - last alive " +
            EventLog.clockOf(cfg.lastHeartbeat) + ", restarting")
        // Android allows a background start only for apps exempt from
        // battery optimization.
        runCatching { BridgeService.start(ctx) }.onFailure {
            EventLog.add("watchdog: Android refused the restart - ${it.message}")
            Attention.show(ctx, Attention.BRIDGE, "KDEC Bridge stopped",
                "Android stopped the bridge. Tap to start it again.")
        }
    }
}
