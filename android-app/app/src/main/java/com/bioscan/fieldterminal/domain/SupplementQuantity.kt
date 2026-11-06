package com.bioscan.fieldterminal.domain

// A roster supplement has one dose size ("500 mg"); each log multiplies it. The quantity can be a multiple (2), a
// fraction (½) or zero ("not taken this time", left out of the save), so a stack can be logged with items skipped.
const val SUPPLEMENT_QTY_STEP = 0.25

/** "1", "½", "1¾", "0.3" -- quarters as fraction glyphs, anything else as a plain decimal. */
fun formatSupplementQty(qty: Double): String {
    val whole = Math.floor(qty).toInt()
    val frac = qty - whole
    val glyph = when {
        Math.abs(frac) < 1e-9 -> ""
        Math.abs(frac - 0.25) < 1e-9 -> "¼"
        Math.abs(frac - 0.5) < 1e-9 -> "½"
        Math.abs(frac - 0.75) < 1e-9 -> "¾"
        else -> return String.format(java.util.Locale.US, "%.2f", qty).trimEnd('0').trimEnd('.')
    }
    return when {
        glyph.isEmpty() -> "$whole"
        whole == 0 -> glyph
        else -> "$whole$glyph"
    }
}
