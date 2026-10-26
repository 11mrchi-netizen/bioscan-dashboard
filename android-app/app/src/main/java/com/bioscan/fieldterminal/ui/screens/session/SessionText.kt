package com.bioscan.fieldterminal.ui.screens.session

// Help texts and formatters shared by the session page's cards (SessionDetailScreen.kt and RunSessionCards.kt).

internal const val MOUNTAIN_INDEX_HELP =
    "How steep this route was on average: elevation gain (m) ÷ distance (km). The 40s-60s m/km range is a hilly trail run; over 100 is seriously mountainous, UTMB-style climbing."
internal const val KM_EFFORT_HELP =
    "One \"effort kilometre\" per km of distance plus one per 100 m climbed -- the official FFA/ITRA formula for a route's real size, since a flat 20K and a mountainous 20K are not the same effort."
internal const val VAM_HELP =
    "Vertical Ascent in Meters per hour -- how fast you climbed, borrowed from cycling. A brisk hike is roughly 400-600 m/h; strong trail runners sustain 800-1000+ on steep, runnable climbs."
internal const val GRADE_EFFICIENCY_HELP =
    "Climbing (or descending) speed per heartbeat: vertical metres per hour ÷ average heart rate on that segment -- the trail equivalent of Efficiency Factor. Higher means faster for the same effort."
internal const val CONSISTENCY_HELP =
    "How even your pace was across this run's climbs (or descents), as a coefficient of variation. Lower means more consistent climb to climb; higher means some hit much harder than others."
internal const val PACE_DEGRADATION_HELP =
    "How much your grade-adjusted pace slowed from the first half of comparable segments to the second. Positive means you faded; near zero means you held pace well."
internal const val VAM_DEGRADATION_HELP =
    "Same idea as pace degradation, for climbing speed: how much slower you climbed late in the run versus early, on matched climbs."
internal const val HR_DECOUPLING_HELP =
    "How much your effort-to-heart-rate ratio drifted from the first half of this run to the second. Under about 5% suggests a solid aerobic effort; a bigger drift means heart rate crept up for the same output -- heat, dehydration or fatigue."
internal const val GAP_HELP =
    "Grade-Adjusted Pace: your pace converted to its flat-ground equivalent using the Minetti et al. running-cost formula, so a hilly split doesn't look artificially slow next to a flat one."
internal const val EF_SESSION_HELP =
    "Efficiency Factor for this one run: grade-adjusted speed ÷ average heart rate. Compare it against the Training tab's 28-day trend rather than any single other run -- one run swings too much with heat, sleep and terrain to read alone."
internal const val GROUND_CONTACT_HELP =
    "Ground contact time -- how long each foot spends on the ground per stride, in milliseconds. Lower generally means a springier stride; it naturally rises as you slow down or tire."
internal const val VERTICAL_RATIO_HELP =
    "Vertical oscillation ratio -- how much you bounce up and down relative to your stride length. Lower means more of your effort goes into forward motion rather than bouncing."
internal const val STRIDE_LENGTH_HELP =
    "Average distance covered per stride. Naturally shorter uphill and on technical terrain, longer on flat, fast sections."
internal const val STRIDE_FOR_PACE_HELP =
    "Stride lengthens with speed, so its mark compares it with your own stride at this pace: a straight line through " +
        "your runs on the same surface over the last 90 days, from 6 runs. LONG FOR THIS PACE (8 % or more over it) is " +
        "the watch's hint of overstriding: the same speed from fewer, longer steps, which costs energy (Cavanagh & " +
        "Williams 1982) and loads the knee and hip more than a quicker step (Heiderscheit et al. 2011). Whether the foot " +
        "really lands ahead of the body only a video shows: try the running form analyser. SHORT FOR THIS PACE is a " +
        "quicker step for the speed; that is not a known risk by itself, but a sudden drop can mean fatigue, a niggle " +
        "or slippery ground."
internal const val LACTATE_THRESHOLD_HELP =
    "Your watch's own rolling estimate of the heart rate and pace you could sustain for about an hour before lactate builds up faster than you can clear it -- recomputed after each qualifying run, not from one dedicated test."

internal fun formatSplitPace(durationSec: Long): String {
    val mins = durationSec / 60
    val secs = durationSec % 60
    return "%d:%02d /km".format(mins, secs)
}

internal fun formatDuration(totalMinutes: Double): String {
    val h = (totalMinutes / 60).toInt()
    val m = (totalMinutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
