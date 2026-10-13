package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.MetricMethodology
import com.bioscan.fieldterminal.domain.parseMetricRegistry

// Loads the packaged metric registry (asset metric-registry.json) once. A missing or unreadable asset yields an empty map,
// so a help button simply shows its own text instead of failing a screen.
object MetricRegistryStore {
    @Volatile private var cached: Map<String, MetricMethodology>? = null

    fun get(context: Context, key: String): MetricMethodology? = all(context)[key]

    private fun all(context: Context): Map<String, MetricMethodology> =
        cached ?: synchronized(this) {
            cached ?: runCatching {
                context.applicationContext.assets.open("metric-registry.json").bufferedReader().use { parseMetricRegistry(it.readText()) }
            }.getOrDefault(emptyMap()).also { cached = it }
        }
}
