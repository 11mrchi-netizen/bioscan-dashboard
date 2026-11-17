package com.bioscan.fieldterminal.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The frame every logging and building flow uses (owner's request, Nov 2026: no page that needs scrolling, a multi-page
// flow instead). Full screen, three fixed parts: the header (back, title, "2 / 4 · WHAT", the step dots, close), one
// page of controls, and the bottom bar (BACK, NEXT / ADD DETAIL, SAVE). Each page is sized to fit a 360 × 640 dp phone;
// the body can still scroll as a last resort (a large font, the keyboard up), never by design. Optional pages come last
// and SAVE is offered as soon as the required pages are done, so the extras can always be skipped.

/** One page of a flow. [optional]: an ADD DETAIL page (SAVE is already offered before it); [ready]: NEXT may leave it. */
data class FlowPage(val label: String, val optional: Boolean = false, val ready: Boolean = true)

/** Where a flow is: the page shown, the furthest page reached (its dots open with a tap) and the review page to return to. */
@Stable
class FlowState(page: Int = 0, furthest: Int = 0, returnTo: Int? = null) {
    var page by mutableIntStateOf(page)
        private set
    var furthest by mutableIntStateOf(furthest)
        private set
    /** Set when a review page sends the person back to change something: NEXT reads BACK TO REVIEW and returns there. */
    var returnTo by mutableStateOf(returnTo)
        private set

    fun next() {
        val back = returnTo
        if (back != null && back > page) { page = back; returnTo = null } else page += 1
        furthest = maxOf(furthest, page)
    }

    fun back(): Boolean {
        if (page == 0) return false
        returnTo = null
        page -= 1
        return true
    }

    /** Jump to [target]; from a later page (a review line, a dot), coming back with NEXT returns here. */
    fun goTo(target: Int) {
        if (target == page) return
        returnTo = if (target < page) page else null
        page = target
        furthest = maxOf(furthest, page)
    }

    /** Pages can disappear when an earlier answer changes (a branch): keep the position inside the list. */
    internal fun clamp(size: Int) {
        if (page > size - 1) page = (size - 1).coerceAtLeast(0)
        if (furthest > size - 1) furthest = (size - 1).coerceAtLeast(0)
        if ((returnTo ?: 0) > size - 1) returnTo = null
    }

    companion object {
        val Saver = listSaver<FlowState, Int>(
            save = { listOf(it.page, it.furthest, it.returnTo ?: -1) },
            restore = { FlowState(it[0], it[1], it[2].takeIf { r -> r >= 0 }) },
        )
    }
}

@Composable
fun rememberFlowState(): FlowState {
    val start = LocalFlowStartPage.current
    return rememberSaveable(saver = FlowState.Saver) { FlowState(start, start) }
}

/** The page a new flow opens on: 0 in the app; the fit test opens each page in turn. */
val LocalFlowStartPage = staticCompositionLocalOf { 0 }

/** Draw flows in place instead of in a dialog (the screenshot tests). */
val LocalInlineFlows = staticCompositionLocalOf { false }

/** The body's test tag: the fit test checks that no page of any flow scrolls at 360 × 640 dp. */
const val FLOW_BODY_TAG = "ft_flow_body"

@Composable
fun FTFlow(
    title: String,
    pages: List<FlowPage>,
    state: FlowState,
    onClose: () -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    saving: Boolean = false,
    saveLabel: String = "SAVE",
    wash: Color = FT.DomainLog,
    error: String? = null,
    /** Replaces the bottom bar on a page that ends the flow its own way (a result page, a page of tiles). */
    bottomBar: (@Composable (Int) -> Unit)? = null,
    /** A flow that walks its own steps (the body check-in's sites): back goes here first; true when it went back. */
    onBack: (() -> Boolean)? = null,
    /** Replaces "2 / 4 · WHAT" under the title (a flow with its own step count). */
    subtitle: String? = null,
    content: @Composable ColumnScope.(Int) -> Unit,
) {
    state.clamp(pages.size)
    val goBack: () -> Unit = { if (onBack?.invoke() != true && !state.back()) onClose() }
    val frame = @Composable {
        Box(Modifier.fillMaxSize().background(FT.Base).ftDomainWash(wash)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                FlowHeader(title, pages, state, subtitle, goBack, onClose)
                key(state.page) {
                    // A fresh scroll per page: a page always opens at its top (and is built not to need it at all).
                    val scroll = rememberScrollState()
                    Column(
                        Modifier.weight(1f).fillMaxWidth().testTag(FLOW_BODY_TAG).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content(state.page)
                        error?.let { Text(it, style = FTType.BodySmall, color = FT.Bad) }
                    }
                }
                if (bottomBar != null) bottomBar(state.page) else FlowBottomBar(pages, state, canSave, saving, saveLabel, onSave)
            }
        }
    }
    BackHandler(enabled = !LocalInlineFlows.current, onBack = goBack)
    if (LocalInlineFlows.current) frame() else {
        Dialog(onDismissRequest = goBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            frame()
        }
    }
}

@Composable
private fun FlowHeader(title: String, pages: List<FlowPage>, state: FlowState, subtitle: String?, goBack: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FTIconButton(FTIcons.Back, "Back", goBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = FTType.LabelCaps, color = FT.TextPrimary, maxLines = 1)
                if (subtitle != null) {
                    Text(subtitle, style = FTType.MonoCaption, color = FT.TextSecondary, maxLines = 1)
                } else if (pages.size > 1) {
                    Text("${state.page + 1} / ${pages.size} · ${pages[state.page].label}", style = FTType.MonoCaption, color = FT.TextSecondary, maxLines = 1)
                }
            }
            FTIconButton(FTIcons.Close, "Close", onClose)
        }
        if (pages.size > 1) {
            // The steps as segments: the current one lit, the ones walked open with a tap, optional ones dashed thinner.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pages.forEachIndexed { i, p ->
                    val reached = i <= state.furthest
                    val shape = RoundedCornerShape(50)
                    val color = when {
                        i == state.page -> FT.PlatinumSolid
                        reached -> FT.TextSecondary
                        else -> FT.GlassBorder
                    }
                    Box(
                        Modifier.weight(1f).height(if (p.optional) 3.dp else 4.dp).background(color, shape)
                            .semantics { contentDescription = "Step ${i + 1}, ${p.label}" }
                            .ftClickable(enabled = reached && i != state.page, shape = shape) { state.goTo(i) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowBottomBar(pages: List<FlowPage>, state: FlowState, canSave: Boolean, saving: Boolean, saveLabel: String, onSave: () -> Unit) {
    val i = state.page
    val last = i >= pages.lastIndex
    val current = pages.getOrNull(i) ?: FlowPage("")
    val nextOptional = !last && pages[i + 1].optional
    val returning = state.returnTo != null
    Row(
        Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // BACK here too when there is room (the header's arrow and the system back always go back); with ADD DETAIL
        // and SAVE side by side it would squeeze them onto two lines.
        val threeWide = !returning && !last && (nextOptional || current.optional)
        if (i > 0 && !returning && !threeWide) FTButton("BACK", enabled = !saving) { state.back() }
        val save = @Composable { m: Modifier ->
            FTButton(if (saving) "SAVING…" else saveLabel, modifier = m, enabled = canSave && !saving, emphasis = FTButtonEmphasis.Primary, onClick = onSave)
        }
        when {
            returning -> FTButton("BACK TO REVIEW", modifier = Modifier.weight(1f), enabled = current.ready, emphasis = FTButtonEmphasis.Primary) { state.next() }
            last -> save(Modifier.weight(1f))
            nextOptional || current.optional -> {
                // The rest is optional: SAVE now, or go on to the extras.
                FTButton(if (current.optional) "NEXT" else "ADD DETAIL", modifier = Modifier.weight(1f), enabled = current.ready && !saving) { state.next() }
                save(Modifier.weight(1f))
            }
            else -> FTButton("NEXT", modifier = Modifier.weight(1f), enabled = current.ready, emphasis = FTButtonEmphasis.Primary) { state.next() }
        }
    }
}

// ---- parts flows share ----

/** A page's lead question: one sentence on what this page asks. */
@Composable
fun FlowQuestion(text: String, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = FTType.RowTitle, color = FT.TextPrimary)
        if (hint != null) Text(hint, style = FTType.BodySmall, color = FT.TextSecondary)
    }
}

/** A labelled group on a page. */
@Composable
fun FlowField(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = FTType.LabelCaps, color = FT.TextMuted)
        content()
    }
}

/** One line of a review page: what, the answer, and a tap back to the page that asked it. */
@Composable
fun FlowReviewLine(label: String, value: String, warn: Boolean = false, onEdit: (() -> Unit)?) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Row(
        Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, shape)
            .then(if (onEdit != null) Modifier.ftClickable(shape = shape, onClick = onEdit) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.weight(0.38f))
        Text(value, style = FTType.BodySmall, color = if (warn) FT.Warn else FT.TextPrimary, modifier = Modifier.weight(0.62f), maxLines = 2)
        if (onEdit != null) Text("EDIT", style = FTType.Micro, color = FT.TextSecondary)
    }
}

/** "2 of 5" with PREVIOUS / NEXT for a list paged one item at a time (foods in a photo, aid stations, claims). */
@Composable
fun FlowPager(index: Int, count: Int, onIndex: (Int) -> Unit, what: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FTButton("‹", enabled = index > 0) { onIndex(index - 1) }
        Text(
            if (count == 0) "None yet" else "${index + 1} of $count${if (what.isNotEmpty()) " $what" else ""}",
            style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.weight(1f),
        )
        FTButton("›", enabled = index < count - 1) { onIndex(index + 1) }
    }
}

/** A short gap that pushes what follows to the page's foot (the body is a column with spacing). */
@Composable
fun ColumnScope.FlowSpacer() = Spacer(Modifier.weight(1f, fill = false))

/**
 * Pick any number from a list that may be long: a grid of [columns], [perPage] at a time, with "1 of 3 ›" under it when
 * there is more than one page (a list never scrolls). [options] are key to label; the count picked shows on every page.
 */
@Composable
fun FlowPickGrid(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    columns: Int = 2,
    perPage: Int = 12,
    single: Boolean = false,
) {
    val pages = options.chunked(perPage.coerceAtLeast(1))
    var index by rememberSaveable(options.size) { mutableIntStateOf(0) }
    val shown = pages.getOrNull(index.coerceIn(0, (pages.size - 1).coerceAtLeast(0))).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (key, label) -> FlowTile(label, key in selected, Modifier.weight(1f), multi = !single) { onToggle(key) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (pages.size > 1) {
            FlowPager(index, pages.size, { index = it }, what = if (!single && selected.isNotEmpty()) "· ${selected.size} picked" else "")
        }
    }
}

/** One tile of a flow's choice grid: 48 dp tall, platinum when picked. */
@Composable
fun FlowTile(label: String, selected: Boolean, modifier: Modifier = Modifier, multi: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected }
            .border(FT.BorderWidth, if (selected) Color.Transparent else FT.GlassBorder, shape)
            .background(if (selected) FT.PlatinumSolid else FT.GlassFill, shape)
            .ftClickable(shape = shape, role = if (multi) Role.Checkbox else Role.RadioButton, onClickLabel = label, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = FTType.MonoCaption, color = if (selected) FT.PlatinumInk else FT.TextSecondary, textAlign = TextAlign.Center, maxLines = 2)
    }
}
