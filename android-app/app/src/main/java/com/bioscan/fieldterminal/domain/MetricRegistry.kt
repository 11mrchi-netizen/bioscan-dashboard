package com.bioscan.fieldterminal.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Analysis Layer 3 / Phase 7: methodology as data. The single source is supabase/functions/_shared/metric-registry/
// metric-registry.json (validated by Node tests against docs/analysis-layer-2/02-metric-registry.md); the Gradle build
// packages that file as an asset so the app shows exactly the text the console shows.
@Serializable
data class MetricReference(val citation: String, val doi: String? = null)

@Serializable
data class MetricMethodology(
    val key: String,
    val docName: String? = null,
    val kind: String,
    val methodology: String,
    val limitations: List<String> = emptyList(),
    val references: List<MetricReference> = emptyList(),
    val productDefined: Boolean = false,
    val methodologyVersion: String,
)

@Serializable
data class MetricRegistryFile(val registryVersion: String, val metrics: List<MetricMethodology>)

private val registryJson = Json { ignoreUnknownKeys = true }

fun parseMetricRegistry(json: String): Map<String, MetricMethodology> =
    registryJson.decodeFromString<MetricRegistryFile>(json).metrics.associateBy { it.key }

// Same layout as methodologyText() in _shared/analysis-core/metricRegistry.ts.
fun MetricMethodology.helpText(): String = buildList {
    add(methodology)
    if (productDefined) add("Product-defined: not clinically validated.")
    if (limitations.isNotEmpty()) add("Limits:\n" + limitations.joinToString("\n") { "- $it" })
    if (references.isNotEmpty()) {
        add("References:\n" + references.joinToString("\n") { r -> "- ${r.citation}${r.doi?.let { " doi:$it" } ?: ""}" })
    }
    add("Methodology v$methodologyVersion")
}.joinToString("\n\n")
