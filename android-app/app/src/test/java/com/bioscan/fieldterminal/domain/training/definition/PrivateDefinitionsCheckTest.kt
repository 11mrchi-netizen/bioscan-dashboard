package com.bioscan.fieldterminal.domain.training.definition

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Validates the user's PRIVATE definition files with the real parser, without the
// program content ever living in the repository. Skipped unless TB_DEFS_DIR points at
// a directory of *.json definitions. Writes a readable rendering next to it
// (TB_DEFS_DIR/../render.txt) for the user's review.
class PrivateDefinitionsCheckTest {
    @Test
    fun privateDefinitionsParseValidateAndRender() {
        val dir = System.getenv("TB_DEFS_DIR")?.let(::File)
        assumeTrue("TB_DEFS_DIR not set", dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        val defs = files.map { f -> runCatching { parseDefinition(f.readText()) }.getOrElse { throw AssertionError("${f.name}: ${it.message}") } }
        val report = validateSet(defs)
        File(dir.parentFile, "render.txt").writeText(
            defs.sortedWith(compareBy({ it::class.simpleName }, { it.key })).joinToString("\n\n") { renderDefinition(it) } +
                "\n\n--- WARNINGS ---\n" + report.warnings.joinToString("\n"),
        )
        assertTrue("${files.size} definitions, errors:\n" + report.errors.joinToString("\n"), report.ok)
    }
}
