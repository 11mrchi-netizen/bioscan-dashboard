package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.EvidenceReviewItem
import com.bioscan.fieldterminal.data.InteractionReviewItem
import com.bioscan.fieldterminal.data.RegistryReviewRepository
import com.bioscan.fieldterminal.data.RegistryReviewRow
import com.bioscan.fieldterminal.data.ReviewState
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.reviewState
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.today.FullScreenOverlay
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId

// Review of the shared registries (evidence_records: what an ingredient is expected to do; interaction_records: pairs to
// watch). Every row was seeded from a cited source and marked unreviewed. Here the owner reads each claim with its
// source and either confirms it (the app then shows it as reviewed) or disputes it with a note (an interaction keeps its
// flag with the note; a disputed outcome claim is no longer shown). Unreviewed rows first.

private data class Entry(
    val registry: String,
    val id: Long,
    val version: Int?,
    val seedStatus: String,
    val title: String,
    val body: String,
    val details: List<String>,
    val citation: String?,
    val url: String?,
)

private fun citationUrl(pmidDoi: String?, fallback: String?): String? {
    val c = pmidDoi?.trim().orEmpty()
    return when {
        c.matches(Regex("""(?i)pmid:?\s*\d+""")) -> "https://pubmed.ncbi.nlm.nih.gov/${c.filter(Char::isDigit)}/"
        c.matches(Regex("""\d{5,9}""")) -> "https://pubmed.ncbi.nlm.nih.gov/$c/"
        c.startsWith("10.") -> "https://doi.org/$c"
        c.startsWith("http") -> c
        else -> fallback
    }
}

private fun EvidenceReviewItem.entry() = Entry(
    "evidence", id, version, reviewStatus,
    title = subjectKey.replace('_', ' ').replaceFirstChar { it.uppercase() } + (outcomeKey?.let { " → ${it.replace('_', ' ')}" } ?: ""),
    body = claimText,
    details = listOfNotNull(
        effectDirection?.let { "Direction: $it" }, population?.let { "In: $it" }, doseRange?.let { "Dose studied: $it" },
        listOfNotNull(studyDesign, evidenceTier?.let { "tier $it" }).joinToString(", ").ifBlank { null }, caveats?.let { "Caveats: $it" },
    ),
    citation = listOfNotNull(source, pmidDoi).joinToString(" · ").ifBlank { null },
    url = citationUrl(pmidDoi, null),
)

private fun InteractionReviewItem.entry() = Entry(
    "interaction", id, version, reviewStatus,
    title = "${(aAliases.ifEmpty { aKeys }).firstOrNull() ?: "?"} + ${(bAliases.ifEmpty { bKeys }).firstOrNull() ?: "?"}",
    body = effect,
    details = listOfNotNull(
        advice.ifBlank { null }?.let { "Advice: $it" }, mechanism?.let { "Mechanism: $it" }, severity?.let { "Severity: $it" },
        listOfNotNull(studyDesign, evidenceTier?.let { "tier $it" }).joinToString(", ").ifBlank { null }, caveats?.let { "Caveats: $it" },
    ),
    citation = listOfNotNull(source, pmidDoi).joinToString(" · ").ifBlank { null },
    url = citationUrl(pmidDoi, sourceUrl),
)

@Composable
fun RegistryReviewScreen(onClose: () -> Unit) {
    val repo = remember { RegistryReviewRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var reviews by remember { mutableStateOf<Map<Pair<String, Long>, RegistryReviewRow>>(emptyMap()) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        try {
            reviews = repo.loadReviews()
            entries = repo.loadInteractions().map { it.entry() } + repo.loadEvidence().map { it.entry() }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load"
        }
    }

    val list = entries
    val stateOf = { e: Entry -> reviewState(e.seedStatus, e.version, reviews[e.registry to e.id]) }
    val open = list?.count { stateOf(it) == ReviewState.UNREVIEWED } ?: 0
    FullScreenOverlay(title = "REVIEW SOURCES", onClose = onClose, trailing = list?.let { "$open TO REVIEW" }) {
        Text(
            "Each claim below came from the cited study or label and is shown in the app as unreviewed until you read it. " +
                "Confirm it if the source says what the claim says; dispute it with a note if not.",
            style = FTType.Caption, color = FT.TextMuted,
        )
        error?.let { Text(it, style = FTType.Caption, color = FT.Critical) }
        if (list == null) return@FullScreenOverlay
        val sorted = list.sortedBy { stateOf(it) != ReviewState.UNREVIEWED }
        for ((title, reg) in listOf("INTERACTIONS" to "interaction", "EXPECTED EFFECTS" to "evidence")) {
            val rows = sorted.filter { it.registry == reg }
            if (rows.isEmpty()) continue
            Text(title, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
            rows.forEach { e ->
                ReviewCard(e, stateOf(e), reviews[e.registry to e.id],
                    onVerdict = { verdict, note ->
                        scope.launch {
                            runCatching { repo.setVerdict(e.registry, e.id, e.version, verdict, note) }
                                .onSuccess { reload++ }.onFailure { error = it.message }
                        }
                    },
                    onClear = {
                        scope.launch { runCatching { repo.clear(e.registry, e.id) }.onSuccess { reload++ }.onFailure { error = it.message } }
                    })
            }
        }
    }
}

@Composable
private fun ReviewCard(e: Entry, state: ReviewState, review: RegistryReviewRow?, onVerdict: (String, String?) -> Unit, onClear: () -> Unit) {
    val uri = LocalUriHandler.current
    var disputing by remember { mutableStateOf(false) }
    var note by remember(review) { mutableStateOf(review?.note.orEmpty()) }
    val stateText = when (state) {
        ReviewState.UNREVIEWED -> "UNREVIEWED"
        ReviewState.CONFIRMED -> if (review == null) "REVIEWED" else "CONFIRMED ${reviewDay(review.reviewedAt)}"
        ReviewState.DISPUTED -> "DISPUTED ${review?.let { reviewDay(it.reviewedAt) }.orEmpty()}".trim()
    }
    Section(e.title, trailing = stateText) {
        Text(e.body, style = FTType.BodySmall, color = FT.TextPrimary)
        e.details.forEach { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
        e.citation?.let { c ->
            Text(
                "Source: $c" + if (e.url != null) "  ›" else "",
                style = FTType.Caption, color = if (e.url != null) FT.Emerald else FT.TextMuted,
                modifier = Modifier.ftClickable(enabled = e.url != null) { e.url?.let { runCatching { uri.openUri(it) } } },
            )
        }
        if (state == ReviewState.DISPUTED) review?.note?.let { Text("Your note: $it", style = FTType.Caption, color = FT.Warning) }
        if (disputing) {
            FieldTextField(note, { note = it }, "What the source actually says", singleLine = false)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Act("SAVE DISPUTE") { onVerdict("disputed", note); disputing = false }
                Act("CANCEL", muted = true) { disputing = false }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                if (state != ReviewState.CONFIRMED || review == null) Act("CONFIRM") { onVerdict("confirmed", null) }
                Act("DISPUTE", muted = true) { disputing = true }
                if (review != null) Act("CLEAR", muted = true) { onClear() }
            }
        }
    }
}

@Composable
private fun Act(text: String, muted: Boolean = false, onClick: () -> Unit) =
    Text(text, style = FTType.LabelCaps, color = if (muted) FT.TextSecondary else FT.Emerald,
        modifier = Modifier.ftClickable(onClick = onClick).padding(vertical = 6.dp))

private fun reviewDay(ts: String): String = runCatching {
    OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().let { "${it.dayOfMonth} ${it.month.name.take(3)}" }
}.getOrDefault("")
