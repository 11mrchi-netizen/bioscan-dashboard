package com.bioscan.fieldterminal.data

import kotlinx.serialization.Serializable

// Shared generateContent REST response shape -- every Gemini text/JSON call
// in this app (NutritionEstimationRepository, SupplementImpactRepository)
// parses the same envelope, so it's declared once here instead of as a
// private duplicate per file (which collides: Kotlin's `private` on a
// top-level class only restricts where it's referenced from, not its
// package-level name, so two files can't each declare their own private
// GeminiResponse in the same package).
@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
data class GeminiCandidate(val content: GeminiContent? = null)

@Serializable
data class GeminiContent(val parts: List<GeminiPart> = emptyList())

@Serializable
data class GeminiPart(val text: String? = null)

@Serializable
data class GeminiErrorResponse(val error: GeminiError? = null)

@Serializable
data class GeminiError(val message: String? = null)
