package com.amanxdev0.maidfree.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.amanxdev0.maidfree.R
import com.amanxdev0.maidfree.model.AppInfo
import com.amanxdev0.maidfree.scan.Scanner
import com.amanxdev0.maidfree.util.formatBytes

class AppsActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var search: EditText
    private val all = mutableListOf<AppInfo>()
    private val shown = mutableListOf<AppInfo>()
    private lateinit var adapter: AppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        title = "App manager"

        list = findViewById(R.id.appsList)
        search = findViewById(R.id.appSearch)
        adapter = AppsAdapter(shown,
            onInfo = { pkg ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            },
            onUninstall = { pkg ->
                try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't uninstall", Toast.LENGTH_SHORT).show()
                }
            })
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(s.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        Scanner.cancelRequested = false
        Thread {
            try {
                val apps = Scanner.listApps(this@AppsActivity)
                runOnUiThread {
                    all.clear()
                    all.addAll(apps)
                    filter("")
                }
            } catch (e: Scanner.CancelledException) {
                // ignore
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@AppsActivity, "Failed to load apps", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun filter(q: String) {
        shown.clear()
        shown.addAll(if (q.isBlank()) all else all.filter {
            it.label.contains(q, true) || it.packageName.contains(q, true)
        })
        adapter.notifyDataSetChanged()
        title = "App manager (${shown.size})"
    }

    override fun onDestroy() {
        Scanner.cancelRequested = true
        super.onDestroy()
    }
}

class AppsAdapter(
    private val items: List<AppInfo>,
    private val onInfo: (String) -> Unit,
    private val onUninstall: (String) -> Unit
) : BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(pos: Int) = items[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    private class VH(v: View) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val name: TextView = v.findViewById(R.id.appName)
        val size: TextView = v.findViewById(R.id.appSize)
        val info: Button = v.findViewById(R.id.appInfoBtn)
        val uninstall: Button = v.findViewById(R.id.appUninstallBtn)
    }

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val h = (v.tag as? VH) ?: VH(v).also { v.tag = it }
        val a = items[pos]
        h.icon.setImageDrawable(a.icon)
        h.name.text = a.label + if (a.isSystem) "  ⚙" else ""
        h.size.text = "Cache ${formatBytes(a.cacheBytes)} · Data ${formatBytes(a.dataBytes)}"
        h.info.setOnClickListener { onInfo(a.packageName) }
        h.uninstall.setOnClickListener { onUninstall(a.packageName) }
        return v
    }
}
