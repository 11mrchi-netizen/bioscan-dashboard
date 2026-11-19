package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.BloodworkMarkerEvaluation
import com.bioscan.fieldterminal.domain.evaluateBloodworkMarker
import com.bioscan.fieldterminal.domain.risk.InstrumentAnswer
import com.bioscan.fieldterminal.domain.risk.summaries
import com.bioscan.fieldterminal.domain.meview.MeViewDoc
import com.bioscan.fieldterminal.domain.meview.bloodworkMeView
import com.bioscan.fieldterminal.domain.meview.bodyMeView
import io.github.jan.supabase.SupabaseClient
import java.time.LocalDate

// What each published Me tab reads, in one place, so the tab on screen and the background refresh (MeViewPublishWorker)
// work the tab out from the same rows.

/** Each marker's change between its last two draws against its own RCV, by name (Me › Bloodwork). */
suspend fun loadBloodworkRcv(supabase: SupabaseClient): Map<String, BloodworkMarkerEvaluation> {
    val repo = AnalysisRepository(supabase)
    val drawDates = repo.loadLabDraws().associate { it.id to LocalDate.parse(it.drawDate) }
    return repo.loadLabResults()
        .mapNotNull { row -> row.value?.let { v -> drawDates[row.drawId]?.let { date -> Triple(row, date, v) } } }
        .groupBy { it.first.markerName }
        .mapValues { (name, rows) ->
            val first = rows.first().first
            evaluateBloodworkMarker(markerName = name, draws = rows.map { it.second to it.third }, unit = first.unit, refLow = first.refLow, refHigh = first.refHigh)
        }
}

object MeViewSources {
    suspend fun performance(supabase: SupabaseClient, today: LocalDate = LocalDate.now()): MeViewDoc {
        val sessions = AnalysisRepository(supabase).loadSportSessions()
        val running = catching { ReadCache.get("skills.running", tables = setOf("exercise_sessions", "wearable_daily", "zepp_workout_detail"),
            serializer = RunningHistory.serializer()) { SkillHistoryRepository(supabase).loadRunning() } }.getOrNull()
        val records = catching { com.bioscan.fieldterminal.domain.recordHistories(PersonalRecordsRepository(supabase).loadSets()) }.getOrDefault(emptyList())
        return com.bioscan.fieldterminal.domain.meview.performanceMeView(sessions, running, records, today)
    }

    suspend fun health(context: android.content.Context, supabase: SupabaseClient): MeViewDoc {
        val repo = HealthConcernsRepository(supabase)
        return com.bioscan.fieldterminal.domain.meview.healthMeView(
            repo.loadConcerns(), catching { repo.loadOverview() }.getOrNull(), catching { repo.loadAllFindings() }.getOrDefault(emptyList()),
            catching { repo.loadChecks() }.getOrDefault(emptyList()), HealthConcernsRepository.vocab(context),
        )
    }

    suspend fun body(supabase: SupabaseClient, today: LocalDate = LocalDate.now()): MeViewDoc {
        val metrics = AnalysisRepository(supabase).loadBodyMetrics()
        val checkinRepo = BodyCheckinRepository(supabase)
        val checkins = catching { checkinRepo.loadHistory() }.getOrDefault(emptyList())
        val profile = catching { UserProfileRepository(supabase).loadProfile() }.getOrNull()
        val height = profile?.heightCm ?: catching { checkinRepo.latestHeightCm() }.getOrNull()
        val scale = catching { ScaleReadingRepository(supabase).loadCompositionSeries() }.getOrDefault(emptyList())
        return bodyMeView(metrics, checkins, height, profile, scale, today)
    }

    suspend fun bloodwork(supabase: SupabaseClient): MeViewDoc {
        val overview = LabsRepository(supabase).loadOverview()
        val rcv = catching { loadBloodworkRcv(supabase) }.getOrDefault(emptyMap())
        val watches = catching { LabWatchRepository(supabase).watches() }.getOrDefault(emptyList())
        return bloodworkMeView(overview, rcv, watches)
    }

    fun routine(d: com.bioscan.fieldterminal.data.routine.RoutinePageData): MeViewDoc =
        com.bioscan.fieldterminal.domain.meview.routineMeView(
            d.today, d.ribbons, d.time, d.places, d.phone, d.regularity, d.activities, d.logs,
            d.apps.map { Triple(it.label ?: it.pkg, it.category, it.minutes) },
        )

    suspend fun exposures(context: android.content.Context, supabase: SupabaseClient): MeViewDoc {
        val defs = InstrumentsRepository.definitions(context)
        // The questionnaires are read softly: before their migration, the air part still publishes.
        val rows = catching { InstrumentsRepository(supabase).loadResponses() }.getOrElse { emptyList() }
        val summaries = defs.summaries(rows.map { InstrumentAnswer(it.instrument, it.date, it.score, it.band, it.flags) }, LocalDate.now())
        val screens = catching { RiskScreensRepository(supabase).load(context) }.getOrNull()
        return com.bioscan.fieldterminal.domain.meview.exposuresMeView(ExposuresRepository(supabase).load(), summaries, screens)
    }

    suspend fun routine(supabase: SupabaseClient): MeViewDoc =
        routine(com.bioscan.fieldterminal.data.routine.RoutinePageRepository(supabase).load())
}
