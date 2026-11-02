package com.xnvalabs.smarteyex.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service (type connectedDevice) that keeps the process alive while the glasses are connected, so button
 * presses on the glasses still reach the assistant when the phone screen is off. All logic lives in GlassesRepository;
 * this class only holds the required persistent notification.
 */
class GlassesService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Koneksi kacamata", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("SmartEyeX terhubung ke kacamata")
            .setContentText("Tombol kacamata aktif: tahan untuk bicara, tekan untuk melihat.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "glasses_connection"
        const val NOTIFICATION_ID = 4107
    }
}
