package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.FoodSuggestion
import com.bioscan.fieldterminal.data.NutritionCronometerLookupRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Type a food, pick it from suggestions: the user's remembered choice, the app's own catalog (barcode products, scanned
// labels) and Cronometer, merged server-side. `initial` shows before anything is typed (e.g. an item's alternatives).
// Debounced: a request goes out only after a pause in typing, and a newer query cancels the older one.
@Composable
fun FoodSuggestField(
    initialQuery: String,
    initial: List<FoodSuggestion>,
    placeholder: String,
    onPick: (query: String, suggestion: FoodSuggestion) -> Unit,
    onCancel: () -> Unit,
) {
    val repo = remember { NutritionCronometerLookupRepository(SupabaseClientProvider.client) }
    var query by remember { mutableStateOf(initialQuery) }
    var suggestions by remember { mutableStateOf(initial) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // The prefilled text is not a search the user asked for: show `initial` until they type.
    var typed by remember { mutableStateOf(false) }

    LaunchedEffect(query, typed) {
        if (!typed) return@LaunchedEffect
        val q = query.trim()
        if (q.length < 2) {
            suggestions = initial
            searching = false
            return@LaunchedEffect
        }
        searching = true
        failed = false
        delay(350)
        try {
            suggestions = repo.suggest(q)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            searching = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldTextField(
                query,
                { query = it; typed = true },
                placeholder,
                modifier = Modifier.weight(1f),
            )
            Text("CANCEL", style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.ftClickable { onCancel() })
        }
        when {
            searching -> Text("Searching...", style = FTType.Caption, color = FT.TextMuted)
            failed -> Text("Couldn't search right now -- try again in a moment.", style = FTType.Caption, color = FT.Warning)
            typed && query.trim().length >= 2 && suggestions.isEmpty() -> Text("No foods found.", style = FTType.Caption, color = FT.TextMuted)
        }
        if (suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall)),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                suggestions.forEach { s ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .ftClickable { onPick(query.trim().ifEmpty { s.name }, s) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(s.name, style = FTType.BodySmall, color = FT.TextPrimary)
                        Text(suggestionDetail(s), style = FTType.Caption, color = if (s.remembered) FT.Emerald else FT.TextMuted)
                    }
                }
            }
        }
    }
}

private fun suggestionDetail(s: FoodSuggestion): String = listOfNotNull(
    "Your usual choice".takeIf { s.remembered },
    s.kcalPer100g?.let { "${it.roundToInt()} kcal/100 g" },
    sourceLabel(s.source, s.ref.kind),
).joinToString(" · ")

internal fun sourceLabel(source: String?, kind: String? = null): String? = when {
    source == null -> if (kind == "catalog") "Saved product" else null
    source == "label_scan" -> "Scanned label"
    source == "openfoodfacts" -> "Open Food Facts"
    source.startsWith("usda") || source.startsWith("USDA") -> "USDA"
    else -> source
}
