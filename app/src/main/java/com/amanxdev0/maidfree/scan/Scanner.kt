package com.amanxdev0.maidfree.scan

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.amanxdev0.maidfree.model.AppInfo
import com.amanxdev0.maidfree.model.CleanableItem
import com.amanxdev0.maidfree.util.Perms
import java.io.File
import java.security.MessageDigest

/**
 * Blocking file/app scanners. Call from a background thread; progress
 * callbacks are invoked on that same thread (wrap in runOnUiThread).
 */
object Scanner {

    /** Set true to abort an in-progress scan. Reset to false before starting. */
    @Volatile var cancelRequested = false

    private val TEMP_EXT = setOf("tmp", "temp", "log", "bak", "swp", "chk", "old")
    private const val SELF_PKG = "com.amanxdev0.maidfree"

    class CancelledException : RuntimeException()

    private fun checkCancel() {
        if (cancelRequested) throw CancelledException()
    }

    // ---------- shared helpers ----------

    private fun sharedRoot(): File =
        Environment.getExternalStorageDirectory() ?: File("/sdcard")

    /** Walk shared storage, skipping symlinks and our own app dir. Reports progress. */
    private fun walkShared(
        onFile: (File) -> Unit,
        onProgress: (scanned: Int) -> Unit
    ) {
        var count = 0
        val root = sharedRoot()
        if (!root.exists()) return
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            checkCancel()
            val dir = stack.removeLast()
            val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (k in kids) {
                checkCancel()
                try {
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue
                } catch (e: Exception) { /* ignore */ }
                if (k.isDirectory) {
                    if (k.name == SELF_PKG) continue
                    stack.add(k)
                } else {
                    count++
                    if (count % 500 == 0) onProgress(count)
                    onFile(k)
                }
            }
        }
        onProgress(count)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(8192).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- 1. Junk ----------

    fun scanJunk(ctx: Context, onProgress: (Int) -> Unit): List<CleanableItem> {
        val out = mutableListOf<CleanableItem>()
        // 1a. App caches (needs usage access)
        out += scanAppCaches(ctx)
        // 1b. Temp / log / backup files
        walkShared(onFile = { f ->
            val ext = f.extension.lowercase()
            val name = f.name.lowercase()
            if (ext in TEMP_EXT || name.endsWith("~")) {
                out += CleanableItem(
                    f.absolutePath, f.name, f.length(),
                    "Temp files", "Leftover temporary file"
                )
            }
        }, onProgress = onProgress)
        // 1c. Strictly-empty directories only (kids.isEmpty()).
        // Never flag a dir that merely contains subdirs — it may hold real data.
        run {
            val root = sharedRoot()
            val stack = ArrayDeque<File>().apply { add(root) }
            while (stack.isNotEmpty()) {
                checkCancel()
                val dir = stack.removeLast()
                val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                for (k in kids) if (k.isDirectory) stack.add(k)
                if (kids.isEmpty() && dir != root) {
                    out += CleanableItem(
                        dir.absolutePath, dir.name, 0L,
                        "Empty folders", "Empty directory"
                    )
                }
            }
        }
        // 1d. Thumbnail caches
        run {
            val thumbs = File(sharedRoot(), "DCIM/.thumbnails")
            if (thumbs.isDirectory) {
                var total = 0L
                try {
                    thumbs.walkTopDown().forEach { if (it.isFile) total += it.length() }
                } catch (e: Exception) { /* ignore */ }
                if (total > 0) out += CleanableItem(
                    thumbs.absolutePath, ".thumbnails", total,
                    "Thumbnails", "Regenerable thumbnail cache"
                )
            }
        }
        return out.sortedByDescending { it.size }
    }

    /** Per-app cache sizes. Items are manual-clean (tap opens the app's settings). */
    fun scanAppCaches(ctx: Context): List<CleanableItem> {
        if (!Perms.hasUsageAccess(ctx)) return emptyList()
        val out = mutableListOf<CleanableItem>()
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val uuid = sm.getUuidForPath(ctx.filesDir)
            val pm = ctx.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                checkCancel()
                try {
                    val st = ssm.queryStatsForPackage(uuid, app.packageName, android.os.Process.myUserHandle())
                    if (st.cacheBytes > 5 * 1024 * 1024) { // only sizable caches
                        val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
                        out += CleanableItem(
                            path = app.packageName,
                            displayName = label,
                            size = st.cacheBytes,
                            category = "App cache",
                            detail = "Tap to open app settings & clear"
                        )
                    }
                } catch (e: Exception) { /* skip */ }
            }
        } catch (e: Exception) { /* unsupported */ }
        return out.sortedByDescending { it.size }
    }

    // ---------- 2. Corpse finder ----------

    fun scanCorpses(ctx: Context, onProgress: (Int) -> Unit): List<CleanableItem> {
        val pm = ctx.packageManager
        val installed = pm.getInstalledApplications(0).map { it.packageName }.toSet()
        val out = mutableListOf<CleanableItem>()
        val roots = listOf(
            File(sharedRoot(), "Android/data"),
            File(sharedRoot(), "Android/obb")
        )
        var n = 0
        for (root in roots) {
            val kids = try { root.listFiles() } catch (e: Exception) { null } ?: continue
            for (k in kids) {
                checkCancel()
                n++; onProgress(n)
                if (!k.isDirectory) continue
                if (k.name == SELF_PKG || k.name in installed) continue
                // looks like a package dir (contains dots) and is orphaned
                if ('.' !in k.name) continue
                var total = 0L
                try { k.walkTopDown().forEach { if (it.isFile) total += it.length() } } catch (e: Exception) {}
                out += CleanableItem(
                    k.absolutePath, k.name, total,
                    "Corpse", "Leftover from an uninstalled app"
                )
            }
        }
        return out.sortedByDescending { it.size }
    }

    // ---------- 3. Duplicates ----------

    fun scanDuplicates(onProgress: (Int) -> Unit): List<List<CleanableItem>> {
        val bySize = HashMap<Long, MutableList<File>>()
        walkShared(onFile = { f ->
            val len = try { f.length() } catch (e: Exception) { 0L }
            if (len > 0) bySize.getOrPut(len) { mutableListOf() }.add(f)
        }, onProgress = onProgress)
        val groups = mutableListOf<List<CleanableItem>>()
        for ((size, files) in bySize) {
            checkCancel()
            if (files.size < 2) continue
            val byHash = HashMap<String, MutableList<File>>()
            for (f in files) {
                checkCancel()
                try {
                    byHash.getOrPut(sha256(f)) { mutableListOf() }.add(f)
                } catch (e: Exception) { /* unreadable */ }
            }
            for ((_, dupes) in byHash) {
                if (dupes.size < 2) continue
                dupes.sortBy { it.absolutePath }
                // keep the first, offer the rest for deletion
                val items = dupes.drop(1).map {
                    CleanableItem(
                        it.absolutePath, it.name, size,
                        "Duplicate", "Identical to ${dupes[0].name}"
                    )
                }
                groups += items
            }
        }
        return groups.sortedByDescending { it.sumOf { i -> i.size } }
    }

    // ---------- 4. Large files ----------

    fun scanLargeFiles(top: Int = 50, onProgress: (Int) -> Unit): List<CleanableItem> {
        val heap = java.util.PriorityQueue<File>(top + 1) { a, b ->
            a.length().compareTo(b.length())
        }
        walkShared(onFile = { f ->
            heap.add(f)
            if (heap.size > top) heap.poll()
        }, onProgress = onProgress)
        return heap.sortedByDescending { it.length() }.map {
            CleanableItem(it.absolutePath, it.name, it.length(), "Large file", it.parent ?: "")
        }
    }

    // ---------- 5. App list with storage ----------

    fun listApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val usageOk = Perms.hasUsageAccess(ctx)
        val ssm = if (usageOk) ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager else null
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val uuid = try { sm.getUuidForPath(ctx.filesDir) } catch (e: Exception) { StorageManager.UUID_DEFAULT }
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val out = mutableListOf<AppInfo>()
        for (app in apps) {
            checkCancel()
            var cache = 0L; var data = 0L
            if (ssm != null) {
                try {
                    val st = ssm.queryStatsForPackage(uuid, app.packageName, android.os.Process.myUserHandle())
                    cache = st.cacheBytes; data = st.dataBytes
                } catch (e: Exception) { /* skip */ }
            }
            val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
            val icon = try { pm.getApplicationIcon(app) } catch (e: Exception) { null }
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            out += AppInfo(app.packageName, label, icon, cache, data, isSystem)
        }
        return out.sortedByDescending { it.cacheBytes + it.dataBytes }
    }

    // ---------- storage summary (StatFs — no hidden StorageStats fields) ----------

    data class StorageSummary(val total: Long, val free: Long)

    fun storageSummary(ctx: Context): StorageSummary {
        return try {
            val sf = StatFs(sharedRoot().absolutePath)
            StorageSummary(
                total = sf.blockCountLong * sf.blockSizeLong,
                free = sf.availableBlocksLong * sf.blockSizeLong
            )
        } catch (e: Exception) {
            StorageSummary(0L, 0L)
        }
    }

    // ---------- deletion ----------

    /** Deletes files/dirs. App-cache items are skipped (manual via app settings). */
    fun deleteItems(items: List<CleanableItem>): Pair<Int, Long> {
        var count = 0; var freed = 0L
        for (it in items) {
            checkCancel()
            if (it.category == "App cache") continue
            try {
                val f = File(it.path)
                if (!f.exists()) continue
                freed += if (f.isDirectory) f.walkTopDown().filter { x -> x.isFile }.sumOf { x -> x.length() } else f.length()
                if (f.deleteRecursively()) count++
            } catch (e: Exception) { /* keep going */ }
        }
        return count to freed
    }
}
