package com.bioscan.fieldterminal.domain

// Shared period selector for the running-totals widgets on Training
// (distance) and Nutrition (macros) -- one enum so both screens' toggles
// stay in sync in meaning, even though each computes its own sum over the
// data it already has client-side (no new tables, no new queries per period
// switch).
enum class TotalsPeriod(val label: String, val days: Long) {
    Day("1D", 1),
    Week("7D", 7),
    Month("30D", 30),
    Quarter("90D", 90),
}
