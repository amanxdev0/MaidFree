package com.amanxdev0.maidfree.util

import android.content.Context

/** User-excluded paths that scanners must skip. Matches exact paths or anything beneath them. */
object Exclusions {
    private const val KEY = "exclusions"

    fun get(ctx: Context): Set<String> =
        ctx.getSharedPreferences("maidfree", Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()

    fun add(ctx: Context, path: String) {
        val prefs = ctx.getSharedPreferences("maidfree", Context.MODE_PRIVATE)
        val next = get(ctx).toMutableSet().apply { add(path) }
        prefs.edit().putStringSet(KEY, next).apply()
    }

    fun remove(ctx: Context, path: String) {
        val prefs = ctx.getSharedPreferences("maidfree", Context.MODE_PRIVATE)
        val next = get(ctx).toMutableSet().apply { remove(path) }
        prefs.edit().putStringSet(KEY, next).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("maidfree", Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun isExcluded(path: String, excluded: Set<String>): Boolean {
        if (excluded.isEmpty()) return false
        for (e in excluded) {
            if (path == e || path.startsWith(e.trimEnd('/') + "/")) return true
        }
        return false
    }
}
