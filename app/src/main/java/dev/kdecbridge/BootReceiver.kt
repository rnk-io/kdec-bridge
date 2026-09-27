package dev.kdecbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the bridge after a reboot or an app update if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Config(context).enabled) BridgeService.start(context)
    }
}
