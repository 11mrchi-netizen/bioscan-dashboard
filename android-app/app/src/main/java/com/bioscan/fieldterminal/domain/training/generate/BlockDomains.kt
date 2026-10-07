package com.bioscan.fieldterminal.domain.training.generate

// DAV-345. A block's domain composition, derived from what was generated (architecture 3.3b):
// the domain with the most sessions is primary, the next secondary, and any other domain
// with a real share is maintenance. Shown to the user for confirmation, never imposed.

data class BlockDomain(val domain: String, val role: String, val sessions: Int)

fun deriveBlockDomains(sessions: List<GeneratedSession>, maintenanceShare: Double = 0.10): List<BlockDomain> {
    val counted = sessions.filter { it.domain != "recovery" && it.workKind != "test" }
    if (counted.isEmpty()) return emptyList()
    val byDomain = counted.groupingBy { it.domain }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    return byDomain.mapIndexedNotNull { i, (domain, n) ->
        when {
            i == 0 -> BlockDomain(domain, "primary", n)
            i == 1 -> BlockDomain(domain, "secondary", n)
            n.toDouble() / counted.size >= maintenanceShare -> BlockDomain(domain, "maintenance", n)
            else -> null
        }
    }
}
