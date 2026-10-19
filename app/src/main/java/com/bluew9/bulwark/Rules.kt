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

import android.content.Context

/** The firewall rules live in a small local preferences file. Nothing ever leaves the phone. */
object Rules {
    private const val FILE = "bulwark"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", value).apply()
    }

    /** Packages blocked on mobile data (mobile = true) or on Wi-Fi (mobile = false). */
    fun blocked(ctx: Context, mobile: Boolean): Set<String> =
        HashSet(prefs(ctx).getStringSet(if (mobile) "mobile" else "wifi", emptySet()) ?: emptySet())

    fun save(ctx: Context, wifi: Set<String>, mobile: Set<String>) {
        prefs(ctx).edit()
            .putStringSet("wifi", HashSet(wifi))
            .putStringSet("mobile", HashSet(mobile))
            .apply()
    }
}
