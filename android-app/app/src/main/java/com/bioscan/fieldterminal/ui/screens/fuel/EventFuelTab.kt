package com.bioscan.fieldterminal.ui.screens.fuel

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.EventFuel
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.fuel.RaceFuelPlan
import com.bioscan.fieldterminal.domain.fuel.WeatherSource
import com.bioscan.fieldterminal.domain.fuel.raceFuelPlanText
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// An event's FUEL tab (docs/fuel-planning/README.md, phase 6): the race or long-day plan from domain/fuel/RaceFuelPlan.kt.
// The plan's text is kept on the phone after every load, so the tab still shows the last plan without signal, and SHARE
// sends it to a crew.

private const val CACHE = "fuel_plan_cache"
private val START: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

private fun cache(context: Context) = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)

@Composable
fun EventFuelTab(eventId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var fuel by remember { mutableStateOf<EventFuel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(attempt) {
        loading = true
        error = null
        try {
            fuel = FuelPlanRepository(SupabaseClientProvider.client).loadEvent(context, eventId)
            fuel?.let { f ->
                val text = raceFuelPlanText(f.plan, startLabel(f))
                cache(context).edit().putString("event:$eventId", text).putLong("event:$eventId:at", System.currentTimeMillis()).apply()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        } finally {
            loading = false
        }
    }

    Column(modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val f = fuel
        when {
            f != null -> PlanView(f)
            loading -> Text("Building the plan (weather, course, sweat)…", style = FTType.BodySmall, color = FT.TextSecondary)
            else -> {
                val cached = cache(context).getString("event:$eventId", null)
                Text(
                    error?.let { "Couldn't build the plan now: $it" } ?: "Set a target time or an expected duration on the event to plan it.",
                    style = FTType.BodySmall, color = if (error != null) FT.Critical else FT.TextSecondary,
                )
                if (cached != null) {
                    val at = java.time.Instant.ofEpochMilli(cache(context).getLong("event:$eventId:at", 0L)).atZone(ZoneId.systemDefault())
                    FTCard(title = "LAST SAVED PLAN · ${at.format(START).uppercase()}") { Text(cached, style = FTType.MonoCaption, color = FT.TextPrimary) }
                    FTButton("SHARE THE SAVED PLAN") { share(context, cached) }
                }
                FTButton("RETRY", emphasis = FTButtonEmphasis.Tertiary) { attempt++ }
            }
        }
    }
}

private fun startLabel(f: EventFuel): String =
    (f.event.start ?: f.event.date.atTime(9, 0).atZone(ZoneId.systemDefault())).withZoneSameInstant(ZoneId.systemDefault()).format(START)

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share the fuel plan")) }
}

@Composable
private fun PlanView(f: EventFuel) {
    val context = LocalContext.current
    val p = f.plan
    FTCard(title = "PER HOUR", emphasis = FTCardEmphasis.Hero) {
        Text("${p.carbsPerHourLow.roundToInt()}-${p.carbsPerHourHigh.roundToInt()} G CARBOHYDRATE", style = FTType.LabelCaps, color = FT.TextPrimary)
        Text("${p.fluidMlPerHourLow.roundToInt()}-${p.fluidMlPerHourHigh.roundToInt()} ML FLUID · ${p.sodiumMgPerL.roundToInt()} MG SODIUM PER LITRE", style = FTType.LabelCaps, color = FT.TextPrimary)
        Text(
            "Start ${startLabel(f)} · ${hm(p.durationMin)} planned (${hm(p.durationLowMin)}-${hm(p.durationHighMin)}) · " +
                "total ${p.totalCarbsG.roundToInt()} g, ${"%.1f".format(p.totalFluidMl / 1000)} L" + if (p.totalCaffeineMg > 0) ", ${p.totalCaffeineMg.roundToInt()} mg caffeine" else "",
            style = FTType.MonoCaption, color = FT.TextSecondary,
        )
        Text(
            "WEATHER: ${p.weatherSource.label}" + (p.heat?.let { " · ${it.band.label}" } ?: ""),
            style = FTType.LabelCaps, color = if (p.weatherSource == WeatherSource.FORECAST) FT.TextPrimary else FT.TextSecondary,
        )
        p.heat?.lines?.forEach { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
        Text("Sweat about ${(p.sweat.mlPerHour / 10).roundToInt() * 10} ml an hour (${p.sweat.basis}).", style = FTType.Caption, color = FT.TextSecondary)
        if (p.expectedLossPct > 0) Text("Expected loss at the finish: %.1f %% of body weight.".format(p.expectedLossPct), style = FTType.Caption, color = FT.TextSecondary)
    }
    if (f.changes.isNotEmpty()) {
        FTCard(title = "CHANGED SINCE LAST SHOWN") { f.changes.forEach { Text("· $it", style = FTType.BodySmall, color = FT.TextPrimary) } }
    }
    if (p.warnings.isNotEmpty()) {
        FTCard(title = "WATCH") { p.warnings.forEach { Text("· $it", style = FTType.BodySmall, color = FT.Warning) } }
    }
    if (f.event.course.size >= 2) {
        FTCard(title = "ON THE COURSE") {
            FuelProfileChart(f.event.course, p)
            Text("Dots: fuel stops every 20 minutes. Ticks: aid stations.", style = FTType.Caption, color = FT.TextSecondary)
        }
    }
    FTCard(title = "BEFORE") { p.before.forEach { PrepLine(it.whenLabel, it.text) } }
    FTCard(title = "DURING") {
        p.steps.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hm(s.elapsedMin), style = FTType.MonoCaption, color = FT.DomainFuel, modifier = Modifier.width(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.items.joinToString(" · ").ifBlank { "—" }, style = FTType.BodySmall, color = FT.TextPrimary)
                    Text(
                        listOfNotNull(
                            s.km?.let { "KM %.1f".format(it) },
                            s.aidStation?.let { it.name.uppercase() },
                            s.tempC?.let { "${it.roundToInt()} °C" },
                            "${s.sodiumMg.roundToInt()} MG NA",
                        ).joinToString(" · "),
                        style = FTType.MonoCaption, color = FT.TextSecondary,
                    )
                }
            }
        }
    }
    if (p.legs.isNotEmpty()) {
        FTCard(title = "CARRY") {
            p.legs.forEach { leg ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text("${leg.from.uppercase()} → ${leg.to.uppercase()} · ${hm(leg.minutes)}", style = FTType.LabelCaps, color = FT.TextPrimary)
                    Text(
                        (leg.items.entries.joinToString(", ") { "${it.value} × ${it.key}" }.ifBlank { "Nothing to eat" }) + " · ${leg.bottlesMl} ml to drink",
                        style = FTType.BodySmall, color = FT.TextSecondary,
                    )
                    leg.note?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
                }
            }
        }
    }
    FTCard(title = "AFTER") { p.after.forEach { PrepLine(it.whenLabel, it.text) } }
    FTCard(title = "ASSUMPTIONS") { p.assumptions.forEach { Text("· $it", style = FTType.Caption, color = FT.TextSecondary) } }
    FTButton("SHARE THE PLAN", emphasis = FTButtonEmphasis.Primary) { share(context, raceFuelPlanText(p, startLabel(f))) }
}

@Composable
private fun PrepLine(whenLabel: String, text: String) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(whenLabel, style = FTType.LabelCaps, color = FT.DomainFuel)
        Text(text, style = FTType.BodySmall, color = FT.TextPrimary)
    }
}

// The elevation profile with the fuel stops (dots) and aid stations (ticks) on it. One axis (elevation, labelled at its
// extremes); the stops are positions, not a second scale.
@Composable
private fun FuelProfileChart(course: List<CoursePoint>, plan: RaceFuelPlan) {
    val pts = course.filter { it.elevationM != null }
    if (pts.size < 2) return
    val maxD = pts.last().distanceM
    val minE = pts.minOf { it.elevationM!! }
    val maxE = pts.maxOf { it.elevationM!! }.let { if (it - minE < 1) it + 1 else it }
    val stopsKm = plan.steps.mapNotNull { it.km }
    val stations = plan.steps.mapNotNull { s -> s.aidStation?.let { it.km } }.distinct()
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).semantics {
            contentDescription = "Course profile from ${minE.roundToInt()} to ${maxE.roundToInt()} m over ${"%.1f".format(maxD / 1000)} km, with ${stopsKm.size} fuel stops"
        },
    ) {
        val pad = 8f
        fun x(d: Double) = pad + (d / maxD).toFloat() * (size.width - 2 * pad)
        fun y(e: Double) = pad + (1 - ((e - minE) / (maxE - minE)).toFloat()) * (size.height - 2 * pad)
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.distanceM), y(p.elevationM!!)) else path.lineTo(x(p.distanceM), y(p.elevationM!!)) }
        drawPath(path, FT.TextSecondary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        fun elevAt(km: Double): Double = com.bioscan.fieldterminal.domain.fuel.elevationAtKm(pts, km) ?: minE
        stations.forEach { km ->
            val px = x(km * 1000)
            drawLine(FT.TextPrimary, Offset(px, pad), Offset(px, size.height - pad), strokeWidth = 1.dp.toPx())
        }
        stopsKm.forEach { km ->
            drawCircle(FT.Base, radius = 5.dp.toPx(), center = Offset(x(km * 1000), y(elevAt(km))))
            drawCircle(FT.DomainFuel, radius = 4.dp.toPx(), center = Offset(x(km * 1000), y(elevAt(km))))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0 KM · ${minE.roundToInt()}-${maxE.roundToInt()} M", style = FTType.MonoCaption, color = FT.TextSecondary)
        Text("%.1f KM".format(maxD / 1000), style = FTType.MonoCaption, color = FT.TextSecondary)
    }
}
