package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.graphics.Color
import com.bioscan.fieldterminal.domain.score.ScoreKeys
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens.Metric

/**
 * The fixed metric hue (design/tokens/tokens.css --m-*) of each score input and series key, so a metric is drawn in the
 * same colour on every list, bullet, chart and history page. Direction (above / below normal, the points it moved a
 * score) is never told by this colour: the number, the arrow and the words say it.
 */
fun metricInk(key: String): Color = when (key) {
    ScoreKeys.SLEEP_HOURS, ScoreKeys.SLEEP_DEBT -> Metric.SleepDuration
    ScoreKeys.SLEEP_REGULARITY -> Metric.Regularity
    ScoreKeys.SLEEP_MIDPOINT -> Metric.Circadian
    ScoreKeys.SLEEP_DEEP_REM -> Metric.StageDeep
    ScoreKeys.SLEEP_BREATHING -> Metric.Respiratory
    ScoreKeys.HRV, ScoreKeys.HRV_CV -> Metric.Hrv
    ScoreKeys.RHR -> Metric.RestingHr
    ScoreKeys.NIGHT_STRESS, ScoreKeys.STRESSED_SHARE -> Metric.PhysStress
    ScoreKeys.RELAXED_SHARE -> FuturisticMaterialTokens.Zone.StressCalm
    ScoreKeys.SORENESS -> Metric.Soreness
    ScoreKeys.ENERGY -> Metric.Energy
    ScoreKeys.MORNING_ERECTION, ScoreKeys.AROUSAL -> Metric.Arousal
    ScoreKeys.TSB -> Metric.Tsb
    ScoreKeys.DAYS_WITHOUT_REST, ScoreKeys.MONOTONY -> Metric.Atl
    ScoreKeys.PLAN_DONE -> Metric.Ctl
    ScoreKeys.FUEL_LOGGED_SHARE, ScoreKeys.ENERGY_RATIO -> Metric.Calories
    ScoreKeys.PROTEIN_RATIO -> Metric.Protein
    ScoreKeys.CARB_RATIO -> Metric.Carbs
    ScoreKeys.WATER_RATIO -> Metric.Hydration
    ScoreKeys.DRINKS -> Metric.Sugar
    ScoreKeys.LATE_CAFFEINE -> Metric.Caffeine
    ScoreKeys.GUT_SYMPTOMS -> Metric.GutComfort
    ScoreKeys.GUT_TIMING, ScoreKeys.GUT_TIMING_SPREAD, ScoreKeys.GUT_FREQUENCY, ScoreKeys.GUT_SESSION -> Metric.StoolHealthy
    ScoreKeys.GUT_FORM -> Metric.StoolLoose
    ScoreKeys.WIND_DOWN -> Metric.NightScreen
    ScoreKeys.HR_DIP -> Metric.HrDip
    ScoreKeys.FELT_STRESS -> Metric.Stress
    ScoreKeys.MOOD -> Metric.Mood
    ScoreKeys.LATE_SCREEN -> Metric.PhoneSleep
    ScoreKeys.MEAL_TIMING_SPREAD -> Metric.MealTiming
    else -> Metric.WeightEma
}
