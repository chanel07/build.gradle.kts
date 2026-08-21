package com.blutu.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var rootLayout: LinearLayout
    private var bluetoothAdapter: BluetoothAdapter? = null

    private enum class Screen { HOME, VICTIM, RESCUER }
    private var currentScreen = Screen.HOME

    // Guarda qué acción ejecutar después de conceder permisos.
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pendingAction?.invoke()
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        setContentView(rootLayout)

        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager.adapter

        showHome()
        observeVictims()
    }

    // ---------- Pantalla de inicio ----------

    private fun showHome() {
        currentScreen = Screen.HOME
        rootLayout.removeAllViews()

        addTitle("Blutu")
        addSpacer(24)

        val subtitle = TextView(this).apply {
            text = "Ayuda de emergencia por Bluetooth"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        rootLayout.addView(subtitle)
        addSpacer(48)

        val helpBtn = bigButton("NECESITO AYUDA", "#B00020") { showVictim() }
        rootLayout.addView(helpBtn)
        addSpacer(24)

        val rescueBtn = bigButton("BUSCAR PERSONAS", "#1B5E20") { showRescuer() }
        rootLayout.addView(rescueBtn)
    }

    // ---------- Pantalla víctima ----------

    private fun showVictim() {
        currentScreen = Screen.VICTIM
        rootLayout.removeAllViews()

        addBackButton { stopAllServices(); showHome() }
        addTitle("Pedir ayuda")
        addSpacer(16)

        val info = TextView(this).apply {
            text = "Elige tu situación. Tu celular emitirá una señal " +
                    "para que los rescatistas te encuentren."
            textSize = 15f
            gravity = Gravity.CENTER
        }
        rootLayout.addView(info)
        addSpacer(32)

        for (type in BlutuProtocol.EmergencyType.entries) {
            val btn = Button(this).apply {
                text = type.label
                textSize = 17f
                setOnClickListener { startVictim(type) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 16)
            btn.layoutParams = params
            rootLayout.addView(btn)
        }
    }

    private fun startVictim(type: BlutuProtocol.EmergencyType) {
        val action = {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                showVictimActive(type, btEnabled = false)
            } else {
                val intent = Intent(this, AdvertiseService::class.java)
                intent.putExtra(AdvertiseService.EXTRA_TYPE_CODE, type.code)
                ContextCompat.startForegroundService(this, intent)
                showVictimActive(type, btEnabled = true)
            }
        }
        runWithPermissions(victimPermissions(), action)
    }

    private fun showVictimActive(type: BlutuProtocol.EmergencyType, btEnabled: Boolean) {
        rootLayout.removeAllViews()
        addBackButton { stopAllServices(); showHome() }

        if (!btEnabled) {
            addTitle("Bluetooth apagado")
            val warn = TextView(this).apply {
                text = "Activa el Bluetooth y vuelve a intentar."
                textSize = 16f
                gravity = Gravity.CENTER
            }
            rootLayout.addView(warn)
            return
        }

        val banner = TextView(this).apply {
            text = "EMITIENDO SEÑAL"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B00020"))
            setPadding(0, 40, 0, 20)
        }
        rootLayout.addView(banner)

        val detail = TextView(this).apply {
            text = "${type.label}\n\nMantén el celular encendido. " +
                    "Los rescatistas cercanos con Blutu pueden verte.\n\n" +
                    "La señal sigue activa aunque bloquees la pantalla."
            textSize = 16f
            gravity = Gravity.CENTER
        }
        rootLayout.addView(detail)
        addSpacer(40)

        val stopBtn = bigButton("DETENER", "#555555") {
            stopAllServices(); showHome()
        }
        rootLayout.addView(stopBtn)
    }

    // ---------- Pantalla rescatista ----------

    private lateinit var rescuerListContainer: LinearLayout
    private lateinit var rescuerStatus: TextView

    private fun showRescuer() {
        currentScreen = Screen.RESCUER
        rootLayout.removeAllViews()

        addBackButton { stopAllServices(); showHome() }
        addTitle("Buscar personas")

        rescuerStatus = TextView(this).apply {
            text = "Iniciando búsqueda..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        rootLayout.addView(rescuerStatus)

        rescuerListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply { addView(rescuerListContainer) }
        rootLayout.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val action = {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                rescuerStatus.text = "Activa el Bluetooth para buscar."
            } else {
                val intent = Intent(this, ScanService::class.java)
                ContextCompat.startForegroundService(this, intent)
                rescuerStatus.text = "Buscando señales de ayuda..."
            }
        }
        runWithPermissions(rescuerPermissions(), action)
    }

    private fun observeVictims() {
        lifecycleScope.launch {
            ScanRepository.victims.collect { victims ->
                if (currentScreen == Screen.RESCUER) {
                    renderVictimList(victims)
                }
            }
        }
    }

    private fun renderVictimList(victims: List<ScanRepository.Victim>) {
        if (!::rescuerListContainer.isInitialized) return
        rescuerListContainer.removeAllViews()

        rescuerStatus.text = if (victims.isEmpty()) {
            "Buscando señales de ayuda..."
        } else {
            "${victims.size} persona(s) pidiendo ayuda"
        }

        for (v in victims) {
            val proximity = rssiToProximity(v.smoothedRssi)
            val card = TextView(this).apply {
                text = "${v.type.label}\n$proximity  ·  ${v.smoothedRssi} dBm"
                textSize = 17f
                setPadding(24, 28, 24, 28)
                setBackgroundColor(proximityColor(v.smoothedRssi))
                setTextColor(Color.WHITE)
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 12)
            card.layoutParams = params
            rescuerListContainer.addView(card)
        }
    }

    // Traduce RSSI a lenguaje humano de cercanía.
    private fun rssiToProximity(rssi: Int): String = when {
        rssi >= -55 -> "MUY CERCA"
        rssi >= -70 -> "Cerca"
        rssi >= -85 -> "Media distancia"
        else -> "Lejos"
    }

    private fun proximityColor(rssi: Int): Int = when {
        rssi >= -55 -> Color.parseColor("#B00020")
        rssi >= -70 -> Color.parseColor("#E65100")
        rssi >= -85 -> Color.parseColor("#F9A825")
        else -> Color.parseColor("#9E9E9E")
    }

    // ---------- Permisos ----------

    private fun victimPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun rescuerPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun runWithPermissions(perms: Array<String>, action: () -> Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------- Utilidades UI ----------

    private fun stopAllServices() {
        stopService(Intent(this, AdvertiseService::class.java))
        stopService(Intent(this, ScanService::class.java))
        ScanRepository.clear()
    }

    private fun addTitle(text: String) {
        val t = TextView(this).apply {
            this.text = text
            textSize = 30f
            gravity = Gravity.CENTER
        }
        rootLayout.addView(t)
    }

    private fun addSpacer(height: Int) {
        val s = View(this)
        s.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height
        )
        rootLayout.addView(s)
    }

    private fun addBackButton(onBack: () -> Unit) {
        val b = Button(this).apply {
            text = "← Volver"
            setOnClickListener { onBack() }
        }
        rootLayout.addView(b)
    }

    private fun bigButton(label: String, colorHex: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(colorHex))
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 220
        )
        b.layoutParams = params
        return b
    }
}
