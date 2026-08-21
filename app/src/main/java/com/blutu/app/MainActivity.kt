package com.blutu.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var deviceListContainer: LinearLayout

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanning = false

    private data class DeviceInfo(
        val name: String,
        val address: String,
        var rssi: Int,
        var lastSeen: Long
    )

    private val devices = HashMap<String, DeviceInfo>()
    private val deviceViews = HashMap<String, TextView>()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshIntervalMs = 1000L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            cleanupStaleDevices()
            renderDeviceList()
            refreshHandler.postDelayed(this, refreshIntervalMs)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = try {
                result.device.name ?: result.scanRecord?.deviceName ?: "Desconocido"
            } catch (e: SecurityException) {
                "Desconocido"
            }
            val existing = devices[address]
            if (existing != null) {
                existing.rssi = result.rssi
                existing.lastSeen = System.currentTimeMillis()
            } else {
                devices[address] = DeviceInfo(name, address, result.rssi, System.currentTimeMillis())
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            runOnUiThread {
                statusText.text = "Error al escanear (código $errorCode)"
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            checkBluetoothEnabledAndScan()
        } else {
            statusText.text = "Permisos denegados. Blutu necesita Bluetooth para funcionar."
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter?.isEnabled == true) {
            startScanning()
        } else {
            statusText.text = "Bluetooth desactivado. Actívalo para escanear."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            statusText.text = "Este dispositivo no tiene Bluetooth."
            return
        }

        requestNeededPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Blutu"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Iniciando..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }

        deviceListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this).apply {
            addView(deviceListContainer)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
    }

    private fun requestNeededPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            checkBluetoothEnabledAndScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkBluetoothEnabledAndScan() {
        val adapter = bluetoothAdapter ?: return
        if (adapter.isEnabled) {
            startScanning()
        } else {
            try {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: SecurityException) {
                statusText.text = "No se pudo pedir activar Bluetooth."
            }
        }
    }

    private fun startScanning() {
        if (scanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            statusText.text = "No se pudo iniciar el escáner BLE."
            return
        }
        try {
            scanner.startScan(scanCallback)
            scanning = true
            statusText.text = "Buscando dispositivos..."
            refreshHandler.post(refreshRunnable)
        } catch (e: SecurityException) {
            statusText.text = "Falta permiso de Bluetooth."
        }
    }

    private fun stopScanning() {
        if (!scanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // permiso ya revocado, ignorar
        }
        scanning = false
    }

    private fun cleanupStaleDevices() {
        val now = System.currentTimeMillis()
        val staleAfterMs = 15000L
        val staleAddresses = devices.filter { now - it.value.lastSeen > staleAfterMs }.keys
        for (address in staleAddresses) {
            devices.remove(address)
            deviceViews.remove(address)?.let { view -> deviceListContainer.removeView(view) }
        }
    }

    private fun renderDeviceList() {
        val sorted = devices.values.sortedByDescending { it.rssi }

        statusText.text = if (sorted.isEmpty()) {
            "Buscando dispositivos..."
        } else {
            "${sorted.size} dispositivo(s) cerca"
        }

        for ((index, info) in sorted.withIndex()) {
            val label = "${info.name}\n${info.address}  ·  ${info.rssi} dBm"
            val existingView = deviceViews[info.address]
            if (existingView != null) {
                existingView.text = label
                deviceListContainer.removeView(existingView)
                deviceListContainer.addView(existingView, index)
            } else {
                val newView = TextView(this).apply {
                    text = label
                    textSize = 15f
                    setPadding(16, 20, 16, 20)
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 8)
                newView.layoutParams = params
                deviceViews[info.address] = newView
                deviceListContainer.addView(newView, index)
            }
        }
    }
}
