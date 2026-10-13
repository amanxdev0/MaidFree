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
        val prefs = ctx.getSharedPreferences("maidfree", Context.MODE_PRIVATE)
        // If the user opted into scheduled auto-clean and the accessibility
        // service is live, clear caches silently instead of just notifying.
        if (prefs.getBoolean("autoClean", false) &&
            com.amanxdev0.maidfree.clean.AutoCleanService.isEnabled(ctx)
        ) {
            Thread {
                try {
                    val pkgs = Scanner.scanAppCaches(ctx).map { it.path }
                    val svc = com.amanxdev0.maidfree.clean.AutoCleanService.instance
                    if (svc != null && pkgs.isNotEmpty()) {
                        com.amanxdev0.maidfree.clean.AutoCleanService.onProgress =
                            { _, _, _, finished ->
                                if (finished) {
                                    notifyDone(ctx)
                                    com.amanxdev0.maidfree.clean.AutoCleanService.onProgress = null
                                }
                            }
                        svc.begin(pkgs)
                    } else {
                        scanAndNotify(ctx)
                    }
                } catch (e: Exception) {
                    // quiet: will retry tomorrow
                } finally {
                    done.finish()
                }
            }.start()
        } else {
            Thread {
                try {
                    scanAndNotify(ctx)
                } catch (e: Exception) {
                    // quiet: will retry tomorrow
                } finally {
                    done.finish()
                }
            }.start()
        }
    }

    private fun scanAndNotify(ctx: Context) {
        Scanner.cancelRequested = false
        val junk = Scanner.scanJunk(ctx) {}
        val total = junk.filter { it.category != "App cache" }.sumOf { it.size }
        if (total > 50L * 1024 * 1024) notifyFound(ctx, total)
    }

    private fun notifyDone(ctx: Context) {
        postNotification(
            ctx, "Auto-clean finished",
            "App caches were cleared automatically."
        )
    }

    private fun notifyFound(ctx: Context, bytes: Long) {
        postNotification(
            ctx,
            "${formatBytes(bytes)} of junk found",
            "Tap to clean it up with MaidFree."
        )
    }

    private fun postNotification(ctx: Context, title: String, text: String) {
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
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1001, n)
        } catch (e: SecurityException) { /* no notif permission */ }
    }
}
