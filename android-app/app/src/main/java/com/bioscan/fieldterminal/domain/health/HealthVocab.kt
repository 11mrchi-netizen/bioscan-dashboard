package com.bioscan.fieldterminal.domain.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Health concerns: the region and symptom vocabulary, packaged as the asset health-vocab.json from
// supabase/functions/_shared/health-vocab (one copy shared with the analysis core and the migration's seed). Codes are
// never reused; the analysis core validates the file (healthVocab.ts) and HealthVocabTest checks this parser on it.

@Serializable
data class VocabQuestion(
    val id: String,
    val text: String,
    val type: String,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
)

@Serializable
data class VocabRegion(
    val code: String,
    val label: String,
    val group: String,
    val sided: Boolean,
    val tissue: String,
    val muscles: List<String> = emptyList(),
    val followups: List<String> = emptyList(),
)

@Serializable
data class VocabSymptom(
    val code: String,
    val label: String,
    val scope: String,
    val group: String? = null,
    val followups: List<String> = emptyList(),
)

@Serializable
data class HealthVocab(
    val vocabVersion: String,
    val questions: List<VocabQuestion>,
    val regions: List<VocabRegion>,
    val symptoms: List<VocabSymptom>,
    val exerciseMuscles: List<String> = emptyList(),
) {
    fun region(code: String?): VocabRegion? = regions.find { it.code == code }
    fun symptom(code: String?): VocabSymptom? = symptoms.find { it.code == code }
    fun question(id: String): VocabQuestion? = questions.find { it.id == id }

    val localSymptoms: List<VocabSymptom> get() = symptoms.filter { it.scope == "local" || it.scope == "any" }
    val systemicSymptoms: List<VocabSymptom> get() = symptoms.filter { it.scope == "systemic" || it.scope == "any" }

    /** The follow-up questions for one region and symptom, region's first, at most [limit] (owner brief: few taps). */
    fun followupsFor(regionCode: String?, symptomCode: String?, limit: Int = 2): List<VocabQuestion> =
        ((region(regionCode)?.followups ?: emptyList()) + (symptom(symptomCode)?.followups ?: emptyList()))
            .distinct()
            .mapNotNull { question(it) }
            .take(limit)

    /** "R KNEE", "LOWER BACK", for pins and titles. */
    fun regionLabel(code: String?, side: String?): String {
        val r = region(code) ?: return code?.uppercase() ?: ""
        val prefix = when (side) { "right" -> "R "; "left" -> "L "; "both" -> "BOTH "; else -> "" }
        return prefix + r.label.uppercase()
    }

    /** A title prefilled for a new concern: "Right knee", "Sore throat". */
    fun defaultTitle(regionCode: String?, side: String?, symptomCode: String?): String {
        val r = region(regionCode)
        if (r != null) {
            val s = when (side) { "right" -> "Right "; "left" -> "Left "; "both" -> "Both "; else -> "" }
            return (s + if (s.isEmpty()) r.label else r.label.lowercase()).take(80)
        }
        return (symptom(symptomCode)?.label ?: "Symptom").take(80)
    }
}

private val vocabJson = Json { ignoreUnknownKeys = true }

fun parseHealthVocab(text: String): HealthVocab = vocabJson.decodeFromString(HealthVocab.serializer(), text)
