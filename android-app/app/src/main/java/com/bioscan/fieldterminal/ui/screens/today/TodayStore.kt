@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class)

package com.bioscan.fieldterminal.ui.screens.today

import android.content.Context
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.ReadCache
import com.bioscan.fieldterminal.data.ScoresView
import com.bioscan.fieldterminal.data.TodayFuel
import com.bioscan.fieldterminal.data.TodayPreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

// TODAY's finished page, built once a day and kept:
//   - in memory for the life of the app process, and
//   - on disk, so opening the app after Android closed it shows the page at once instead of building it again.
// It is rebuilt in the background, the kept page staying on screen, when it may have gone stale:
//   - a write to one of the tables it reads (a Health Connect or Zepp sync, a log, the check-in), seen through ReadCache's
//     write stamp, which every write through the Supabase client bumps;
//   - a new day;
//   - or [STALE_MS] since it was built (stress samples and calendar entries arrive without a write from this app).
// A rebuild does not mean new scores: the score engine runs again only when what it reads changed ([TodaySnapshot.key],
// domain/score/ScoreKey.kt). A sync that rewrites the same rows changes nothing on screen.

data class TodaySnapshot(
    val day: LocalDate,
    val view: ScoresView?,
    val nut: NutritionOverview?,
    val fuel: TodayFuel?,
    val previews: TodayPreviews?,
    val closed: Set<LocalDate>,
    val builtAtMs: Long,
    val stamp: Long,
    /** What the scores were computed from; equal keys mean equal scores. */
    val key: String? = null,
    /** The plan and the SO FAR line as last drawn, shown from disk before the fuel plan has loaded again. */
    val rows: List<PlanRow>? = null,
    val soFar: SoFar? = null,
)

/** What is written to disk: the scores and the page's last plan, not the raw nutrition and fuel rows. */
@Serializable
data class KeptToday(
    val format: Int,
    val day: LocalDate,
    val builtAtMs: Long,
    val key: String,
    val view: ScoresView,
    val rows: List<PlanRow>,
    val soFar: SoFar?,
    val closed: Set<LocalDate>,
)

object TodayStore {
    const val STALE_MS = 15 * 60_000L

    /** Bump when [KeptToday] or anything inside ScoresView changes shape: an older file is then ignored. */
    const val FORMAT = 1
    private const val FILE = "today_page.json"
    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true; encodeDefaults = true }

    /** Every table TODAY's page reads. A write to any of them makes the page stale. */
    val TABLES: Set<String> = setOf(
        "wearable_daily", "sleep_daily", "wellbeing_daily", "exercise_sessions", "planned_sessions", "meals", "meal_items",
        "hydration_daily", "evening_checkins", "stool_log", "zepp_raw_extracts", "calendar_events", "supplement_log",
        "supplements", "body_metrics", "health_concerns", "health_observations", "injuries", "illnesses",
    )

    @Volatile
    var snapshot: TodaySnapshot? = null

    /** The kept page if it is today's, fresh or not: shown at once while a stale one is rebuilt. */
    fun forDay(today: LocalDate): TodaySnapshot? = snapshot?.takeIf { it.day == today }

    fun isFresh(s: TodaySnapshot?, today: LocalDate, nowMs: Long = System.currentTimeMillis(), stamp: Long = ReadCache.writeStamp(TABLES)): Boolean =
        isFresh(s?.day, s?.builtAtMs, s?.stamp, today, nowMs, stamp)

    /** Pure form, for tests. */
    fun isFresh(day: LocalDate?, builtAtMs: Long?, builtStamp: Long?, today: LocalDate, nowMs: Long, stamp: Long): Boolean =
        day == today && builtAtMs != null && nowMs - builtAtMs in 0 until STALE_MS && builtStamp == stamp

    fun clear() {
        snapshot = null
    }

    /** Sign-out: forget the kept page, in memory and on disk. */
    fun clearEverything(context: Context) {
        snapshot = null
        runCatching { File(context.filesDir, FILE).delete() }
    }

    fun encode(kept: KeptToday): String = json.encodeToString(KeptToday.serializer(), kept)

    /** The kept page for [today], or null if there is none, it is another day's, or it was written by an older version. */
    fun decode(text: String, today: LocalDate): KeptToday? =
        runCatching { json.decodeFromString(KeptToday.serializer(), text) }.getOrNull()?.takeIf { it.format == FORMAT && it.day == today }

    /** Reads the page kept on disk, off the main thread. Never throws. */
    suspend fun readDisk(context: Context, today: LocalDate): KeptToday? = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.let { decode(it, today) }
    }

    /** Writes the page to disk (to a temporary file first, so a crash mid-write never leaves half a page). Never throws. */
    suspend fun writeDisk(context: Context, kept: KeptToday) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(encode(kept))
            tmp.renameTo(File(context.filesDir, FILE))
        }
    }
}
