package com.amanxdev0.maidfree.work

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.amanxdev0.maidfree.R
import com.amanxdev0.maidfree.scan.Scanner
import com.amanxdev0.maidfree.ui.MainActivity
import com.amanxdev0.maidfree.util.formatBytes

/** Daily background scan — notifies when junk piles up. Scheduled via AlarmManager. */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL = "sweep_daily"
        private const val REQ = 9001

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL, "Daily scan",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(ch)
            }
        }

        private fun pending(ctx: Context): PendingIntent {
            val i = Intent(ctx, AlarmReceiver::class.java)
            return PendingIntent.getBroadcast(
                ctx, REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + AlarmManager.INTERVAL_DAY,
                AlarmManager.INTERVAL_DAY,
                pending(ctx)
            )
        }

        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx))
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                Scanner.cancelRequested = false
                val junk = Scanner.scanJunk(ctx) {}
                val total = junk.filter { it.category != "App cache" }.sumOf { it.size }
                if (total > 50L * 1024 * 1024) notifyFound(ctx, total)
            } catch (e: Exception) {
                // quiet: will retry tomorrow
            } finally {
                done.finish()
            }
        }.start()
    }

    private fun notifyFound(ctx: Context, bytes: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🧹 ${formatBytes(bytes)} of junk found")
            .setContentText("Tap to clean it up with Sweep.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1001, n)
        } catch (e: SecurityException) { /* no notif permission */ }
    }
}
