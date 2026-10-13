package com.amanxdev0.maidfree.clean

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Automatically clears per-app caches by driving the system app-settings UI:
 * opens each app's settings page, taps "Clear cache", goes back, repeats.
 *
 * It NEVER touches "Clear data" / "Clear storage" — the matcher explicitly
 * refuses any node whose text mentions data deletion.
 *
 * Written from scratch for MaidFree (no SD Maid code); the *technique* of
 * driving settings via accessibility is the same one classic cleaners use.
 */
class AutoCleanService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoCleanService? = null
            private set

        @Volatile private var queue = ArrayDeque<String>()
        @Volatile var running = false
            private set
        @Volatile var processed = 0
            private set
        @Volatile var total = 0
            private set
        @Volatile var cleared = 0
            private set

        /** (processed, total, currentPackage, done) — invoked on the service thread; post to UI yourself. */
        var onProgress: ((Int, Int, String, Boolean) -> Unit)? = null

        /** True when the user has enabled this service in system Accessibility settings. */
        fun isEnabled(ctx: android.content.Context): Boolean {
            val expected = ComponentName(ctx, AutoCleanService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var taskToken = 0
    private var currentPkg = ""
    private var attempts = 0

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        running = false
        taskToken++ // invalidate pending runnables
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Driven by timed inspections instead of reacting to every event;
        // this keeps the state machine predictable across OEM skins.
    }

    override fun onInterrupt() {
        running = false
        taskToken++
    }

    /** Start clearing caches for [packages] (package names). Ignored if already running. */
    fun begin(packages: List<String>) {
        if (running || packages.isEmpty()) return
        queue = ArrayDeque(packages)
        total = packages.size
        processed = 0
        cleared = 0
        running = true
        nextPackage()
    }

    fun stop() {
        running = false
        taskToken++
        queue.clear()
    }

    // ---------- state machine ----------

    private fun nextPackage() {
        taskToken++
        val pkg = queue.removeFirstOrNull()
        if (pkg == null) {
            running = false
            onProgress?.invoke(total, total, "", true)
            return
        }
        currentPkg = pkg
        attempts = 0
        try {
            val i = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            finishPackage()
            return
        }
        val token = taskToken
        handler.postDelayed({ if (token == taskToken && running) inspect() }, 1500)
    }

    /** Look at the current settings screen and decide the next action. */
    private fun inspect() {
        if (!running) return
        val root = rootInActiveWindow
        if (root == null) {
            retryOrSkip()
            return
        }
        // 1. Prefer a visible "Clear cache" button.
        val clearNode = findClickable(root) { t ->
            t.contains("clear cache") && !t.contains("clear data") && !t.contains("clear storage")
        }
        if (clearNode != null) {
            // Double-check the node and its parents don't mention data deletion.
            if (!mentionsDataDeletion(clearNode)) {
                clearNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val token = taskToken
                handler.postDelayed({
                    if (token == taskToken && running) {
                        cleared++
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        val t2 = taskToken
                        handler.postDelayed({
                            if (t2 == taskToken && running) finishPackage()
                        }, 900)
                    }
                }, 1200)
                return
            }
        }
        // 2. Otherwise drill into a "Storage" row (AOSP-style settings).
        if (attempts < 2) {
            attempts++
            val storageNode = findClickable(root) { t ->
                t.contains("storage") && !t.contains("clear")
            }
            if (storageNode != null) {
                storageNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val token = taskToken
                handler.postDelayed({ if (token == taskToken && running) inspect() }, 1500)
                return
            }
        }
        retryOrSkip()
    }

    private fun retryOrSkip() {
        if (attempts < 3) {
            attempts++
            val token = taskToken
            handler.postDelayed({ if (token == taskToken && running) inspect() }, 1200)
        } else {
            // Couldn't drive this app's settings — back out and move on.
            performGlobalAction(GLOBAL_ACTION_BACK)
            val token = taskToken
            handler.postDelayed({ if (token == taskToken && running) finishPackage() }, 700)
        }
    }

    private fun finishPackage() {
        processed++
        val done = processed >= total && queue.isEmpty()
        onProgress?.invoke(processed, total, currentPkg, done)
        if (done) {
            running = false
        } else {
            nextPackage()
        }
    }

    // ---------- node helpers ----------

    /** Depth-first search for a clickable node whose text matches [predicate]. */
    private fun findClickable(
        root: AccessibilityNodeInfo,
        predicate: (String) -> Boolean
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val text = buildString {
                append(n.text?.toString() ?: "")
                append(' ')
                append(n.contentDescription?.toString() ?: "")
            }.lowercase()
            if (predicate(text)) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null) return c
            }
            for (i in 0 until n.childCount) {
                try {
                    n.getChild(i)?.let { stack.add(it) }
                } catch (e: Exception) { /* ignore */ }
            }
        }
        return null
    }

    /** Refuse anything whose own or nearby text mentions wiping app data. */
    private fun mentionsDataDeletion(node: AccessibilityNodeInfo): Boolean {
        val sb = StringBuilder()
        sb.append(node.text?.toString() ?: "").append(' ')
        sb.append(node.contentDescription?.toString() ?: "").append(' ')
        var p: AccessibilityNodeInfo? = try { node.parent } catch (e: Exception) { null }
        repeat(2) {
            val cur = p ?: return@repeat
            sb.append(cur.text?.toString() ?: "").append(' ')
            p = try { cur.parent } catch (e: Exception) { null }
        }
        val t = sb.toString().lowercase()
        return t.contains("clear data") || t.contains("clear storage") || t.contains("delete data")
    }
}
