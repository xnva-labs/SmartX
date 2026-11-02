package com.xnvalabs.smarteyex.data.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

/** Snapshot of device status — battery, connectivity, storage. */
data class DeviceStatus(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: String,
    val storageFreeGb: Double,
    val storageTotalGb: Double,
)

/**
 * Feature #37 (Device Management) — reads live device status via
 * Android's own APIs (BatteryManager, ConnectivityManager, StatFs), no
 * new permission needed for any of these. Pull-based rather than a
 * registered BroadcastReceiver: DeviceScreen calls [snapshot] on open
 * and on a manual refresh tap, which is enough for a status dashboard
 * that doesn't need sub-second updates.
 *
 * The battery read uses the documented null-receiver sticky-intent
 * trick (`registerReceiver(null, filter)`), which returns the last
 * ACTION_BATTERY_CHANGED broadcast synchronously without actually
 * registering a callback — so it doesn't need the receiver-exported
 * flag Android 13+ requires for real registered receivers.
 */
object DeviceStatusRepository {
    fun snapshot(context: Context): DeviceStatus {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val networkType = when {
            capabilities == null -> "Tidak terhubung"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data Seluler"
            else -> "Terhubung"
        }

        val stat = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024.0 * 1024.0

        return DeviceStatus(
            batteryPercent = percent,
            isCharging = charging,
            networkType = networkType,
            storageFreeGb = stat.availableBytes / gb,
            storageTotalGb = stat.totalBytes / gb,
        )
    }
}
