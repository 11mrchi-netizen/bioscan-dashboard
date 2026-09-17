package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Science
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.StatusOverview
import com.bioscan.fieldterminal.data.StatusRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.nav.TileRoute
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

// Status tab. The "3d — Body console" launch screen (user's pick, see
// ROADMAP.md P8) stays above the fold; the old 6-chip sub-tab rail is
// replaced by 4 tiles (DAV-70, First feedback fixes), each its own pushed
// page with its own internal tabs (see TileRoute/FuelTab/HeartTab in
// ui/nav/TopLevelTab.kt). Real content migrated wholesale into those 4
// pages, not rebuilt -- see each tile screen's own file for what moved
// where.
@Composable
fun StatusScreen(onOpenTile: (TileRoute) -> Unit) {
    var overview by remember { mutableStateOf<StatusOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        overview = StatusRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FieldColors.Ground)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "STATUS", context = "ALL SYSTEMS")

        BodyConsole(overview = overview, isLoading = isLoading)

        TileGrid(onOpenTile)
    }
}

private data class TileSpec(val route: TileRoute, val label: String, val icon: ImageVector)

private val TILES = listOf(
    TileSpec(TileRoute.Training, "TRAINING", Icons.Filled.FitnessCenter),
    TileSpec(TileRoute.Fuel, "FUEL", Icons.Filled.Restaurant),
    TileSpec(TileRoute.Heart, "HEART", Icons.Filled.Favorite),
    TileSpec(TileRoute.Labs, "LABS", Icons.Filled.Science),
)

// A plain 2x2 Row/Column grid, not LazyVerticalGrid -- this codebase never
// nests a Lazy* container inside an already-vertically-scrolling Column
// (StatusScreen's own outer verticalScroll), which needs unbounded-height
// handling a Lazy grid doesn't give for free. Four known tiles don't need
// lazy layout anyway.
@Composable
private fun TileGrid(onOpenTile: (TileRoute) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TILES.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> StatusTile(tile, modifier = Modifier.weight(1f)) { onOpenTile(tile.route) } }
            }
        }
    }
}

@Composable
private fun StatusTile(tile: TileSpec, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .height(110.dp)
            .background(FieldColors.RaisedSurface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tile.icon, contentDescription = tile.label, tint = FieldColors.Amber, modifier = Modifier.height(28.dp))
        Text(
            text = tile.label,
            style = FieldTextStyles.tabBarLabel,
            color = FieldColors.Ink,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
