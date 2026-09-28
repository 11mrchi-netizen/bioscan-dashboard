package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkPopulationJson(
    @SerialName("sampling_frame") val samplingFrame: String,
    val geography: String? = null,
    @SerialName("age_low") val ageLow: Int? = null,
    @SerialName("age_high") val ageHigh: Int? = null,
    val sex: String? = null,
)

@Serializable
data class ThresholdBandJson(val name: String, val min: Double? = null, val max: Double? = null)

// Only meaningful when artifact_type == "threshold_range" -- a future
// reference_equation artifact stores a differently-shaped `equation` blob
// this model doesn't decode yet; adding a real polymorphic decode is
// deferred until a second real artifact_type actually needs it.
@Serializable
data class ThresholdBandsJson(val bands: List<ThresholdBandJson> = emptyList())

@Serializable
data class BenchmarkArtifactRow(
    val id: Long,
    val metric: String,
    @SerialName("artifact_type") val artifactType: String,
    @SerialName("source_name") val sourceName: String,
    @SerialName("source_tier") val sourceTier: String,
    val population: BenchmarkPopulationJson,
    @SerialName("sample_size") val sampleSize: Int? = null,
    // jsonb object keys are always strings on the wire -- BenchmarkArtifactRepository
    // converts to Map<Int, Double> when building the domain BenchmarkArtifact.
    val quantiles: Map<String, Double>? = null,
    val equation: ThresholdBandsJson? = null,
    val directionality: String,
    @SerialName("supports_percentile") val supportsPercentile: Boolean,
    @SerialName("artifact_version") val artifactVersion: Int,
    @SerialName("superseded_by") val supersededBy: Long? = null,
)
