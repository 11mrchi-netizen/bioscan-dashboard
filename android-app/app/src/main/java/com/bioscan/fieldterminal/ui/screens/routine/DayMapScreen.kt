package com.bioscan.fieldterminal.ui.screens.routine

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clipToBounds
import com.bioscan.fieldterminal.ui.components.fitTo
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.DayFix
import com.bioscan.fieldterminal.data.routine.DayRecord
import com.bioscan.fieldterminal.data.routine.PlaceRow
import com.bioscan.fieldterminal.data.routine.PlacesRepository
import com.bioscan.fieldterminal.domain.routine.CommuteJourney
import com.bioscan.fieldterminal.domain.routine.MANUAL_PLACE_RADII_M
import com.bioscan.fieldterminal.domain.routine.ModeGroup
import com.bioscan.fieldterminal.domain.routine.PlaceCategory
import com.bioscan.fieldterminal.domain.routine.TimelineItem
import com.bioscan.fieldterminal.domain.routine.commuteJourneys
import com.bioscan.fieldterminal.domain.routine.dayTimeline
import com.bioscan.fieldterminal.domain.routine.homeWorkProblem
import com.bioscan.fieldterminal.domain.routine.isCommute
import com.bioscan.fieldterminal.domain.routine.mergeCandidates
import com.bioscan.fieldterminal.domain.routine.modeGroup
import com.bioscan.fieldterminal.domain.routine.timelineCoverage
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.settings.PlaceEditor
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Day map (docs/routine-intelligence/README.md, "Day map and commute"): one day of what Locations recorded, on a map and
// as a timeline. The path is drawn from the trip fixes, coloured by the activity Android reported with each fix; each
// place is its 150 m circle with its name (places not visited that day are drawn faint, so a stray place shows). Tap a
// place to name it, merge it into another or delete it; long-press the map to mark a place by hand.
@Composable
fun DayMapScreen(initialDate: LocalDate, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val repo = remember { PlacesRepository(SupabaseClientProvider.client) }
    var date by rememberSaveable { mutableStateOf(initialDate) }
    var reload by remember { mutableIntStateOf(0) }
    var places by remember { mutableStateOf<List<PlaceRow>>(emptyList()) }
    var record by remember { mutableStateOf<DayRecord?>(null) }
    var recordDate by remember { mutableStateOf<LocalDate?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PlaceRow?>(null) }
    var marking by remember { mutableStateOf<GeoPoint?>(null) }
    var focus by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now(zone)

    LaunchedEffect(date, reload) {
        loading = true
        // Another day: drop the old one at once, so the map never zooms to the previous day's points.
        if (recordDate != date) { record = null; recordDate = null }
        scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { loading = false }) {
            places = repo.loadPlaces()
            record = repo.loadDay(date, zone)
            recordDate = date
            error = null
        }
    }

    val categories = places.associate { it.id to PlaceCategory.of(it.category) }
    val categoryOf: (String?) -> PlaceCategory? = { categories[it] }
    val byId = places.associateBy { it.id }
    val items = record?.let { r ->
        dayTimeline(date, zone, r.visits, r.trips, System.currentTimeMillis(), isCommute = { t -> isCommute(t, categoryOf) })
    }.orEmpty()
    val journeys = record?.let { commuteJourneys(it.trips, categoryOf) }.orEmpty()
    val problem = homeWorkProblem(places.map { it.toPlace() }, places.firstOrNull { it.source == "home_setting" }?.id)

    PinnedPage(
        onBack = onBack,
        title = "DAY MAP",
        subtitle = date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")).uppercase() +
            if (record != null && items.isNotEmpty()) " · %.0f %% RECORDED".format(timelineCoverage(items) * 100) else "",
        accent = FT.DomainLabs,
        rail = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FTButton("‹ PREVIOUS", modifier = Modifier.weight(1f)) { date = date.minusDays(1); focus = null }
                    FTButton("NEXT ›", modifier = Modifier.weight(1f), enabled = date.isBefore(today)) { date = date.plusDays(1); focus = null }
                }
                DayMap(
                    date = recordDate ?: date,
                    record = record,
                    places = places,
                    visitedIds = record?.visits?.mapNotNull { it.placeId }?.toSet().orEmpty(),
                    focusId = focus,
                    marking = marking,
                    cartoKey = remember { MapSettingsStore.getCartoKey(context) },
                    onPlaceTap = { editing = it },
                    onLongPress = { marking = it },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            problem?.let { msg ->
                val setup = places.firstOrNull { it.source == "home_setting" }
                FTCard(title = "HOME AND WORK") {
                    Text(msg, style = FTType.BodySmall, color = FT.Warning)
                    if (setup != null && setup.category != PlaceCategory.HOME.key && places.none { it.category == PlaceCategory.HOME.key }) {
                        FTButton("MAKE THE SETUP PLACE HOME", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Primary) {
                            scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { reload++ }) {
                                repo.saveLabel(setup.id, "Home", PlaceCategory.HOME)
                            }
                        }
                    }
                }
            }
            Text(
                "Long-press the map to mark a place. Tap a place's name to label, merge or delete it.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
            ModeLegend(listOf(ModeGroup.ON_FOOT, ModeGroup.BICYCLE, ModeGroup.VEHICLE, ModeGroup.UNKNOWN))
            if (loading && record == null) Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
            if (journeys.isNotEmpty()) CommuteOfTheDay(journeys)
            record?.let { r ->
                if (r.visits.isEmpty() && r.trips.isEmpty()) {
                    Text(
                        if (r.fixes.isEmpty()) "Nothing was recorded this day." else "${r.fixes.size} fixes, but no stay of 10 minutes or more was found yet.",
                        style = FTType.BodySmall,
                        color = FT.TextSecondary,
                    )
                } else {
                    DaySummary(items, byId)
                    Timeline(items, byId, zone, onVisit = { id -> focus = id; byId[id]?.let { editing = it } })
                }
            }
        }
    }

    editing?.let { p ->
        PlaceSheet(
            place = p,
            places = places,
            onDismiss = { editing = null },
            onSave = { label, category, stop ->
                scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { editing = null; reload++ }) {
                    repo.saveLabel(p.id, label, category, stop)
                }
            },
            onMerge = { into ->
                scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { editing = null; reload++ }) {
                    repo.mergePlace(p.id, into.id)
                }
            },
            onDelete = {
                scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { editing = null; reload++ }) {
                    repo.deletePlace(p.id)
                }
            },
        )
    }
    marking?.let { point ->
        MarkPlaceSheet(
            point = point,
            onDismiss = { marking = null },
            onSave = { label, category, radius ->
                scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { marking = null; reload++ }) {
                    // Same as Setup's ADD PLACE: the history inside the circle moves to it (claimForManualPlace).
                    val name = label?.trim()?.ifBlank { null } ?: category?.label ?: "Marked place"
                    repo.addPlace(name, category, point.latitude, point.longitude, radius)
                }
            },
        )
    }
}

private class MapHolder {
    var zoomedFor: Pair<LocalDate, String?>? = null
}

@Composable
private fun DayMap(
    date: LocalDate,
    record: DayRecord?,
    places: List<PlaceRow>,
    visitedIds: Set<String>,
    focusId: String?,
    marking: GeoPoint?,
    cartoKey: String?,
    onPlaceTap: (PlaceRow) -> Unit,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { MapHolder() }
    AndroidView(
        modifier = modifier.clipToBounds(),
        onRelease = { it.onDetach() },
        factory = { ctx ->
            com.bioscan.fieldterminal.ui.components.stableMapView(ctx).apply {
                // CARTO's dark tiles when a key is saved (Setup › Connected services), OpenStreetMap's own otherwise.
                if (cartoKey != null) {
                    setTileSource(
                        XYTileSource("CartoDarkMatter", 0, 20, 256, ".png?key=$cartoKey", arrayOf("https://basemaps.cartocdn.com/rastertiles/dark_all/")),
                    )
                } else {
                    setTileSource(TileSourceFactory.MAPNIK)
                }
                controller.setZoom(13.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            val density = map.resources.displayMetrics.density
            map.overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let(onLongPress)
                        return p != null
                    }
                }),
            )
            // Places: faint when not visited this day.
            places.forEach { p ->
                val visited = p.id in visitedIds
                val color = placeColor(PlaceCategory.of(p.category), p.label != null).toArgb()
                map.overlays.add(
                    Polygon(map).apply {
                        points = Polygon.pointsAsCircle(GeoPoint(p.lat, p.lon), p.radiusM)
                        fillPaint.color = withAlpha(color, if (visited) 0.18f else 0.06f)
                        outlinePaint.color = withAlpha(color, if (visited) 0.9f else 0.35f)
                        outlinePaint.strokeWidth = (if (visited) 2f else 1f) * density
                        setOnClickListener { _, _, _ -> false }
                    },
                )
            }
            // The path: each trip from its fixes, split where the activity changes or fixes are 15 minutes apart.
            record?.let { r -> pathRuns(r).forEach { run ->
                map.overlays.add(
                    Polyline(map).apply {
                        setPoints(run.points)
                        outlinePaint.color = ModeColors.getValue(run.mode).toArgb()
                        outlinePaint.strokeWidth = 4f * density
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        setOnClickListener { _, _, _ -> false }
                    },
                )
            } }
            // Names on top, so they take the taps. Every place has one (faint when not visited), so a stray place can be
            // found and merged or deleted.
            places.forEach { p ->
                val visited = p.id in visitedIds
                map.overlays.add(
                    Marker(map).apply {
                        position = GeoPoint(p.lat, p.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = LabelBadge(p.label ?: "Unlabelled", placeColor(PlaceCategory.of(p.category), p.label != null).toArgb(), density, faint = !visited)
                        setOnMarkerClickListener { _, _ -> onPlaceTap(p); true }
                    },
                )
            }
            marking?.let { m ->
                map.overlays.add(
                    Marker(map).apply {
                        position = m
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = LabelBadge("New place", FT.TextPrimary.toArgb(), density, faint = false)
                        setOnMarkerClickListener { _, _ -> true }
                    },
                )
            }
            // Zoom once per day (and per focused place), not on every redraw, so a pan or zoom stays put.
            val key = date to focusId
            if (record != null && holder.zoomedFor != key) {
                holder.zoomedFor = key
                val focus = focusId?.let { id -> places.firstOrNull { it.id == id } }
                val pts = if (focus != null) {
                    listOf(GeoPoint(focus.lat, focus.lon))
                } else {
                    record.visits.map { GeoPoint(it.lat, it.lon) } + record.fixes.map { GeoPoint(it.lat, it.lon) }
                }.ifEmpty { places.firstOrNull { it.category == PlaceCategory.HOME.key }?.let { listOf(GeoPoint(it.lat, it.lon)) }.orEmpty() }
                if (pts.isNotEmpty()) map.fitTo(pts)
            }
            map.invalidate()
        },
    )
}


private data class PathRun(val mode: ModeGroup, val points: List<GeoPoint>)

// Each trip's fixes (accuracy 200 m or better) as runs of one mode, starting at the place left and ending at the place
// reached. A fix without an activity takes the mode of the run it is in.
private fun pathRuns(r: DayRecord): List<PathRun> {
    val runs = mutableListOf<PathRun>()
    val visitEnds = r.visits.associateBy { it.endMs }
    val visitStarts = r.visits.associateBy { it.startMs }
    for (trip in r.trips) {
        val fixes = r.fixes.filter { it.ts in trip.startMs..trip.endMs && (it.accuracyM ?: 0.0) <= 200.0 }
        val start = visitEnds[trip.startMs]?.let { DayFix(trip.startMs, it.lat, it.lon, null, null) }
        val end = visitStarts[trip.endMs]?.let { DayFix(trip.endMs, it.lat, it.lon, null, null) }
        val all = listOfNotNull(start) + fixes + listOfNotNull(end)
        if (all.size < 2) continue
        var mode = all.firstNotNullOfOrNull { it.activity }?.let(::modeGroup) ?: ModeGroup.UNKNOWN
        var current = mutableListOf(GeoPoint(all[0].lat, all[0].lon))
        for (i in 1 until all.size) {
            val f = all[i]
            val m = f.activity?.let(::modeGroup) ?: mode
            val gap = f.ts - all[i - 1].ts > 15 * 60_000L
            val p = GeoPoint(f.lat, f.lon)
            if (m != mode || gap) {
                if (!gap) current += p // join the runs where the mode changes
                if (current.size >= 2) runs += PathRun(mode, current)
                current = mutableListOf(p)
                mode = m
            } else {
                current += p
            }
        }
        if (current.size >= 2) runs += PathRun(mode, current)
    }
    return runs
}

private fun withAlpha(argb: Int, a: Float): Int = (argb and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

// A place's name on the map: a rounded tag with a coloured ring, faint for a place not visited that day.
private class LabelBadge(private val label: String, color: Int, density: Float, faint: Boolean) : Drawable() {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (faint) FT.TextSecondary.toArgb() else FT.TextPrimary.toArgb()
        textSize = 11 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(FT.Surface.toArgb(), if (faint) 0.7f else 0.92f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = withAlpha(color, if (faint) 0.5f else 1f)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val padH = 7 * density
    private val padV = 4 * density
    private val w = (text.measureText(label) + 2 * padH).toInt()
    private val h = (text.textSize + 2 * padV).toInt()

    init { setBounds(0, 0, w, h) }

    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds).apply { inset(ring.strokeWidth, ring.strokeWidth) }
        canvas.drawRoundRect(r, h / 2f, h / 2f, fill)
        canvas.drawRoundRect(r, h / 2f, h / 2f, ring)
        canvas.drawText(label, bounds.left + padH, bounds.exactCenterY() - (text.descent() + text.ascent()) / 2, text)
    }

    override fun setAlpha(alpha: Int) { text.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { text.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

@Composable
private fun CommuteOfTheDay(journeys: List<CommuteJourney>) {
    val scale = journeys.maxOf { it.minutes }
    FTCard(
        title = "COMMUTE",
        info = "Door to door between your Home and Work places, through stops of up to 15 minutes on the way. A longer stop " +
            "(dinner on the way home) makes two outings, not a commute.",
    ) {
        journeys.forEach { j ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${j.way.label} · ${clock(j.departMs)} → ${clock(j.arriveMs)}", style = FTType.Body, color = FT.TextPrimary)
                    Text(hm(j.minutes), style = FTType.Telemetry, color = FT.TextPrimary)
                }
                ModeBar(j.byMode, scale)
                val stops = if (j.stops.isEmpty()) "" else " · ${j.stops.size} stop${if (j.stops.size > 1) "s" else ""}"
                Text("%.1f km$stops · ${modeLine(j.byMode)}".format(j.distanceKm), style = FTType.BodySmall, color = FT.TextSecondary)
            }
        }
        ModeLegend()
    }
}

@Composable
private fun DaySummary(items: List<TimelineItem>, byId: Map<String, PlaceRow>) {
    val byCategory = mutableMapOf<String, Double>()
    var moving = 0.0
    var km = 0.0
    var active = 0.0
    items.forEach { i ->
        when (i) {
            is TimelineItem.Visit -> {
                val p = i.visit.placeId?.let { byId[it] }
                val key = PlaceCategory.of(p?.category)?.label ?: if (p?.label != null) "Other" else "Unlabelled"
                byCategory[key] = (byCategory[key] ?: 0.0) + i.minutes
            }
            is TimelineItem.Move -> { moving += i.minutes; km += i.trip.distanceKm; active += i.trip.activeMinutes }
            is TimelineItem.Gap -> Unit
        }
    }
    val unknown = items.filterIsInstance<TimelineItem.Gap>().sumOf { it.minutes }
    FTCard(title = "THE DAY") {
        byCategory.entries.sortedByDescending { it.value }.forEach { (k, v) -> SummaryLine(k, hm(v)) }
        SummaryLine("On the move", "${hm(moving)} · %.1f km · ${hm(active)} on foot or bicycle".format(km))
        if (unknown >= 1) SummaryLine("Nothing recorded", hm(unknown))
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Body, color = FT.TextPrimary)
        Text(value, style = FTType.Telemetry, color = FT.TextSecondary)
    }
}

@Composable
private fun Timeline(items: List<TimelineItem>, byId: Map<String, PlaceRow>, zone: ZoneId, onVisit: (String) -> Unit) {
    val scale = items.filterIsInstance<TimelineItem.Move>().maxOfOrNull { it.minutes } ?: 1.0
    FTCard(title = "TIMELINE", info = "Stays of 10 minutes or more within 150 m are visits; the time between two visits is a trip.") {
        items.forEach { item ->
            val span = "${clock(item.startMs, zone)}–${clock(item.endMs, zone)}"
            when (item) {
                is TimelineItem.Visit -> {
                    val p = item.visit.placeId?.let { byId[it] }
                    val name = p?.label ?: "Unlabelled place"
                    val cat = PlaceCategory.of(p?.category)
                    Row(
                        Modifier.fillMaxWidth().ftClickable(enabled = p != null, shape = RoundedCornerShape(FT.RadiusSmall)) { p?.let { onVisit(it.id) } }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(placeColor(cat, p?.label != null))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = FTType.Body, color = if (p?.label == null) FT.Warning else FT.TextPrimary)
                            val more = listOfNotNull(
                                cat?.label,
                                p?.osmName?.takeIf { p.label == null }?.let { "near $it" },
                                "from the day before".takeIf { item.startsBefore },
                                "into the next day".takeIf { item.endsAfter },
                            ).joinToString(" · ")
                            Text(if (more.isEmpty()) span else "$span · $more", style = FTType.BodySmall, color = FT.TextSecondary)
                        }
                        Text(hm(item.minutes), style = FTType.Telemetry, color = FT.TextSecondary)
                    }
                }
                is TimelineItem.Move -> {
                    val byMode = item.trip.legs.groupBy { modeGroup(it.mode) }.mapValues { (_, ls) -> ls.sumOf { it.minutes } }
                    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "$span · %.1f km".format(item.trip.distanceKm) + if (item.commute) " · COMMUTE" else "",
                                style = FTType.BodySmall,
                                color = if (item.commute) FT.TextPrimary else FT.TextSecondary,
                            )
                            Text(hm(item.minutes), style = FTType.Telemetry, color = FT.TextSecondary)
                        }
                        ModeBar(byMode, scale)
                        modeLine(byMode).takeIf { it.isNotEmpty() }?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
                    }
                }
                is TimelineItem.Gap -> Text(
                    "$span · nothing recorded (${hm(item.minutes)})",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlaceSheet(
    place: PlaceRow,
    places: List<PlaceRow>,
    onDismiss: () -> Unit,
    onSave: (String?, PlaceCategory?, Boolean) -> Unit,
    onMerge: (PlaceRow) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<PlaceRow?>(null) }
    val byId = places.associateBy { it.id }
    val near = mergeCandidates(place.toPlace(), places.map { it.toPlace() }).mapNotNull { (p, d) -> byId[p.id]?.let { it to d } }.take(5)
    val suggestion = if (place.osmName != null || place.osmCategory != null) place.osmName to PlaceCategory.of(place.osmCategory) else null
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("PLACE", style = FTType.SectionTitle, color = FT.TextPrimary)
            val origin = when (place.source) {
                "home_setting" -> "Made from your Setup home location"
                "manual" -> "Marked by hand"
                else -> "Found from your visits"
            }
            Text(
                listOfNotNull(origin, "%.0f m circle".format(place.radiusM), place.osmName?.let { "OpenStreetMap: $it" }).joinToString(" · "),
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
            PlaceEditor(place = place, suggestion = suggestion, onCancel = onDismiss, onSave = onSave)

            if (near.isNotEmpty()) {
                Text("MERGE INTO A NEARBY PLACE", style = FTType.Label, color = FT.TextSecondary)
                Text(
                    "For two places that are one, or a place recorded by mistake: its visits and trips move to the other place.",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    near.forEach { (p, d) ->
                        val selected = mergeTarget?.id == p.id
                        val shape = RoundedCornerShape(FT.RadiusSmall)
                        Text(
                            "${p.label ?: p.osmName ?: "Unlabelled"} · %.0f m".format(d),
                            style = FTType.Label,
                            color = if (selected) FT.PlatinumInk else FT.TextSecondary,
                            modifier = Modifier
                                .border(FT.BorderWidth, if (selected) Color.Transparent else FT.GlassBorder, shape)
                                .background(if (selected) FT.PlatinumSolid else Color.Transparent, shape)
                                .ftClickable(shape = shape) { mergeTarget = if (selected) null else p }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                mergeTarget?.let { t ->
                    FTButton("MERGE INTO ${(t.label ?: "THAT PLACE").uppercase()}", modifier = Modifier.fillMaxWidth()) { onMerge(t) }
                }
            }

            if (confirmDelete) {
                Text(
                    "Its visits stay, as unlabelled time. If you stay here again, a new place starts. Merge is usually what you want.",
                    style = FTType.BodySmall,
                    color = FT.Warning,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FTButton("KEEP", modifier = Modifier.weight(1f)) { confirmDelete = false }
                    FTButton("DELETE PLACE", modifier = Modifier.weight(1f), emphasis = FTButtonEmphasis.Primary, onClick = onDelete)
                }
            } else {
                FTButton("DELETE PLACE…", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Tertiary) { confirmDelete = true }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MarkPlaceSheet(point: GeoPoint, onDismiss: () -> Unit, onSave: (String?, PlaceCategory?, Double) -> Unit) {
    var radius by remember { mutableStateOf(150.0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("MARK A PLACE", style = FTType.SectionTitle, color = FT.TextPrimary)
            Text(
                "%.5f, %.5f. Visits inside the circle move to it when it is nearer than their own place, and unnamed places " +
                    "the phone found inside it are merged into it."
                    .format(point.latitude, point.longitude),
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
            Text("SIZE", style = FTType.Label, color = FT.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MANUAL_PLACE_RADII_M.map { it to "%.0f m".format(it) }.forEach { (r, label) ->
                    val selected = radius == r
                    val shape = RoundedCornerShape(FT.RadiusSmall)
                    Text(
                        label,
                        style = FTType.Label,
                        color = if (selected) FT.PlatinumInk else FT.TextSecondary,
                        modifier = Modifier
                            .border(FT.BorderWidth, if (selected) Color.Transparent else FT.GlassBorder, shape)
                            .background(if (selected) FT.PlatinumSolid else Color.Transparent, shape)
                            .ftClickable(shape = shape) { radius = r }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            PlaceEditor(
                place = PlaceRow("new", point.latitude, point.longitude, radius, source = "manual"),
                suggestion = null,
                onCancel = onDismiss,
                onSave = { label, category, _ -> onSave(label, category, radius) },
                showStop = false,
            )
        }
    }
}
