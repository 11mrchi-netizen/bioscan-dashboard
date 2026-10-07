package com.bioscan.fieldterminal.domain

// DAV-360. One barcode can map to several provider listings (SuppCo returned 4
// listings for one multivitamin UPC, differing only in naming era). Choose the
// listing whose name best overlaps the user's own product name; ties prefer an
// on-market listing, then the first. Pure and generic so it stays independent of
// the provider models. Returns null for an empty list.
fun <T> pickBestListing(
    items: List<T>,
    productName: String,
    name: (T) -> String?,
    offMarket: (T) -> Boolean?,
): T? {
    if (items.isEmpty()) return null
    val target = nameTokens(productName)

    fun score(item: T): Double {
        val candidate = nameTokens(name(item) ?: "")
        if (target.isEmpty() || candidate.isEmpty()) return 0.0
        return target.intersect(candidate).size.toDouble() / target.union(candidate).size
    }

    return items.withIndex()
        .maxWithOrNull(
            compareBy<IndexedValue<T>>({ score(it.value) }, { if (offMarket(it.value) == false) 1 else 0 })
                .thenBy { -it.index },
        )?.value
}

private fun nameTokens(s: String): Set<String> =
    normalizeIngredientName(s).split(' ').filter { it.isNotEmpty() }.toSet()
