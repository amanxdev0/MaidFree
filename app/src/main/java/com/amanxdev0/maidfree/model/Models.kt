package com.amanxdev0.maidfree.model

import android.graphics.drawable.Drawable

/** One cleanable thing found by a scan. */
data class CleanableItem(
    val path: String,        // absolute path (or packageName for app-cache items)
    val displayName: String,
    val size: Long,
    val category: String,    // "App cache", "Temp files", "Empty folders", "Thumbnails", "Corpse", "Duplicate", "Large file"
    val detail: String = "",
    var checked: Boolean = true
)

/** Installed app with storage stats. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val cacheBytes: Long,
    val dataBytes: Long,
    val isSystem: Boolean
)
