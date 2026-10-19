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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import android.util.TypedValue

object Colors {
    val BG = Color.parseColor("#0B0F14")
    val SURFACE = Color.parseColor("#151C24")
    val CHIP = Color.parseColor("#223041")
    val TEXT = Color.parseColor("#E6EDF3")
    val DIM = Color.parseColor("#7F8C99")
    val ACCENT = Color.parseColor("#F2B134")
    val PRUSSIAN = Color.parseColor("#0A4A78")
    val DANGER = Color.parseColor("#D64545")
    val OK = Color.parseColor("#3FB27F")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun roundedBg(color: Int, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx.toFloat()
}

fun Context.pill(label: String, fill: Int, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    gravity = Gravity.CENTER
    setTextColor(Colors.TEXT)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setPadding(dp(14), dp(10), dp(14), dp(10))
    background = roundedBg(fill, dp(10))
    setOnClickListener { onClick() }
}

fun Activity.applyDarkWindow() {
    window.statusBarColor = Colors.BG
    window.navigationBarColor = Colors.BG
    window.decorView.setBackgroundColor(Colors.BG)
}
