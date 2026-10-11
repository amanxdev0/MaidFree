package com.amanxdev0.maidfree.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var u = -1
    while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
    return String.format(Locale.US, "%.1f %s", v, units[u])
}
