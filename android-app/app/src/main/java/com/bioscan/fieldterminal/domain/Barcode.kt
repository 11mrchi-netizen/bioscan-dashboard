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

/**
 * A scanned barcode that no database knows is read a second time before a product is created under it. The scanner can
 * read an EAN-13 as a different, still valid UPC-A (4713057770715 was once read as 753019770715): the product was then
 * created under the wrong number, and the next scan, read right, did not find it. Two reads that agree are the number
 * printed on the package; a second read that differs is looked up as usual, and usually finds the product.
 */
class UnknownScanCheck {
    private var pending: String? = null

    /** True when [barcode] is the same unknown number as the scan before it: it was read right. */
    fun confirmed(barcode: String): Boolean = (pending == barcode).also { pending = if (it) null else barcode }

    fun clear() {
        pending = null
    }

    companion object {
        fun askAgain(barcode: String) =
            "No product is filed under $barcode. Scan it once more to make sure the number was read right; then you can add it."
    }
}
