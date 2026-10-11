package com.amanxdev0.maidfree.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.amanxdev0.maidfree.R
import com.amanxdev0.maidfree.scan.Scanner
import com.amanxdev0.maidfree.util.Perms
import com.amanxdev0.maidfree.util.formatBytes
import com.amanxdev0.maidfree.work.AlarmReceiver

class MainActivity : Activity() {

    private lateinit var permBanner: LinearLayout
    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var storageBreakdown: TextView
    private lateinit var toolsGrid: GridLayout
    private lateinit var schedulerSwitch: Switch

    private val prefs by lazy { getSharedPreferences("sweep", MODE_PRIVATE) }

    data class Tool(val emoji: String, val title: String, val desc: String, val type: String)

    private val tools = listOf(
        Tool("🧹", "Junk cleaner", "App caches, temp files, empty folders", "junk"),
        Tool("💀", "Corpse finder", "Leftovers from uninstalled apps", "corpse"),
        Tool("👯", "Duplicates", "Find identical files wasting space", "duplicates"),
        Tool("🐘", "Large files", "The biggest space hogs", "large"),
        Tool("📱", "App manager", "Per-app sizes, uninstall, info", "apps"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permBanner = findViewById(R.id.permBanner)
        storageText = findViewById(R.id.storageText)
        storageBar = findViewById(R.id.storageBar)
        storageBreakdown = findViewById(R.id.storageBreakdown)
        toolsGrid = findViewById(R.id.toolsGrid)
        schedulerSwitch = findViewById(R.id.schedulerSwitch)

        findViewById<Button>(R.id.btnFiles).setOnClickListener { Perms.requestAllFilesAccess(this) }
        findViewById<Button>(R.id.btnUsage).setOnClickListener { Perms.requestUsageAccess(this) }

        buildToolCards()

        schedulerSwitch.isChecked = prefs.getBoolean("scheduler", false)
        schedulerSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("scheduler", on).apply()
            if (on) {
                ensureNotifPermission()
                AlarmReceiver.schedule(this)
                Toast.makeText(this, "Daily scan scheduled", Toast.LENGTH_SHORT).show()
            } else {
                AlarmReceiver.cancel(this)
            }
        }

        AlarmReceiver.createChannel(this)
        loadStorage()
    }

    override fun onResume() {
        super.onResume()
        updatePermBanner()
        loadStorage()
    }

    private fun updatePermBanner() {
        val needFiles = !Perms.hasAllFilesAccess(this)
        val needUsage = !Perms.hasUsageAccess(this)
        permBanner.visibility = if (needFiles || needUsage) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnFiles).visibility = if (needFiles) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnUsage).visibility = if (needUsage) View.VISIBLE else View.GONE
    }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
    }

    private fun loadStorage() {
        Thread {
            try {
                val s = Scanner.storageSummary(this@MainActivity)
                val used = s.total - s.free
                val pct = if (s.total > 0) (used * 100 / s.total).toInt() else 0
                runOnUiThread {
                    if (s.total > 0) {
                        storageText.text = "Storage: ${formatBytes(used)} of ${formatBytes(s.total)} used"
                        storageBar.progress = pct
                        storageBreakdown.text = "Free ${formatBytes(s.free)}"
                    } else {
                        storageText.text = "Storage: unavailable"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { storageText.text = "Storage: unavailable" }
            }
        }.start()
    }

    private fun buildToolCards() {
        toolsGrid.removeAllViews()
        for (t in tools) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(24, 28, 24, 28)
                setBackgroundResource(R.drawable.card_bg)
                isClickable = true
                isFocusable = true
            }
            val emoji = TextView(this).apply { text = t.emoji; textSize = 34f; gravity = Gravity.CENTER }
            val title = TextView(this).apply {
                text = t.title; textSize = 16f; gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val desc = TextView(this).apply {
                text = t.desc; textSize = 12f; gravity = Gravity.CENTER; alpha = 0.6f
            }
            card.addView(emoji); card.addView(title); card.addView(desc)
            val lp = GridLayout.LayoutParams().apply {
                width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(8, 8, 8, 8)
            }
            card.layoutParams = lp
            card.setOnClickListener {
                if (t.type == "apps") startActivity(Intent(this, AppsActivity::class.java))
                else startActivity(
                    Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_TYPE, t.type)
                )
            }
            toolsGrid.addView(card)
        }
    }
}
