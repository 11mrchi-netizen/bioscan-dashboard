package com.bioscan.fieldterminal.domain

// DAV-362. One canonical stored form per barcode so a scan and a typed entry find
// the same product: digits only; an EAN-13 that is just a UPC-A with a leading 0
// is stored as the 12-digit UPC-A. Anything that is not an EAN-8/UPC-A/EAN-13/GTIN-14
// length is rejected (null) rather than guessed.
fun normalizeBarcode(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    val canon = if (digits.length == 13 && digits.startsWith("0")) digits.drop(1) else digits
    return canon.takeIf { it.length in setOf(8, 12, 13, 14) }
}
