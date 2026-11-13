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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ReferenceSearchDto
import com.bioscan.fieldterminal.data.RegimenReferenceRepository
import com.bioscan.fieldterminal.data.RegimenReferenceRow
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.LABEL_SECTION_TITLES
import com.bioscan.fieldterminal.domain.LabelMention
import com.bioscan.fieldterminal.domain.halfLifeWords
import com.bioscan.fieldterminal.domain.labelMentions
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Reference card for a medication, hormone or peptide (docs/regimen/README.md, phase 1b): looked up only when asked, from
// RxNorm and the FDA label (openFDA), or PubMed when there is no label. The card shows its source, when it was fetched and
// a link; the half-life with the label's own words; which of the person's other items the label names; the label's
// sections, each opened with a tap. Nothing here suggests a dose.

@Composable
fun RegimenReferenceSection(supplementId: Long?, name: String, others: List<Pair<Long, String>>, unit: String? = null, dose: Double? = null) {
    val repo = remember { RegimenReferenceRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var card by remember(supplementId) { mutableStateOf<RegimenReferenceRow?>(null) }
    var search by remember(supplementId) { mutableStateOf<ReferenceSearchDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(supplementId) {
        if (supplementId != null) card = catching { repo.load(supplementId) }.getOrNull()
    }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { block() } catch (e: Exception) { error = e.message ?: "Lookup failed" } finally { busy = false }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("REFERENCE", style = FTType.LabelCaps, color = FT.TextSecondary)
        if (supplementId == null) {
            Caption("Save it first: then its FDA label (or, without one, PubMed studies) can be looked up and kept here.")
            return@Column
        }
        val c = card
        val s = search
        when {
            s != null -> Candidates(
                s, busy,
                onPickLabel = { setId -> run { card = repo.saveLabel(supplementId, setId, s.rxnorm); search = null } },
                onSaveStudies = { run { card = repo.saveStudies(supplementId, s); search = null } },
                onCancel = { search = null },
            )
            c != null -> CardView(c, others, unit, dose,
                onRefresh = { run { search = repo.search(name) } },
                onRemove = { run { repo.remove(supplementId); card = null } })
            else -> {
                Caption("Look it up in RxNorm and the FDA label: interactions, warnings, how the body handles it, its half-life. " +
                    "Only this item's card is kept.")
                Action(if (busy) "LOOKING UP…" else "FIND ITS REFERENCE") { if (!busy && name.isNotBlank()) run { search = repo.search(name) } }
            }
        }
        error?.let { Text("Couldn't look it up ($it).", style = FTType.Caption, color = FT.Bad) }
    }
}

@Composable
private fun Candidates(s: ReferenceSearchDto, busy: Boolean, onPickLabel: (String) -> Unit, onSaveStudies: () -> Unit, onCancel: () -> Unit) {
    s.rxnorm?.let { Caption("RxNorm: ${it.name} (RxCUI ${it.rxcui})" + (it.ingredients.firstOrNull()?.takeIf { i -> i.name != it.name }?.let { i -> " · ingredient ${i.name}" } ?: "")) }
    when {
        s.labels.isNotEmpty() -> {
            Caption("FDA labels for \"${s.term}\". Pick the one for your product (prescription labels first):")
            s.labels.forEach { l ->
                val type = when {
                    l.productType?.contains("PRESCRIPTION") == true -> "Rx"
                    l.productType?.contains("OTC") == true -> "OTC"
                    else -> null
                }
                Column(Modifier.fillMaxWidth().ftClickable { if (!busy) onPickLabel(l.setId) }.padding(vertical = 6.dp)) {
                    Text(listOfNotNull(l.brand ?: l.generic, l.manufacturer).joinToString(" · "), style = FTType.RowTitle, color = FT.TextPrimary)
                    Text(listOfNotNull(type, l.route.joinToString("/").lowercase().ifBlank { null }, l.effectiveTime?.let { labelDate(it) }).joinToString(" · "),
                        style = FTType.MonoCaption, color = FT.TextMuted)
                }
            }
        }
        s.pubmed.isNotEmpty() -> {
            Caption("No FDA label for \"${s.term}\": it is not an approved drug. These are the most relevant PubMed studies; they are " +
                "research, not dosing guidance.")
            s.pubmed.forEach { Study(it.title, it.journal, it.year, it.url) }
            Action(if (busy) "SAVING…" else "KEEP THESE STUDIES") { if (!busy) onSaveStudies() }
        }
        else -> Caption("Nothing found for \"${s.term}\" in RxNorm, the FDA labels or PubMed. Try the generic name.")
    }
    Action("CANCEL", muted = true) { onCancel() }
}

@Composable
private fun CardView(c: RegimenReferenceRow, others: List<Pair<Long, String>>, unit: String?, dose: Double?, onRefresh: () -> Unit, onRemove: () -> Unit) {
    val uri = LocalUriHandler.current
    Text(c.title, style = FTType.RowTitle, color = FT.TextPrimary)
    val sourceName = if (c.source == "fda_label") "FDA label (openFDA)" else "PubMed"
    Caption(listOfNotNull(sourceName, c.rxcui?.let { "RxCUI $it" }, "fetched ${fetchedDay(c.retrievedAt)}", c.label?.version?.let { "label version $it" }).joinToString(" · "))
    c.url?.let { url -> Action(if (c.source == "fda_label") "OPEN THE FULL LABEL" else "OPEN ON PUBMED") { runCatching { uri.openUri(url) } } }

    c.halfLifeHours?.let { h ->
        Text("Half-life about ${halfLifeWords(h)}", style = FTType.Telemetry, color = FT.TextPrimary, modifier = Modifier.padding(top = 4.dp))
        c.halfLifeText?.let { Caption("The label: $it") }
        AmountInBodySection(c.supplementId, h, c.ingredient)
    }

    c.label?.let { label ->
        val mentions = remember(label, others) { labelMentions(label.sections, others) }
        if (mentions.isNotEmpty()) {
            Text("THE LABEL NAMES YOUR", style = FTType.Micro, color = FT.Warn, modifier = Modifier.padding(top = 4.dp))
            mentions.forEach { MentionLine(it) }
        } else if (others.isNotEmpty()) {
            Caption("None of your other items is named on this label.")
        }
        LABEL_SECTION_TITLES.forEach { (key, title) -> label.sections[key]?.let { Expandable(title, it) } }
    }
    c.studies?.let { st -> st.items.forEach { Study(it.title, it.journal, it.year, it.url) } }

    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        Action("LOOK UP AGAIN") { onRefresh() }
        Action("REMOVE", muted = true) { onRemove() }
    }
}

@Composable
private fun MentionLine(m: LabelMention) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text("${m.itemName} · in ${m.section}", style = FTType.BodySmall, color = FT.TextPrimary)
        Caption("“${m.excerpt}”")
    }
}

@Composable
private fun Expandable(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text((if (open) "▾ " else "▸ ") + title, style = FTType.BodySmall, color = FT.TextSecondary,
            modifier = Modifier.fillMaxWidth().ftClickable { open = !open }.padding(vertical = 6.dp))
        if (open) Text(text, style = FTType.Caption, color = FT.TextSecondary)
    }
}

@Composable
private fun Study(title: String, journal: String?, year: String?, url: String) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().ftClickable { runCatching { uri.openUri(url) } }.padding(vertical = 4.dp)) {
        Text(title, style = FTType.BodySmall, color = FT.TextPrimary)
        Text(listOfNotNull(journal, year).joinToString(" · "), style = FTType.MonoCaption, color = FT.TextMuted)
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = FTType.Caption, color = FT.TextMuted)

@Composable
private fun Action(text: String, muted: Boolean = false, onClick: () -> Unit) =
    Text(text, style = FTType.LabelCaps, color = if (muted) FT.TextSecondary else FT.Ok, modifier = Modifier.ftClickable(onClick = onClick).padding(vertical = 4.dp))

private fun labelDate(yyyymmdd: String): String =
    if (yyyymmdd.length >= 8) "${yyyymmdd.substring(6, 8)}/${yyyymmdd.substring(4, 6)}/${yyyymmdd.substring(0, 4)}" else yyyymmdd

private fun fetchedDay(ts: String): String = runCatching {
    OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().let { "${it.dayOfMonth} ${it.month.name.take(3).lowercase()} ${it.year}" }
}.getOrDefault(ts.take(10))
