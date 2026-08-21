package com.blutu.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    // UUID de prueba Blutu. En la app real este identifica una baliza Blutu.
    private val blutuServiceUuid = ParcelUuid(
        UUID.fromString("0000b101-0000-1000-8000-00805f9b34fb")
    )

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            runOnUiThread {
                statusText.text = "✓ Tu celular PUEDE transmitir"
                statusText.setTextColor(Color.parseColor("#1B7F3B"))
                detailText.text = "Transmitiendo baliza de prueba Blutu.\n\n" +
                        "Abre otra app BLE (como nRF Connect) en otro " +
                        "celular y busca un dispositivo con este UUID:\n\n" +
                        "0000b101-...-34fb\n\n" +
                        "Si lo ves, el modo VÍCTIMA funciona en este equipo."
            }
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Los datos son muy grandes"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Demasiadas transmisiones activas"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Ya estaba transmitiendo"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Error interno"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "El hardware NO soporta transmitir"
                else -> "Error desconocido ($errorCode)"
            }
            runOnUiThread {
                statusText.text = "✗ No pudo transmitir"
                statusText.setTextColor(Color.parseColor("#B00020"))
                detailText.text = "Motivo: $reason\n\n" +
                        "Este celular quizás solo sirva como RESCATISTA " +
                        "(buscar), no como víctima (pedir ayuda)."
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            tryAdvertise()
        } else {
            statusText.text = "Permisos denegados"
            detailText.text = "Blutu necesita permiso de Bluetooth para probar."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager.adapter

        val adapter = bluetoothAdapter
        if (adapter == null) {
            statusText.text = "✗ Sin Bluetooth"
            detailText.text = "Este dispositivo no tiene Bluetooth."
            return
        }

        if (!adapter.isEnabled) {
            statusText.text = "Bluetooth apagado"
            detailText.text = "Activa el Bluetooth y vuelve a abrir la app."
            return
        }

        // Chequeo de capacidad ANTES de intentar transmitir
        if (!adapter.isMultipleAdvertisementSupported) {
            statusText.text = "✗ Hardware sin soporte de transmisión"
            statusText.setTextColor(Color.parseColor("#B00020"))
            detailText.text = "Este celular NO puede transmitir balizas BLE.\n\n" +
                    "Solo podrá funcionar como RESCATISTA (buscar), " +
                    "no como víctima (pedir ayuda)."
            return
        }

        requestPermissionsAndAdvertise()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            // permiso revocado, ignorar
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Blutu — Prueba de transmisión"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            text = "Probando..."
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        detailText = TextView(this).apply {
            text = ""
            textSize = 15f
            gravity = Gravity.CENTER
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(detailText)
        setContentView(root)
    }

    private fun requestPermissionsAndAdvertise() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf() // en versiones viejas el permiso es de manifest, no runtime
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            tryAdvertise()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun tryAdvertise() {
        val adapter = bluetoothAdapter ?: return
        advertiser = adapter.bluetoothLeAdvertiser

        if (advertiser == null) {
            statusText.text = "✗ Sin transmisor BLE"
            detailText.text = "El sistema no entregó un transmisor. " +
                    "Probablemente este celular no puede transmitir."
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(blutuServiceUuid)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            statusText.text = "Iniciando transmisión..."
        } catch (e: SecurityException) {
            statusText.text = "✗ Falta permiso"
            detailText.text = "No se pudo transmitir por falta de permiso."
        }
    }
}
