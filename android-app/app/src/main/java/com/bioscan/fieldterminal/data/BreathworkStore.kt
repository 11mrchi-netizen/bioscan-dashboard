package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.breath.BreathPattern
import com.bioscan.fieldterminal.domain.breath.BreathPatternCodec

// Breathwork's own patterns, kept on this phone only (not synced): one encoded line per pattern
// (domain/breath/BreathPattern.kt's BreathPatternCodec). The presets are code, never stored. A preset's rounds the user
// last chose are remembered too, so a preset starts where it was left.
object BreathworkStore {
    private const val PREFS_NAME = "breathwork"
    private const val PREF_CUSTOM = "custom_patterns"
    private const val PREF_NEXT_ID = "next_custom_id"
    private const val PREF_ROUNDS_PREFIX = "rounds:"

    fun customPatterns(context: Context): List<BreathPattern> =
        prefs(context).getString(PREF_CUSTOM, null).orEmpty()
            .lines().filter { it.isNotBlank() }.mapNotNull(BreathPatternCodec::decode)

    /** Adds [pattern] (a new id is given when its id is blank) or replaces the one with its id. Returns what was saved. */
    fun save(context: Context, pattern: BreathPattern): BreathPattern {
        val p = prefs(context)
        val saved = if (pattern.id.isBlank()) {
            val next = p.getInt(PREF_NEXT_ID, 1)
            p.edit().putInt(PREF_NEXT_ID, next + 1).apply()
            pattern.copy(id = "custom:$next")
        } else pattern
        val list = customPatterns(context).filterNot { it.id == saved.id } + saved
        write(context, list)
        return saved
    }

    fun delete(context: Context, id: String) = write(context, customPatterns(context).filterNot { it.id == id })

    fun presetRounds(context: Context, id: String): Int? =
        prefs(context).getInt(PREF_ROUNDS_PREFIX + id, 0).takeIf { it > 0 }

    fun savePresetRounds(context: Context, id: String, rounds: Int) {
        prefs(context).edit().putInt(PREF_ROUNDS_PREFIX + id, rounds).apply()
    }

    private fun write(context: Context, list: List<BreathPattern>) {
        prefs(context).edit().putString(PREF_CUSTOM, list.joinToString("\n", transform = BreathPatternCodec::encode)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
