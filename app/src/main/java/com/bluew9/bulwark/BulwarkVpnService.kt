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

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException

/**
 * How the blocking works (no root needed):
 *
 * Android lets an app create a local VPN. Bulwark creates one that only covers the apps you chose
 * (addAllowedApplication) and sends every packet of those apps into a dead end: the packets are
 * read and thrown away, so they never reach the network. Apps you did not choose are not touched.
 *
 * The rules are applied separately for Wi-Fi and for mobile data. When the phone switches network,
 * the VPN is rebuilt with the right list. The system itself runs this service, so it keeps working
 * when the Bulwark screen is closed, and it is started again after a reboot.
 */
class BulwarkVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.bluew9.bulwark.STOP"
        const val ACTION_RELOAD = "com.bluew9.bulwark.RELOAD"

        @Volatile
        var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tun: ParcelFileDescriptor? = null
    private var applied: Set<String>? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val applyRunnable = Runnable { apply() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Rules.setEnabled(this, false)
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> startUp()
            SERVICE_INTERFACE -> {
                // Started by the system (Always-on VPN).
                Rules.setEnabled(this, true)
                startUp()
            }
            else -> {
                if (Rules.isEnabled(this)) {
                    startUp()
                } else {
                    shutdown()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // The user turned the VPN off from Android settings, or another VPN took over.
        Rules.setEnabled(this, false)
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        unregisterCallback()
        closeTun()
        super.onDestroy()
    }

    private fun startUp() {
        if (!running) {
            running = true
            registerCallback()
        }
        applied = null
        scheduleApply(0)
    }

    private fun shutdown() {
        running = false
        handler.removeCallbacksAndMessages(null)
        unregisterCallback()
        closeTun()
        applied = null
        stopSelf()
    }

    private fun scheduleApply(delayMs: Long) {
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, delayMs)
    }

    private fun apply() {
        if (!running) return
        val toBlock = blockSet()
        if (toBlock == applied) return

        if (toBlock.isEmpty()) {
            closeTun()
            applied = toBlock
            return
        }

        val builder = Builder()
            .setSession("Bulwark")
            .setBlocking(true)
            .setMtu(1500)
            .addAddress("10.255.255.1", 32)
            .addAddress("fd00:255::1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)

        var covered = 0
        for (pkg in toBlock) {
            try {
                builder.addAllowedApplication(pkg)
                covered++
            } catch (e: Exception) {
                // App not installed any more: skip it.
            }
        }
        if (covered == 0) {
            closeTun()
            applied = toBlock
            return
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
        if (fd == null) {
            // VPN permission was withdrawn.
            Rules.setEnabled(this, false)
            shutdown()
            return
        }

        closeTun() // close the previous interface only after the new one exists
        tun = fd
        applied = toBlock
        startDrain(fd)
    }

    /** Reads the packets of the blocked apps and discards them. */
    private fun startDrain(fd: ParcelFileDescriptor) {
        Thread {
            val input = FileInputStream(fd.fileDescriptor)
            val buffer = ByteArray(32767)
            try {
                while (true) {
                    if (input.read(buffer) < 0) break
                }
            } catch (e: IOException) {
                // Interface closed.
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun closeTun() {
        try {
            tun?.close()
        } catch (e: IOException) {
        }
        tun = null
    }

    /** 0 = unknown/none, 1 = Wi-Fi or Ethernet, 2 = mobile data. */
    private fun transport(): Int {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return 0
        val caps = cm.getNetworkCapabilities(network) ?: return 0
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 0
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            else -> 0
        }
    }

    private fun blockSet(): Set<String> {
        val wifi = Rules.blocked(this, false)
        val mobile = Rules.blocked(this, true)
        return when (transport()) {
            1 -> wifi
            2 -> mobile
            else -> wifi + mobile // when unsure, block everything that is blocked anywhere
        }
    }

    private fun registerCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleApply(400)
            override fun onLost(network: Network) = scheduleApply(400)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleApply(400)
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            callback = null
        }
    }

    private fun unregisterCallback() {
        val cb = callback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (e: Exception) {
        }
        callback = null
    }
}
