package com.bioscan.fieldterminal.data

import android.content.Context

// Local-only view settings for the run session page: which RUN DYNAMICS sections the person folded. Every section
// starts open; a fold is remembered for every run, so a section added later still opens by default.
object RunPageSettingsStore {
    private const val PREFS_NAME = "run_page_settings"
    private const val PREF_FOLDED = "folded_dynamics_sections"

    fun foldedSections(context: Context): Set<String> = prefs(context).getStringSet(PREF_FOLDED, null)?.toSet().orEmpty()

    fun saveFoldedSections(context: Context, folded: Set<String>) {
        prefs(context).edit().putStringSet(PREF_FOLDED, folded).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
