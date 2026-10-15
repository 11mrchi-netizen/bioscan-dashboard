package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.ZeppStrengthData
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetails
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import com.bioscan.fieldterminal.data.model.StrengthSetDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Synthetic workouts only.
class StrengthImportTest {
    private fun set(reps: Int, kg: Double, duration: Int? = 40) = StrengthSetDto(reps = reps, weightKg = kg, durationSec = duration, avgHr = if (duration != null) 120 else null)
    private fun ex(name: String, code: Int?, vararg sets: StrengthSetDto) = StrengthExerciseDto(name, code, sets.toList())

    private val zeppJson = """
        [{"name":"Barbell Squats","exercise_code":3,"sets":[{"reps":5,"weight_kg":60,"duration_sec":40,"avg_hr":120,"detected_reps":5,"extra":1}]},
         {"name":"Bench Press","exercise_code":6,"sets":[{"reps":5,"weight_kg":80,"duration_sec":40,"avg_hr":125,"detected_reps":null}]}]
    """.trimIndent()
    private val zepp = ZeppStrengthData(exercises = Json.parseToJsonElement(zeppJson), fingerprint = "z1-0000abcd-2")

    @Test
    fun `where the exercises came from, including rows from before the marker`() {
        assertEquals(ExercisesSource.NONE, exercisesSource(ExerciseSessionDetails()))
        assertEquals(ExercisesSource.ZEPP, exercisesSource(ExerciseSessionDetails(exercises = listOf(ex("Barbell Squats", 3, set(5, 60.0))))))
        assertEquals(ExercisesSource.APP, exercisesSource(ExerciseSessionDetails(exercises = listOf(ex("Back Squat", null, set(5, 60.0))))))
        assertEquals(ExercisesSource.APP, exercisesSource(ExerciseSessionDetails(exercises = listOf(ex("Barbell Squats", 3, set(5, 60.0))), exercisesSource = "app")))
    }

    @Test
    fun `Zepp's version is read leniently and a bad one is ignored`() {
        val exercises = zepp.zeppExercises()!!
        assertEquals(listOf("Barbell Squats", "Bench Press"), exercises.map { it.name })
        assertEquals(80.0, exercises[1].sets[0].weightKg, 1e-9)
        assertNull(ZeppStrengthData(exercises = Json.parseToJsonElement("""[{"name":1}]"""), fingerprint = "x").zeppExercises())
        assertNull(ZeppStrengthData().zeppExercises())
    }

    @Test
    fun `the choice is offered only when an app edit and Zepp's version differ`() {
        val fromZepp = ExerciseSessionDetails(exercises = listOf(ex("Barbell Squats", 3, set(5, 60.0))), exercisesSource = "zepp", zeppFingerprint = "old")
        assertEquals(ZeppVersionState.IN_SYNC, zeppVersionState(fromZepp, zepp))
        assertEquals(ZeppVersionState.NO_ZEPP_VERSION, zeppVersionState(fromZepp, null))

        val mine = ExerciseSessionDetails(exercises = listOf(ex("Front Squat", null, set(5, 60.0))), exercisesSource = "app", zeppFingerprint = "z1-older-1")
        assertEquals(ZeppVersionState.ZEPP_DIFFERS, zeppVersionState(mine, zepp))
        // Kept after seeing Zepp's current version: not asked again until Zepp changes.
        assertEquals(ZeppVersionState.IN_SYNC, zeppVersionState(mine.copy(zeppFingerprint = zepp.fingerprint), zepp))
        // An old edit with no fingerprint that matches Zepp's content is not a conflict.
        val sameAsZepp = ExerciseSessionDetails(
            exercises = listOf(ex("barbell squats", null, set(5, 60.0, null)), ex("Bench Press", null, set(5, 80.0, null))),
        )
        assertEquals(ZeppVersionState.IN_SYNC, zeppVersionState(sameAsZepp, zepp))
    }

    @Test
    fun `saving only RPE or notes keeps a Zepp list following Zepp, changing the exercises makes them the person's`() {
        val initial = ExerciseSessionDetails(exercises = listOf(ex("Barbell Squats", 3, set(5, 60.0))), exercisesSource = "zepp", zeppFingerprint = "z1-a-1")
        val unchanged = editedDetails(initial, null, null, initial.exercises)
        assertEquals("zepp", unchanged.exercisesSource)
        assertEquals("z1-a-1", unchanged.zeppFingerprint)
        // The Zepp-measured fields ride along, so an unchanged list compares equal.
        assertEquals(initial.exercises, unchanged.exercises)

        val changed = editedDetails(initial, null, null, listOf(ex("Barbell Squats", 3, set(5, 62.5))))
        assertEquals("app", changed.exercisesSource)
        assertEquals("z1-a-1", changed.zeppFingerprint)

        val cleared = editedDetails(initial, null, null, null)
        assertNull(cleared.exercisesSource)

        val run = editedDetails(ExerciseSessionDetails(), "trail", "easy", null)
        assertEquals("trail", run.routeType)
        assertEquals("easy", run.runType)
    }

    @Test
    fun `the comparison lists each exercise with its sets`() {
        assertEquals(listOf("Barbell Squats · 1 set", "Bench Press · 2 sets"), exerciseSummary(listOf(ex("Barbell Squats", 3, set(5, 60.0)), ex("Bench Press", 6, set(5, 80.0), set(5, 80.0)))))
    }
}
