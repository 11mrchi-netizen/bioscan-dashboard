package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bioscan.fieldterminal.data.StatusOverview
import com.bioscan.fieldterminal.data.StatusRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodayPreviews
import com.bioscan.fieldterminal.domain.AddEntryStart
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.nav.TileRoute
import com.bioscan.fieldterminal.ui.screens.AddEntrySheet
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// Status tab. DAV-95: the body schematic is the compositional hub -- the
// four system tiles attach to it inside BodyConsole (see that file) rather
// than sitting in their own section here, per
// design/FIELD_TERMINAL_IA_CONTRACT.md section 4. Each tile is still its
// own pushed page with its own internal tabs (see TileRoute/FuelTab/HeartTab
// in ui/nav/TopLevelTab.kt) -- this is a compositional change, not a new
// navigation destination.
//
// UX review P1: the hub scrolls now (BodyConsole), the FUEL and LABS tiles preview today's data, and the quick-log row
// opens the add sheet here, on the right form; a save reloads the previews so the tiles show it at once.
@Composable
fun StatusScreen(onOpenTile: (TileRoute) -> Unit, onOpenToday: () -> Unit, onOpenInjury: () -> Unit = {}) {
    var overview by remember { mutableStateOf<StatusOverview?>(null) }
    var previews by remember { mutableStateOf<TodayPreviews?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var previewsAttempt by remember { mutableIntStateOf(0) }
    var addStart by remember { mutableStateOf<AddEntryStart?>(null) }
    val guard = rememberLoadGuard()
    val repo = remember { StatusRepository(SupabaseClientProvider.client) }

    LaunchedEffect(guard.attempt) {
        guard.run {
            coroutineScope {
                // The previews never fail the hub: each part is null when it cannot load (StatusRepository.softly).
                val previewsD = async { repo.loadTodayPreviews() }
                overview = repo.loadOverview()
                previews = previewsD.await()
            }
        }
        isLoading = false
    }
    LaunchedEffect(previewsAttempt) {
        if (previewsAttempt > 0) previews = repo.loadTodayPreviews()
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        if (guard.error != null && overview == null) {
            LoadErrorCard(guard)
        } else {
            BodyConsole(
                overview = overview,
                previews = previews,
                isLoading = isLoading,
                onOpenTile = onOpenTile,
                onOpenToday = onOpenToday,
                onOpenInjury = onOpenInjury,
                onQuickLog = { addStart = it },
            )
        }
    }

    addStart?.let { start ->
        AddEntrySheet(
            onDismiss = { addStart = null },
            onSaved = { addStart = null; previewsAttempt++ },
            start = start,
        )
    }
}
