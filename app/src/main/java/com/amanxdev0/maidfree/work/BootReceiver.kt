package com.amanxdev0.maidfree.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-schedules the daily scan after a reboot, if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val on = ctx.getSharedPreferences("sweep", Context.MODE_PRIVATE)
                .getBoolean("scheduler", false)
            if (on) {
                AlarmReceiver.createChannel(ctx)
                AlarmReceiver.schedule(ctx)
            }
        }
    }
}
