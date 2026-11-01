package io.github.washingtongif.foldfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service after reboot; it then waits until Shizuku is running again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(ctx).startOnBoot) {
            FoldService.start(ctx)
        }
    }
}
