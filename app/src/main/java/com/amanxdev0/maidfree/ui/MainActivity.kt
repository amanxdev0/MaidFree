package com.amanxdev0.maidfree.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
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
    private lateinit var toolsList: LinearLayout
    private lateinit var schedulerSwitch: Switch

    private val prefs by lazy { getSharedPreferences("maidfree", MODE_PRIVATE) }

    data class Tool(val icon: Int, val title: String, val desc: String, val type: String)

    private val tools = listOf(
        Tool(R.drawable.ic_tool_autoclean, "Auto-clean caches", "Clears app caches automatically", "autoclean"),
        Tool(R.drawable.ic_tool_junk, "Junk cleaner", "App caches, temp files, empty folders", "junk"),
        Tool(R.drawable.ic_tool_corpse, "Corpse finder", "Leftovers from uninstalled apps", "corpse"),
        Tool(R.drawable.ic_tool_dup, "Duplicates", "Identical files wasting space", "duplicates"),
        Tool(R.drawable.ic_tool_large, "Large files", "The biggest space hogs", "large"),
        Tool(R.drawable.ic_tool_apps, "App manager", "Per-app sizes, uninstall, info", "apps"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permBanner = findViewById(R.id.permBanner)
        storageText = findViewById(R.id.storageText)
        storageBar = findViewById(R.id.storageBar)
        storageBreakdown = findViewById(R.id.storageBreakdown)
        toolsList = findViewById(R.id.toolsList)
        schedulerSwitch = findViewById(R.id.schedulerSwitch)

        findViewById<Button>(R.id.btnFiles).setOnClickListener { Perms.requestAllFilesAccess(this) }
        findViewById<Button>(R.id.btnUsage).setOnClickListener { Perms.requestUsageAccess(this) }

        buildToolRows()

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
                        storageText.text = "${formatBytes(used)} of ${formatBytes(s.total)} used"
                        storageBar.progress = pct
                        storageBreakdown.text = "${formatBytes(s.free)} free"
                    } else {
                        storageText.text = "Unavailable"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { storageText.text = "Unavailable" }
            }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildToolRows() {
        toolsList.removeAllViews()
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        tools.forEachIndexed { i, t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                setBackgroundResource(tv.resourceId)
                isClickable = true
                isFocusable = true
            }
            val icon = ImageView(this).apply {
                setImageResource(t.icon)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(14)
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(this).apply { text = t.title; textSize = 15f }
            val desc = TextView(this).apply { text = t.desc; textSize = 12f; alpha = 0.55f }
            texts.addView(title); texts.addView(desc)
            val chev = TextView(this).apply {
                text = "›"; textSize = 22f; alpha = 0.35f
            }
            row.addView(icon); row.addView(texts); row.addView(chev)
            row.setOnClickListener {
                when (t.type) {
                    "apps" -> startActivity(Intent(this, AppsActivity::class.java))
                    "autoclean" -> startActivity(Intent(this, AutoCleanActivity::class.java))
                    else -> startActivity(
                        Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_TYPE, t.type)
                    )
                }
            }
            toolsList.addView(row)
            if (i < tools.size - 1) {
                val div = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { marginStart = dp(70) }
                    setBackgroundColor(0x14FFFFFF)
                    alpha = 1f
                }
                toolsList.addView(div)
            }
        }
    }
}
