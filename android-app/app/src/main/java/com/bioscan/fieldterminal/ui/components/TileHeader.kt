package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The one back header for every pushed screen (contract A4): back arrow, the section's domain dot, a title and a context
// line, so a pushed page always says where you are. Before the UX review most tile and settings pages passed no title and
// showed a bare arrow, because "the sub-tab rail says where you are" -- but LOAD / PLAN / INJURY never named the section.
@Composable
// App restructure: [action] sits at the header's end (Today's "ADD ABOUT LAST EVENING").
fun TileHeader(onBack: () -> Unit, title: String? = null, subtitle: String? = null, accent: Color? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = FT.TextSecondary)
        }
        if (title != null) {
            // Domain accents identify a section (contract §4); the dot is identity only, never state.
            accent?.let { Box(Modifier.padding(start = 2.dp, end = 6.dp).size(8.dp).clip(CircleShape).background(it)) }
            Column(modifier = Modifier.weight(1f, fill = action != null).padding(start = 4.dp, end = 12.dp)) {
                Text(title, style = FTType.SectionTitle, color = FT.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = FTType.MonoCaption, color = FT.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        action?.let { Box(Modifier.padding(end = 6.dp)) { it() } }
    }
}

// A pushed page whose header and (optional) sub-tab rail stay put while only `content` scrolls (contract A4). Pages with a
// rail pass their own `scrollState` and scroll it to the top when the tab changes.
@Composable
fun PinnedPage(
    onBack: () -> Unit,
    title: String,
    subtitle: String? = null,
    accent: Color? = null,
    rail: (@Composable () -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    // Sits in the header's right corner (the global range dropdown on Training, Nutrition and Health).
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack, title = title, subtitle = subtitle, accent = accent, action = headerAction)
        if (rail != null) {
            rail()
            Box(Modifier.fillMaxWidth().height(FT.BorderWidth).background(FT.GlassBorder))
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState), content = content)
    }
}
