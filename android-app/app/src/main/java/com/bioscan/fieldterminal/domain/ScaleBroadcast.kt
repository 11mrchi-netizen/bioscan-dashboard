package com.bioscan.fieldterminal.domain

// The body scale's passive BLE advertisement (Chipsea / OKOK family, company id 0x20CA). No connection or pairing: the scale
// puts weight and impedance in its manufacturer data. Layout of the 19 bytes after the company id, from two captured frames:
//   0..4   0B 12 12 12 12   header
//   5..6   01 xx            xx unknown (01 after a weigh-in)
//   7      5D               unknown (flag or counter)
//   8..9   weight, big-endian, 0.1 kg   (02 EE = 75.0 kg, confirmed)
//   10..11 impedance, big-endian, 0.1 ohm (13 A6 = 503.0 ohm, plausible, not yet checked against OKOK)
//   12     checksum: XOR of bytes 0..11, XOR 0x20
//   13..18 the scale's own MAC address
// An idle frame has bytes 6..11 all zero, so a reading needs a non-zero weight and impedance.

const val SCALE_COMPANY_ID = 0x20CA
private const val SCALE_PAYLOAD_SIZE = 19
private val SCALE_HEADER = byteArrayOf(0x0B, 0x12, 0x12, 0x12, 0x12)

data class ScaleReading(val weightKg: Double, val impedanceOhm: Double, val mac: String, val rawHex: String)

/** [payload] is the manufacturer data without the company id. Null for any frame that is not a finished weigh-in. */
fun decodeScaleBroadcast(payload: ByteArray): ScaleReading? {
    if (payload.size != SCALE_PAYLOAD_SIZE) return null
    if (!payload.copyOfRange(0, SCALE_HEADER.size).contentEquals(SCALE_HEADER)) return null
    val check = payload.take(12).fold(0x20) { acc, b -> acc xor b.toInt() } and 0xFF
    if (check != (payload[12].toInt() and 0xFF)) return null
    val weight = u16(payload, 8)
    val impedance = u16(payload, 10)
    if (weight == 0 || impedance == 0) return null
    val kg = weight / 10.0
    if (kg !in 5.0..400.0) return null
    return ScaleReading(
        weightKg = kg,
        impedanceOhm = impedance / 10.0,
        mac = payload.copyOfRange(13, 19).joinToString(":") { "%02X".format(it) },
        rawHex = payload.joinToString("") { "%02X".format(it) },
    )
}

private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
