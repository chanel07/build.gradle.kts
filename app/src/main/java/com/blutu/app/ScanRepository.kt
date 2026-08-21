package com.blutu.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ScanRepository {

    data class Victim(
        val address: String,
        val type: BlutuProtocol.EmergencyType,
        val smoothedRssi: Int,
        val lastSeen: Long
    )

    // Estado interno crudo por dispositivo, con RSSI suavizado.
    private data class Raw(
        val address: String,
        var type: BlutuProtocol.EmergencyType,
        var smoothedRssi: Double,
        var lastSeen: Long
    )

    private const val STALE_AFTER_MS = 12000L
    // Factor de suavizado: más bajo = más estable pero más lento en reaccionar.
    private const val SMOOTHING = 0.3

    private val raws = HashMap<String, Raw>()

    private val _victims = MutableStateFlow<List<Victim>>(emptyList())
    val victims: StateFlow<List<Victim>> = _victims

    @Synchronized
    fun update(address: String, type: BlutuProtocol.EmergencyType, rssi: Int) {
        val now = System.currentTimeMillis()
        val existing = raws[address]
        if (existing != null) {
            // Promedio móvil exponencial para estabilizar la señal.
            existing.smoothedRssi =
                existing.smoothedRssi * (1 - SMOOTHING) + rssi * SMOOTHING
            existing.type = type
            existing.lastSeen = now
        } else {
            raws[address] = Raw(address, type, rssi.toDouble(), now)
        }
        emit()
    }

    @Synchronized
    fun purgeStale() {
        val now = System.currentTimeMillis()
        val stale = raws.filter { now - it.value.lastSeen > STALE_AFTER_MS }.keys
        for (a in stale) raws.remove(a)
        emit()
    }

    @Synchronized
    fun clear() {
        raws.clear()
        emit()
    }

    private fun emit() {
        _victims.value = raws.values
            .map {
                Victim(
                    address = it.address,
                    type = it.type,
                    smoothedRssi = it.smoothedRssi.toInt(),
                    lastSeen = it.lastSeen
                )
            }
            .sortedByDescending { it.smoothedRssi }
    }
}
