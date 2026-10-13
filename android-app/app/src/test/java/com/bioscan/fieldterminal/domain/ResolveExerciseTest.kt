package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseLibraryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveExerciseTest {
    private val library = listOf("Standing Military Press", "Front Barbell Squat", "Pullups", "Weighted Pull Ups", "Rocky Pull-Ups/Pulldowns")
        .mapIndexed { i, n -> ExerciseLibraryRow(id = "$i", name = n) }

    @Test
    fun exactNameWinsAndZeppNamesResolveToTheLibrary() {
        assertEquals("Weighted Pull Ups", resolveExercise("weighted pull ups", library)?.name)
        assertEquals("Standing Military Press", resolveExercise("Standing Barbell Press", library)?.name)
        assertEquals("Front Barbell Squat", resolveExercise("Barbell Front Squat", library)?.name)
        assertEquals("Pullups", resolveExercise("Pull Up", library)?.name)
    }

    @Test
    fun unknownNamesStayUnresolved() {
        assertNull(resolveExercise("Zercher Squat", library))
        assertNull(resolveExercise("", library))
    }
}
