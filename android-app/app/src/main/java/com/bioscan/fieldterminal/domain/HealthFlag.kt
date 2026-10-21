package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Status hub's body figure: the one open health flag it points at, and where on the figure the pin goes. Pure, so the
// screen only draws the result. Injuries come first (they are what the figure is for); an illness has no body region.

data class HealthFlag(
    // The injured body part as typed ("Knee (Right)"), or the illness name.
    val label: String,
    val isInjury: Boolean,
    val since: LocalDate?,
)

// Day 1 is the start date itself, matching how the Injury tab counts days.
fun healthFlagDay(flag: HealthFlag, today: LocalDate): Int? =
    flag.since?.let { (ChronoUnit.DAYS.between(it, today) + 1).toInt().coerceAtLeast(1) }

// The most recently started open injury, else the most recently started open illness.
fun pickHealthFlag(injuries: List<HealthFlag>, illnesses: List<HealthFlag>): HealthFlag? =
    injuries.maxByOrNull { it.since ?: LocalDate.MIN } ?: illnesses.maxByOrNull { it.since ?: LocalDate.MIN }

// A point in the figure's 260 x 200 viewBox (BodyConsole.kt draws in the same space).
data class FigurePoint(val x: Float, val y: Float)

// Where the pin goes and the short label written beside it (there is room for about ten characters next to the figure;
// the full text stays in the accessibility description).
data class FigurePin(val point: FigurePoint, val label: String)

private const val CENTER_X = 130f

// Where the pin sits for a free-text body part. The figure faces the viewer and, as in the original mockup ("R KNEE" at
// x = 150), the person's right side is drawn on the right. A part with no side is pinned on the right; a part this list
// does not know is pinned at the pelvis rather than guessed onto a limb.
fun figurePinForBodyPart(part: String): FigurePin {
    val words = part.lowercase().replace(Regex("[^a-z ]"), " ").split(' ').filter { it.isNotBlank() }.toSet()
    val left = "left" in words || "l" in words || "lt" in words
    val right = "right" in words || "r" in words || "rt" in words
    val side = if (left) -1f else 1f
    val sidePrefix = when {
        left -> "L "
        right -> "R "
        else -> ""
    }
    fun has(vararg keys: String) = keys.any { it in words }
    fun center(y: Float, name: String) = FigurePin(FigurePoint(CENTER_X, y), name)
    fun sided(dx: Float, y: Float, name: String) = FigurePin(FigurePoint(CENTER_X + side * dx, y), sidePrefix + name)
    // Limbs before the trunk, so "back of the knee" is a knee and "lower back and hip" a hip.
    return when {
        has("head", "skull", "face", "jaw", "eye", "ear", "concussion") -> center(22f, "HEAD")
        has("neck", "cervical", "trap", "traps", "trapezius") -> center(44f, "NECK")
        has("shoulder", "shoulders", "rotator", "deltoid", "delt", "clavicle", "collarbone") -> sided(25f, 58f, "SHOULDER")
        has("elbow", "elbows") -> sided(43f, 92f, "ELBOW")
        has("wrist", "wrists", "hand", "hands", "finger", "fingers", "thumb", "forearm", "forearms") -> sided(46f, 118f, "HAND")
        has("arm", "arms", "bicep", "biceps", "tricep", "triceps") -> sided(34f, 75f, "ARM")
        has("knee", "knees", "patella", "patellar", "meniscus", "acl", "mcl", "itb") -> sided(20f, 146f, "KNEE")
        has("ankle", "ankles", "foot", "feet", "heel", "toe", "toes", "plantar", "arch") -> sided(23f, 186f, "FOOT")
        has("shin", "shins", "calf", "calves", "achilles", "tibia", "soleus") -> sided(21f, 166f, "CALF")
        has("thigh", "thighs", "hamstring", "hamstrings", "quad", "quads", "quadriceps", "femur") -> sided(19f, 125f, "THIGH")
        has("hip", "hips", "glute", "glutes", "groin", "adductor", "adductors", "pelvis", "si") -> sided(15f, 104f, "HIP")
        has("chest", "pec", "pecs", "pectoral", "rib", "ribs", "sternum") -> sided(14f, 66f, "CHEST")
        has("back", "spine", "lumbar", "thoracic", "lat", "lats") -> center(96f, "BACK")
        has("abdomen", "stomach", "abs", "abdominal", "core", "oblique", "obliques") -> center(96f, "CORE")
        else -> center(104f, "INJURY")
    }
}
