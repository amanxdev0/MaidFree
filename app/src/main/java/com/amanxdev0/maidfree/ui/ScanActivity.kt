package com.amanxdev0.maidfree.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.amanxdev0.maidfree.R
import com.amanxdev0.maidfree.model.CleanableItem
import com.amanxdev0.maidfree.scan.Scanner
import com.amanxdev0.maidfree.util.formatBytes

class ScanActivity : Activity() {

    companion object { const val EXTRA_TYPE = "type" }

    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var selectAll: CheckBox
    private lateinit var totalText: TextView
    private lateinit var deleteBtn: Button

    private val items = mutableListOf<CleanableItem>()
    private lateinit var adapter: ResultsAdapter
    private var type: String = "junk"

    private val titles = mapOf(
        "junk" to "Junk cleaner",
        "corpse" to "Corpse finder",
        "duplicates" to "Duplicate files",
        "large" to "Large files"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        type = intent.getStringExtra(EXTRA_TYPE) ?: "junk"
        title = titles[type] ?: "Scan"

        progress = findViewById(R.id.scanProgress)
        status = findViewById(R.id.scanStatus)
        list = findViewById(R.id.resultsList)
        selectAll = findViewById(R.id.selectAll)
        totalText = findViewById(R.id.totalText)
        deleteBtn = findViewById(R.id.deleteBtn)

        adapter = ResultsAdapter(items,
            onToggle = { updateTotal() },
            onOpenApp = { pkg -> openAppSettings(pkg) })
        list.adapter = adapter

        selectAll.setOnCheckedChangeListener { _, on ->
            items.forEach { if (it.category != "App cache") it.checked = on }
            adapter.notifyDataSetChanged()
            updateTotal()
        }

        deleteBtn.setOnClickListener { confirmDelete() }

        // Long-press a result to exclude its path from all future scans.
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val item = items.getOrNull(pos) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle("Exclude from scans?")
                .setMessage("${item.displayName}\n${item.path}\n\nIt won't appear in future scans.")
                .setPositiveButton("Exclude") { _, _ ->
                    com.amanxdev0.maidfree.util.Exclusions.add(this, item.path)
                    items.remove(item)
                    adapter.notifyDataSetChanged()
                    updateTotal()
                    Toast.makeText(this, "Excluded", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        startScan()
    }

    private fun startScan() {
        Scanner.cancelRequested = false
        progress.visibility = View.VISIBLE
        status.text = "Scanning…"
        Thread {
            try {
                val found: List<CleanableItem> = when (type) {
                    "junk" -> Scanner.scanJunk(this@ScanActivity) { n ->
                        runOnUiThread { status.text = "Scanning… $n files" }
                    }
                    "corpse" -> Scanner.scanCorpses(this@ScanActivity) { n ->
                        runOnUiThread { status.text = "Checking app folders… $n" }
                    }
                    "duplicates" -> Scanner.scanDuplicates(this@ScanActivity) { n ->
                        runOnUiThread { status.text = "Hashing… $n files" }
                    }.flatten()
                    "large" -> Scanner.scanLargeFiles(this@ScanActivity) { n ->
                        runOnUiThread { status.text = "Measuring… $n files" }
                    }
                    else -> emptyList()
                }
                runOnUiThread {
                    items.clear()
                    items.addAll(found)
                    progress.visibility = View.GONE
                    status.text = if (items.isEmpty()) "Nothing found — already clean"
                    else "${items.size} items found"
                    adapter.notifyDataSetChanged()
                    updateTotal()
                }
            } catch (e: Scanner.CancelledException) {
                // user left; ignore
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "Scan failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun updateTotal() {
        val sel = items.filter { it.checked && it.category != "App cache" }
        val bytes = sel.sumOf { it.size }
        val manual = items.count { it.checked && it.category == "App cache" }
        totalText.text = buildString {
            append("${sel.size} selected · ${formatBytes(bytes)}")
            if (manual > 0) append("  (+$manual app caches — open manually)")
        }
        deleteBtn.isEnabled = sel.isNotEmpty()
    }

    private fun confirmDelete() {
        val sel = items.filter { it.checked && it.category != "App cache" }
        if (sel.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${sel.size} items?")
            .setMessage("This will free ${formatBytes(sel.sumOf { it.size })}. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> doDelete(sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete(sel: List<CleanableItem>) {
        deleteBtn.isEnabled = false
        status.text = "Deleting…"
        Thread {
            try {
                val (count, freed) = Scanner.deleteItems(sel)
                runOnUiThread {
                    items.removeAll(sel.toSet())
                    adapter.notifyDataSetChanged()
                    updateTotal()
                    status.text = "Deleted $count items · freed ${formatBytes(freed)}"
                    Toast.makeText(this@ScanActivity, "Freed ${formatBytes(freed)}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Delete failed: ${e.message}"
                    deleteBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun openAppSettings(pkg: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open settings", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        Scanner.cancelRequested = true
        super.onDestroy()
    }
}

class ResultsAdapter(
    private val items: List<CleanableItem>,
    private val onToggle: () -> Unit,
    private val onOpenApp: (String) -> Unit
) : BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(pos: Int) = items[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    private class VH(v: View) {
        val check: CheckBox = v.findViewById(R.id.itemCheck)
        val name: TextView = v.findViewById(R.id.itemName)
        val detail: TextView = v.findViewById(R.id.itemDetail)
        val size: TextView = v.findViewById(R.id.itemSize)
        val open: Button = v.findViewById(R.id.itemOpen)
    }

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        val h = (v.tag as? VH) ?: VH(v).also { v.tag = it }
        val item = items[pos]
        h.name.text = item.displayName
        h.detail.text = "${item.category} · ${item.detail}"
        h.size.text = formatBytes(item.size)
        if (item.category == "App cache") {
            h.check.visibility = View.GONE
            h.open.visibility = View.VISIBLE
            h.open.setOnClickListener { onOpenApp(item.path) }
            v.setOnClickListener { onOpenApp(item.path) }
        } else {
            h.check.visibility = View.VISIBLE
            h.open.visibility = View.GONE
            h.check.setOnCheckedChangeListener(null)
            h.check.isChecked = item.checked
            h.check.setOnCheckedChangeListener { _, on ->
                item.checked = on
                onToggle()
            }
            v.setOnClickListener {
                item.checked = !item.checked
                h.check.isChecked = item.checked
                onToggle()
            }
        }
        return v
    }
}
