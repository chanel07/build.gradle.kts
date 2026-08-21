package com.blutu.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class AdvertiseService : Service() {

    companion object {
        const val EXTRA_TYPE_CODE = "type_code"
        private const val CHANNEL_ID = "blutu_sos"
        private const val NOTIF_ID = 101
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
        }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = manager.adapter
        advertiser = adapter?.bluetoothLeAdvertiser
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val typeCode = intent?.getByteExtra(EXTRA_TYPE_CODE, 0) ?: 0
        val type = BlutuProtocol.EmergencyType.fromCode(typeCode)

        startForegroundNotification(type)
        startAdvertising(type)
        return START_STICKY
    }

    private fun startForegroundNotification(type: BlutuProtocol.EmergencyType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOS Blutu",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PIDIENDO AYUDA")
            .setContentText("Blutu está transmitiendo: ${type.label}")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startAdvertising(type: BlutuProtocol.EmergencyType) {
        val adv = advertiser ?: run { stopSelf(); return }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(BlutuProtocol.SERVICE_UUID)
            .addManufacturerData(
                BlutuProtocol.MANUFACTURER_ID,
                BlutuProtocol.buildManufacturerData(type)
            )
            .build()

        try {
            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (advertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: SecurityException) {
                // permiso revocado, ignorar
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
