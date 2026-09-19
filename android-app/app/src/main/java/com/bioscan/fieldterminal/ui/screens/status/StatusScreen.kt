package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.bioscan.fieldterminal.ui.components.ScreenHeader
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FT.Base)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "STATUS", context = "ALL SYSTEMS")

        BodyConsole(overview = overview, isLoading = isLoading, onOpenMap = onOpenMap, onOpenTile = onOpenTile)
    }
}
