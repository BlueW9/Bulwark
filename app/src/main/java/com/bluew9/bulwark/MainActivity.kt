/*
 * Bulwark - app firewall
 * Copyright (C) 2026 BlueW9
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.bluew9.bulwark

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

data class AppEntry(val pkg: String, val label: String, val system: Boolean)

class MainActivity : Activity() {

    private val requestVpn = 1

    private lateinit var statusText: TextView
    private lateinit var toggleButton: TextView
    private lateinit var countText: TextView
    private lateinit var systemChip: TextView
    private lateinit var adapter: AppAdapter

    private val all = ArrayList<AppEntry>()
    private val shown = ArrayList<AppEntry>()
    private val wifiSet = HashSet<String>()
    private val mobileSet = HashSet<String>()
    private val icons = HashMap<String, Drawable?>()
    private var query = ""
    private var showSystem = false
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkWindow()
        wifiSet.addAll(Rules.blocked(this, false))
        mobileSet.addAll(Rules.blocked(this, true))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(10))
        }

        root.addView(TextView(this).apply {
            text = "Bulwark"
            setTextColor(Colors.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Choose which apps are cut off from the network."
            setTextColor(Colors.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, dp(14))
        })

        // Status card with the main switch.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = roundedBg(Colors.SURFACE, dp(12))
        }
        statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(null, Typeface.BOLD)
        }
        card.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toggleButton = pill("Turn on", Colors.PRUSSIAN) { onToggle() }
        card.addView(toggleButton)
        root.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Colors.DIM)
            setTextColor(Colors.TEXT)
            setSingleLine()
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = roundedBg(Colors.SURFACE, dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    applyFilter()
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        systemChip = pill("System apps: hidden", Colors.CHIP) {
            showSystem = !showSystem
            systemChip.text = if (showSystem) "System apps: shown" else "System apps: hidden"
            applyFilter()
        }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        val blockAll = pill("Block all shown", Colors.DANGER) { setShown(true) }
            .apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        val allowAll = pill("Allow all shown", Colors.CHIP) { setShown(false) }
            .apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(4); leftMargin = dp(4) } }
        actions.addView(systemChip, lp())
        actions.addView(blockAll, lp())
        actions.addView(allowAll, lp())
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        countText = TextView(this).apply {
            setTextColor(Colors.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(12), 0, dp(6))
        }
        root.addView(countText)

        adapter = AppAdapter()
        val list = ListView(this).apply {
            divider = ColorDrawable(Colors.BG)
            dividerHeight = dp(6)
            this.adapter = this@MainActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        loadApps()
        updateCount()
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestVpn && resultCode == RESULT_OK) launchService()
    }

    // ---------- firewall on/off ----------

    private fun onToggle() {
        if (BulwarkVpnService.running) stopFirewall() else startFirewall()
    }

    private fun startFirewall() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, requestVpn)
        } else {
            launchService()
        }
    }

    private fun launchService() {
        Rules.setEnabled(this, true)
        startService(Intent(this, BulwarkVpnService::class.java))
        handler.postDelayed({ refreshStatus() }, 400)
    }

    private fun stopFirewall() {
        Rules.setEnabled(this, false)
        startService(Intent(this, BulwarkVpnService::class.java).setAction(BulwarkVpnService.ACTION_STOP))
        handler.postDelayed({ refreshStatus() }, 400)
    }

    private fun refreshStatus() {
        if (BulwarkVpnService.running) {
            statusText.text = "Firewall is ON"
            statusText.setTextColor(Colors.OK)
            toggleButton.text = "Turn off"
            toggleButton.background = roundedBg(Colors.CHIP, dp(10))
        } else {
            statusText.text = "Firewall is OFF"
            statusText.setTextColor(Colors.ACCENT)
            toggleButton.text = "Turn on"
            toggleButton.background = roundedBg(Colors.PRUSSIAN, dp(10))
        }
    }

    // ---------- rules ----------

    private fun persist() {
        Rules.save(this, wifiSet, mobileSet)
        if (Rules.isEnabled(this)) {
            startService(Intent(this, BulwarkVpnService::class.java)
                .setAction(BulwarkVpnService.ACTION_RELOAD))
        }
        updateCount()
    }

    private fun toggle(pkg: String, mobile: Boolean) {
        val set = if (mobile) mobileSet else wifiSet
        if (!set.add(pkg)) set.remove(pkg)
        adapter.notifyDataSetChanged()
        persist()
    }

    private fun setShown(block: Boolean) {
        for (e in shown) {
            if (block) {
                wifiSet.add(e.pkg)
                mobileSet.add(e.pkg)
            } else {
                wifiSet.remove(e.pkg)
                mobileSet.remove(e.pkg)
            }
        }
        adapter.notifyDataSetChanged()
        persist()
    }

    private fun updateCount() {
        val known = all.map { it.pkg }.toSet()
        val w = if (known.isEmpty()) wifiSet.size else wifiSet.count { it in known }
        val m = if (known.isEmpty()) mobileSet.size else mobileSet.count { it in known }
        countText.text = "$w blocked on Wi-Fi   \u2022   $m blocked on mobile data"
    }

    // ---------- app list ----------

    @Suppress("DEPRECATION")
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val result = ArrayList<AppEntry>()
            for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                val ai = pi.applicationInfo ?: continue
                if (pi.packageName == packageName) continue
                val wantsInternet = pi.requestedPermissions?.contains("android.permission.INTERNET") == true
                if (!wantsInternet) continue
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                result.add(AppEntry(pi.packageName, pm.getApplicationLabel(ai).toString(), system))
            }
            result.sortBy { it.label.lowercase() }
            runOnUiThread {
                all.clear()
                all.addAll(result)
                applyFilter()
                updateCount()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = query.lowercase()
        shown.clear()
        for (e in all) {
            if (e.system && !showSystem) continue
            if (q.isEmpty() || e.label.lowercase().contains(q) || e.pkg.lowercase().contains(q)) shown.add(e)
        }
        adapter.notifyDataSetChanged()
    }

    private fun iconFor(pkg: String): Drawable? = icons.getOrPut(pkg) {
        try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }
    }

    private fun styleChip(c: TextView, blocked: Boolean) {
        if (blocked) {
            c.setTextColor(Color.WHITE)
            c.background = roundedBg(Colors.DANGER, dp(8))
            c.paintFlags = c.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            c.setTextColor(Colors.TEXT)
            c.background = roundedBg(Colors.CHIP, dp(8))
            c.paintFlags = c.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private class Holder(
        val root: LinearLayout,
        val icon: ImageView,
        val name: TextView,
        val pkg: TextView,
        val wifi: TextView,
        val data: TextView
    ) {
        var current: AppEntry? = null
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h = (convertView?.tag as? Holder) ?: newHolder()
            val e = shown[position]
            h.current = e
            h.icon.setImageDrawable(iconFor(e.pkg))
            h.name.text = e.label
            h.pkg.text = e.pkg
            styleChip(h.wifi, e.pkg in wifiSet)
            styleChip(h.data, e.pkg in mobileSet)
            return h.root
        }

        private fun chip(label: String) = TextView(this@MainActivity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        private fun newHolder(): Holder {
            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBg(Colors.SURFACE, dp(12))
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(12); rightMargin = dp(8) }
            }
            val name = TextView(this@MainActivity).apply {
                setTextColor(Colors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            }
            val pkg = TextView(this@MainActivity).apply {
                setTextColor(Colors.DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            }
            texts.addView(name)
            texts.addView(pkg)
            val wifi = chip("Wi-Fi")
            val data = chip("Data")
            root.addView(icon)
            root.addView(texts)
            root.addView(wifi, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
            root.addView(data)
            val h = Holder(root, icon, name, pkg, wifi, data)
            root.tag = h
            wifi.setOnClickListener { h.current?.let { toggle(it.pkg, false) } }
            data.setOnClickListener { h.current?.let { toggle(it.pkg, true) } }
            return h
        }
    }
}
