package com.amanxdev0.maidfree.clean

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Privileged cache cleaning through the Shizuku public API
 * (dev.rikka.shizuku, used here under its own open-source license —
 * no SD Maid code involved).
 *
 * How it works: with the Shizuku app running (ADB-level privileges),
 * we execute `pm trim-caches` as the shell user, which asks Android to
 * trim every app's cache at once. One tap, no per-app settings diving.
 *
 * The user must have the Shizuku app installed and started (wireless
 * debugging, one-time per boot), then grant MaidFree access when asked.
 */
object ShizukuCleaner {

    const val REQUEST_CODE = 1001
    private const val MANAGER_PKG = "moe.shizuku.privileged.api"

    /** True when the Shizuku manager app is installed. */
    fun isManagerInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(MANAGER_PKG, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True when the Shizuku binder is alive (manager running & reachable). */
    fun isBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /** True when the user already granted MaidFree Shizuku access. */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** Pops the Shizuku permission dialog (via the manager app). */
    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) { /* binder not ready */ }
    }

    /**
     * Clears all app caches via `pm trim-caches` as shell.
     * Returns (exitCode, combinedOutput). Must be called off the UI thread.
     */
    fun clearAllCaches(): Pair<Int, String> {
        return try {
            // Ask for ~1TB free: effectively trims every trimmable cache.
            val proc = Shizuku.newProcess(
                arrayOf("pm", "trim-caches", "999999999999"), null, null
            )
            val out = try {
                proc.inputStream.bufferedReader().readText()
            } catch (e: Exception) { "" }
            val err = try {
                proc.errorStream.bufferedReader().readText()
            } catch (e: Exception) { "" }
            val code = try {
                proc.waitFor()
            } catch (e: Exception) { -1 }
            code to (out + err).trim()
        } catch (e: Exception) {
            -1 to (e.message ?: "failed")
        }
    }
}
