package com.bioscan.fieldterminal.domain

// DAV-362. The product-level facts a provider can supply besides ingredients. Used
// by the review-and-apply step: proposals are only the facts where the provider
// states something that differs from what the user already has. Nothing is applied
// until the user picks a field.
enum class FactField(val label: String) {
    ServingSize("Serving size"),
    ServingsPerContainer("Servings per container"),
    Format("Format"),
    SuggestedUse("Suggested use"),
}

data class ProductFacts(
    val servingSize: Double? = null,
    val servingUnit: String? = null,
    val servingForm: String? = null, // format: capsule, tablet, powder...
    val servingsPerContainer: Double? = null,
    val suggestedUse: String? = null,
)

data class FactProposal(val field: FactField, val yours: String?, val theirs: String)

fun proposeFacts(yours: ProductFacts, theirs: ProductFacts): List<FactProposal> = buildList {
    fun num(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
    fun serving(f: ProductFacts) = f.servingSize?.let { "${num(it)}${f.servingUnit?.let { u -> " $u" } ?: ""}" }

    val theirServing = serving(theirs)
    if (theirServing != null && theirServing != serving(yours)) add(FactProposal(FactField.ServingSize, serving(yours), theirServing))

    val theirPer = theirs.servingsPerContainer
    if (theirPer != null && theirPer != yours.servingsPerContainer) {
        add(FactProposal(FactField.ServingsPerContainer, yours.servingsPerContainer?.let(::num), num(theirPer)))
    }
    theirs.servingForm?.trim()?.takeIf { it.isNotEmpty() && !it.equals(yours.servingForm?.trim(), ignoreCase = true) }
        ?.let { add(FactProposal(FactField.Format, yours.servingForm, it)) }
    theirs.suggestedUse?.trim()?.takeIf { it.isNotEmpty() && it != yours.suggestedUse?.trim() }
        ?.let { add(FactProposal(FactField.SuggestedUse, yours.suggestedUse, it)) }
}
