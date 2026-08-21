package com.blutu.app

import android.os.ParcelUuid
import java.util.UUID

/**
 * Contrato compartido entre el rol víctima (transmite) y rescatista (escanea).
 * Única fuente de verdad del formato de la baliza Blutu.
 */
object BlutuProtocol {

    // Firma que identifica una baliza Blutu entre todo el ruido BLE.
    val SERVICE_UUID: ParcelUuid = ParcelUuid(
        UUID.fromString("0000b101-0000-1000-8000-00805f9b34fb")
    )

    // "Company ID" ficticio para los datos del fabricante. Debe ser el mismo
    // en ambos roles. 0xB1B1 es arbitrario pero constante para Blutu.
    const val MANUFACTURER_ID = 0xB1B1

    // Tipos de emergencia. Un solo byte, fácil de leer sin conectar.
    enum class EmergencyType(val code: Byte, val label: String) {
        GENERAL(0, "Ayuda general"),
        MEDICAL(1, "Emergencia médica"),
        TRAPPED(2, "Atrapado"),
        LOST(3, "Perdido");

        companion object {
            fun fromCode(code: Byte): EmergencyType =
                entries.firstOrNull { it.code == code } ?: GENERAL
        }
    }

    // Primer byte: versión del protocolo. Permite cambiar el formato después
    // sin romper apps viejas.
    private const val PROTOCOL_VERSION: Byte = 1

    /** Empaqueta los datos del fabricante para la baliza. */
    fun buildManufacturerData(type: EmergencyType): ByteArray {
        return byteArrayOf(PROTOCOL_VERSION, type.code)
    }

    /** Lee los datos del fabricante recibidos. Devuelve null si no es válido. */
    fun parseManufacturerData(data: ByteArray?): EmergencyType? {
        if (data == null || data.size < 2) return null
        if (data[0] != PROTOCOL_VERSION) return null
        return EmergencyType.fromCode(data[1])
    }
}
