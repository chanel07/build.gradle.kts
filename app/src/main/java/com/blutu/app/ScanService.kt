package com.blutu.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class ScanService : Service() {

    companion object {
        private const val CHANNEL_ID = "blutu_scan"
        private const val NOTIF_ID = 102
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    private val purgeHandler = Handler(Looper.getMainLooper())
    private val purgeRunnable = object : Runnable {
        override fun run() {
            ScanRepository.purgeStale()
            purgeHandler.postDelayed(this, 2000L)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val mfgData = record.getManufacturerSpecificData(BlutuProtocol.MANUFACTURER_ID)
            val type = BlutuProtocol.parseManufacturerData(mfgData) ?: return
            ScanRepository.update(result.device.address, type, result.rssi)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = manager.adapter
        scanner = adapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        startScanning()
        purgeHandler.post(purgeRunnable)
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Búsqueda Blutu",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Buscando personas")
            .setContentText("Blutu está escaneando balizas de ayuda...")
            .setSmallIcon(android.R.drawable.ic_menu_search)
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

    private fun startScanning() {
        if (scanning) return
        val s = scanner ?: run { stopSelf(); return }

        // Filtro por UUID Blutu: el sistema descarta todo lo demás.
        val filter = ScanFilter.Builder()
            .setServiceUuid(BlutuProtocol.SERVICE_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            s.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopScanning() {
        if (!scanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // permiso revocado, ignorar
        }
        scanning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        purgeHandler.removeCallbacks(purgeRunnable)
        stopScanning()
        ScanRepository.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
