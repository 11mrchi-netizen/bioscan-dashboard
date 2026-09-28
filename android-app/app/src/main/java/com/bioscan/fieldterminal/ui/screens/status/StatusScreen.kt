package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bioscan.fieldterminal.data.StatusOverview
import com.bioscan.fieldterminal.data.StatusRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.nav.TileRoute
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Status tab. DAV-95: the body schematic is the compositional hub -- the
// four system tiles attach to it inside BodyConsole (see that file) rather
// than sitting in their own section here, per
// design/FIELD_TERMINAL_IA_CONTRACT.md section 4. Each tile is still its
// own pushed page with its own internal tabs (see TileRoute/FuelTab/HeartTab
// in ui/nav/TopLevelTab.kt) -- this is a compositional change, not a new
// navigation destination.
@Composable
fun StatusScreen(onOpenTile: (TileRoute) -> Unit, onOpenMap: (String) -> Unit) {
    var overview by remember { mutableStateOf<StatusOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        overview = StatusRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    // DAV-202 (24/9 fixes): was a scrolling Column, but real content (the
    // fixed-height figure box, the tile row, one line of "next up" text)
    // never approaches a full screen's height, leaving a large dead gap
    // below NEXT UP with nothing to scroll to. Non-scrolling fillMaxSize +
    // NextUpSection's own weight(1f) (see BodyConsole.kt) lets its surface
    // band absorb the remainder instead of leaving raw background showing.
    // ponytail: no scroll fallback if this page's content ever grows past
    // one screen (e.g. very large system font) -- revisit if that happens.
    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        BodyConsole(overview = overview, isLoading = isLoading, onOpenMap = onOpenMap, onOpenTile = onOpenTile)
    }
}
