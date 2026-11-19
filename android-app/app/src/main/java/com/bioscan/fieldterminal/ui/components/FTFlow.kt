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
import androidx.compose.runtime.remember
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

// The frame every logging and building flow uses. Full screen, three fixed parts: the header (back, title, and
// "2 / 3 · WHAT" with the step dots only in a flow that has steps, close), one page of controls, and the bottom bar
// (BACK, NEXT, SAVE). The rule (Logging Flows Redesign Plan, Nov 2026): a flow gets a new page only when the task changes
// (a choice that changes what comes next, a different action, a different moment, a check of something worked out);
// everything else stays on one page, which may scroll, with optional detail in a MORE DETAIL fold-out (FlowMore). The
// required fields sit at the top of a page (FlowEssentials marks them) and SAVE is in the bottom bar from the moment it
// is allowed, on whichever page: the fit test checks that both are visible without scrolling on a 360 × 640 dp phone.

/** One page of a flow. [optional]: a page the flow may end before (its dot is thinner); [ready]: NEXT may leave it. */
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

/**
 * A form shown inside a flow's page that has steps of its own (food: HOW, then the path): the flow's back (the header
 * arrow, the system back) asks it first. Provided by the flow that hosts forms (the + LOG sheet).
 */
val LocalFormBack = staticCompositionLocalOf<androidx.compose.runtime.MutableState<(() -> Boolean)?>?> { null }

/** A form's SAVE, docked in the hosting flow's bottom bar (so it is always on screen, however long the form). */
class FormSaveAction(internal val owner: Any, val label: String, val saving: Boolean, val enabled: Boolean, val onClick: () -> Unit)

/** Where a hosted form's SAVE goes: the flow that hosts forms (the + LOG sheet, the edit sheet) draws it in its bottom bar. */
val LocalFormSave = staticCompositionLocalOf<androidx.compose.runtime.MutableState<FormSaveAction?>?> { null }

/** The bottom bar of a flow that hosts forms: the form's SAVE when it has one, nothing on a page without one (a choice). */
@Composable
fun FormSaveBar(slot: androidx.compose.runtime.MutableState<FormSaveAction?>) {
    val a = slot.value ?: return
    Row(Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
        FTButton(if (a.saving) "SAVING…" else a.label, modifier = Modifier.weight(1f), enabled = a.enabled && !a.saving, emphasis = FTButtonEmphasis.Primary, onClick = a.onClick)
    }
}

/** While [enabled], back goes to [onBack] (one of the form's own steps) instead of leaving the form. */
@Composable
fun FormBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val slot = LocalFormBack.current ?: return
    val latest = androidx.compose.runtime.rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(slot, enabled) {
        val handler: () -> Boolean = { latest.value(); true }
        if (enabled) slot.value = handler
        onDispose { if (slot.value === handler) slot.value = null }
    }
}

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
                    // A fresh scroll per page: a page always opens at its top, the essentials first.
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
    val returning = state.returnTo != null
    Row(
        Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // BACK when there is room: with NEXT and SAVE side by side it would squeeze them (the header's arrow and the system
        // back always go back).
        val both = !returning && !last && canSave
        if (i > 0 && !returning && !both) FTButton("BACK", enabled = !saving) { state.back() }
        val save = @Composable { m: Modifier ->
            FTButton(if (saving) "SAVING…" else saveLabel, modifier = m, enabled = canSave && !saving, emphasis = FTButtonEmphasis.Primary, onClick = onSave)
        }
        when {
            returning -> FTButton("BACK TO REVIEW", modifier = Modifier.weight(1f), enabled = current.ready, emphasis = FTButtonEmphasis.Primary) { state.next() }
            last -> save(Modifier.weight(1f))
            // SAVE is offered on every page once it is allowed; the pages after it are never a gate to saving.
            canSave -> {
                FTButton("NEXT", modifier = Modifier.weight(1f), enabled = current.ready && !saving) { state.next() }
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

/** The test tag of a page's required fields: the fit test checks they show without scrolling. */
const val FLOW_ESSENTIALS_TAG = "ft_flow_essentials"

/** A page's required fields, at its top: the fit test checks that they are visible on a 360 × 640 dp phone without scrolling. */
@Composable
fun FlowEssentials(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(FLOW_ESSENTIALS_TAG), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/**
 * MORE DETAIL ▾: optional fields folded under the essentials, on the same page. Open or closed is remembered per [key]
 * (one form's fold-out), so someone who always fills them finds it open next time. [summary]: what is in it, shown folded.
 */
@Composable
fun FlowMore(key: String, label: String = "MORE DETAIL", summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("flow_more", android.content.Context.MODE_PRIVATE) }
    var open by rememberSaveable(key) { mutableStateOf(prefs.getBoolean(key, false)) }
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, shape)
                .ftClickable(shape = shape, onClickLabel = if (open) "Fold $label" else "Open $label") {
                    open = !open
                    prefs.edit().putBoolean(key, open).apply()
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = FTType.LabelCaps, color = FT.TextSecondary)
                if (!open && summary != null) Text(summary, style = FTType.Caption, color = FT.TextMuted, maxLines = 1)
            }
            FTIcon(if (open) FTIcons.Minus else FTIcons.Plus, null, tint = FT.TextSecondary, size = FTIconSize.Inline)
        }
        if (open) content()
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
 * Pick any number from a list: a grid of [columns]. Every option shows (the page scrolls when the list is long); [perPage]
 * splits it with "1 of 3 ›" only where a caller asks for that. [options] are key to label.
 */
@Composable
fun FlowPickGrid(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    columns: Int = 2,
    perPage: Int = Int.MAX_VALUE,
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

