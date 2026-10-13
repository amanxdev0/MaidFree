package com.amanxdev0.maidfree.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.amanxdev0.maidfree.R
import com.amanxdev0.maidfree.clean.AutoCleanService
import com.amanxdev0.maidfree.model.CleanableItem
import com.amanxdev0.maidfree.scan.Scanner
import com.amanxdev0.maidfree.util.formatBytes

/**
 * Automatic cache cleaner: ticks through the selected apps, opens each one's
 * system settings page and taps "Clear cache" via the accessibility service.
 * Experimental — OEM settings screens differ, so some apps may be skipped.
 */
class AutoCleanActivity : Activity() {

    private lateinit var setupBox: LinearLayout
    private lateinit var mainBox: LinearLayout
    private lateinit var list: ListView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var totalText: TextView
    private lateinit var cleanBtn: Button
    private lateinit var scheduleCheck: CheckBox

    private val items = mutableListOf<CleanableItem>()
    private lateinit var adapter: CacheAdapter
    private val prefs by lazy { getSharedPreferences("maidfree", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_autoclean)
        title = "Auto-clean caches"

        setupBox = findViewById(R.id.setupBox)
        mainBox = findViewById(R.id.mainBox)
        list = findViewById(R.id.cacheList)
        progress = findViewById(R.id.cleanProgress)
        status = findViewById(R.id.cleanStatus)
        totalText = findViewById(R.id.cleanTotal)
        cleanBtn = findViewById(R.id.cleanBtn)
        scheduleCheck = findViewById(R.id.scheduleCheck)

        findViewById<Button>(R.id.btnEnableAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Turn on MaidFree in the list, then come back",
                Toast.LENGTH_LONG
            ).show()
        }

        adapter = CacheAdapter(items) { updateTotal() }
        list.adapter = adapter
        cleanBtn.setOnClickListener { confirmAndStart() }
        scheduleCheck.isChecked = prefs.getBoolean("autoClean", false)
        scheduleCheck.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("autoClean", on).apply()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            findViewById<TextView>(R.id.restrictedNote).visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = AutoCleanService.isEnabled(this)
        setupBox.visibility = if (enabled) View.GONE else View.VISIBLE
        mainBox.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) loadCaches()
    }

    private fun loadCaches() {
        status.text = "Measuring app caches…"
        progress.visibility = View.VISIBLE
        Thread {
            val found = try {
                Scanner.scanAppCaches(this@AutoCleanActivity)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                progress.visibility = View.GONE
                items.clear()
                items.addAll(found)
                adapter.notifyDataSetChanged()
                status.text = if (items.isEmpty()) "No sizable caches found"
                else "${items.size} apps holding cache"
                updateTotal()
            }
        }.start()
    }

    private fun updateTotal() {
        val sel = items.filter { it.checked }
        totalText.text = "${sel.size} selected · ${formatBytes(sel.sumOf { it.size })}"
        cleanBtn.isEnabled = sel.isNotEmpty() && !AutoCleanService.running
    }

    private fun confirmAndStart() {
        val sel = items.filter { it.checked }
        if (sel.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Auto-clean ${sel.size} app caches?")
            .setMessage(
                "MaidFree will open each app's settings and tap \"Clear cache\" " +
                    "one by one (${formatBytes(sel.sumOf { it.size })} total). " +
                    "It never touches \"Clear data\". Keep the screen on until it finishes."
            )
            .setPositiveButton("Start") { _, _ -> startCleaning(sel.map { it.path }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCleaning(pkgs: List<String>) {
        val svc = AutoCleanService.instance
        if (svc == null) {
            Toast.makeText(this, "Accessibility service isn't connected yet", Toast.LENGTH_LONG).show()
            return
        }
        cleanBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.max = pkgs.size
        AutoCleanService.onProgress = { done, total, current, finished ->
            runOnUiThread {
                progress.progress = done
                status.text = if (finished) "Done — cleared $done app caches"
                else "Cleaning $done of $total… $current"
                if (finished) {
                    cleanBtn.isEnabled = true
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this, "Auto-clean finished ($done apps)", Toast.LENGTH_LONG
                    ).show()
                    loadCaches() // refresh remaining cache sizes
                }
            }
        }
        status.text = "Starting… keep the screen on"
        svc.begin(pkgs)
    }

    override fun onDestroy() {
        AutoCleanService.onProgress = null
        super.onDestroy()
    }
}

class CacheAdapter(
    private val items: List<CleanableItem>,
    private val onToggle: () -> Unit
) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(pos: Int) = items[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_result, parent, false)
        val item = items[pos]
        v.findViewById<TextView>(R.id.itemName).text = item.displayName
        v.findViewById<TextView>(R.id.itemDetail).text =
            "App cache · ${formatBytes(item.size)}"
        v.findViewById<TextView>(R.id.itemSize).text = formatBytes(item.size)
        v.findViewById<Button>(R.id.itemOpen).visibility = View.GONE
        val check = v.findViewById<CheckBox>(R.id.itemCheck)
        check.visibility = View.VISIBLE
        check.setOnCheckedChangeListener(null)
        check.isChecked = item.checked
        check.setOnCheckedChangeListener { _, on ->
            item.checked = on
            onToggle()
        }
        v.setOnClickListener {
            item.checked = !item.checked
            check.isChecked = item.checked
            onToggle()
        }
        return v
    }
}
