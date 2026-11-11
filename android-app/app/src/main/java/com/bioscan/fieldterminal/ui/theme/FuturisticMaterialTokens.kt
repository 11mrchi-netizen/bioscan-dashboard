package com.bioscan.fieldterminal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.score.DomainKey

/**
 * Field Terminal design tokens, v1 "new look" (design/tokens/tokens.css + tokens.json, mirrored one to one).
 *
 * One colour hierarchy drives everything:
 *  - background = domain: a soft wash of the domain hue behind the page ([Domain], drawn by `ftDomainWash`);
 *  - surface = neutral: faint-platinum frosted glass, never tinted by a domain or metric ([GlassFill] .. [GlassBorder]);
 *  - ink = metric: every metric keeps one fixed hue app-wide ([Metric]).
 *
 * The older names (Emerald, Warning, DomainFuel, Category...) are kept as aliases onto the v1 values so every screen
 * moved to the new palette at once; new code uses [Domain], [Metric], [Zone] and the semantic [Ok] / [Warn] / [Bad].
 */
object FuturisticMaterialTokens {
    // ---- surfaces (--ft-bg, --ft-fg, --ft-muted, --ft-dim, --ft-line) ----
    val Base = Color(0xFF070A0D)
    val Surface = Color(0xFF0E1317) // solid fallback under sheets and dialogs (no blur behind them)
    val Elevated = Color(0xFF151B21)
    val TextPrimary = Color(0xFFEAF0F2)
    val TextSecondary = Color(0xFF8793A2) // --ft-muted
    // Low-emphasis text. --ft-dim (#55606e) is below 4.5:1 on the ground, so text uses this lighter step and Dim is
    // kept for marks that are not text (off ticks, idle tracks).
    val TextMuted = Color(0xFF6C7786)
    val Dim = Color(0xFF55606E)
    val Line = Color(0x1AFFFFFF)

    // ---- semantic: status only (--ok, --warn, --bad) ----
    val Ok = Color(0xFF58E39A)
    val Warn = Color(0xFFFFC24B)
    val Bad = Color(0xFFFF5D6C)

    // Legacy semantic names, now the v1 semantic values. Emerald was the "primary signal"; in v1 the primary action is
    // platinum ([Platinum]) and green means ok.
    val Emerald = Ok
    val EmeraldHighlight = Color(0xFF9BF0C2)
    val EmeraldAtmosphere = Color(0xFF0E3B2A)
    val Info = Color(0xFF5AB0FF)
    val Warning = Warn
    val Critical = Bad
    val Analysis = Color(0xFFA78BFA) // the model/analysis layer; lab markers keep their own themes (LabMarkerThemes.kt)

    // ---- platinum: the primary action surface (--ft-platinum, --ft-platinum-ink) ----
    val PlatinumTop = Color(0xFFFFFFFF)
    val PlatinumBottom = Color(0xFFD6DDE8)
    val Platinum: Brush get() = Brush.verticalGradient(listOf(PlatinumTop, PlatinumBottom))
    val PlatinumInk = Color(0xFF0B0F14)
    /** Platinum as one colour, where an API takes a Color rather than a Brush (small selected chips). */
    val PlatinumSolid = Color(0xFFEEF2F7)
    /** A selected row that holds body text (the dock's selected tab): faint white fill and a light hairline. */
    val SelectedGlass = Color(0x1AFFFFFF)
    val SelectedBorder = Color(0x59FFFFFF)

    // ---- domain framing hues: page wash and hero gauge only, never a metric or a card ----
    object Domain {
        val Sleep = Color(0xFF8B7CFF)
        val Cardio = Color(0xFF2FD6C3) // recovery
        val Load = Color(0xFFFF8A5B)
        val Fuel = Color(0xFF8CE06B)
        val Balance = Color(0xFFFF6EC7)
        /** CHARGE: the holographic composite spectrum. Reserved for CHARGE and premium moments. */
        val Charge = listOf(Color(0xFF6D5EFC), Color(0xFF19C7E0), Color(0xFF5AF0C8), Color(0xFFC6F06A), Color(0xFFFF6EC7))
        /** The CHARGE gauge's radar glow. */
        val ChargeRadar = Color(0xFF3FE0D0)

        fun of(key: DomainKey): Color = when (key) {
            DomainKey.SLEEP -> Sleep
            DomainKey.RECOVERY -> Cardio
            DomainKey.LOAD -> Load
            DomainKey.FUEL -> Fuel
            DomainKey.BALANCE -> Balance
        }

        /** The two-stop gauge sweep of a domain ring (gauge.html's DOMAINS). */
        fun stops(key: DomainKey): List<Color> = when (key) {
            DomainKey.SLEEP -> listOf(Color(0xFF6D5EFC), Color(0xFFB48CFF))
            DomainKey.RECOVERY -> listOf(Color(0xFF19C7E0), Color(0xFF5AF0C8))
            DomainKey.LOAD -> listOf(Color(0xFFFF6B4D), Color(0xFFFFB15A))
            DomainKey.FUEL -> listOf(Color(0xFF4FD67A), Color(0xFFC6F06A))
            DomainKey.BALANCE -> listOf(Color(0xFFFF6EC7), Color(0xFFC08BFF))
        }
    }

    // ---- metric colours: fixed, app-wide ink (--m-*) ----
    object Metric {
        // cardio / blood / breath
        val HeartRate = Color(0xFFFF4D5E)
        val RestingHr = Color(0xFFFF5D8A)
        val MaxHr = Color(0xFFE03A4A)
        val Hrv = Color(0xFFFF6EC7)
        val Spo2 = Color(0xFF3A8DFF)
        val Respiratory = Color(0xFF2FD0D0)
        val BloodPressure = Color(0xFFE75BD0)
        val SkinTemp = Color(0xFFFFA23A)
        val Steps = Color(0xFF46C8A0)
        val Vo2max = Color(0xFF8B6CFF)

        // sleep
        val SleepScore = Color(0xFF6D8CFF)
        val SleepDuration = Color(0xFF4A7DFF)
        val StageAwake = Color(0xFFFFB04D)
        val StageRem = Color(0xFF9B6CFF)
        val StageLight = Color(0xFF4A7DFF)
        val StageDeep = Color(0xFF2342C9)
        val SleepingHr = HeartRate
        val Regularity = Color(0xFF36D07A)
        val Circadian = Color(0xFF5AB0FF)

        // body
        val Weight = Color(0xFF9AA6BD)
        val WeightEma = Color(0xFFC7D0E0)
        val BodyFat = Color(0xFFFF9A3D)
        // body fat by method, each its own hue so they read apart on one chart (scale keeps [BodyFat])
        val BodyFatTape = Color(0xFF38C6D4)
        val BodyFatCaliper = Color(0xFFA97BFF)
        val BodyFatDexa = Color(0xFFE86FB0)
        val LeanMass = Color(0xFF36D07A)
        val MusclePotential = Color(0xFF5AD1B0)
        val Measurements = Color(0xFF9B6CFF)
        val EnergyBalance = Color(0xFF4AA8FF)

        // balance / wellbeing
        val Mood = Color(0xFFFFD23A)
        val Energy = Color(0xFFFF9A3D)
        val Stress = Color(0xFFFF6B5B)
        val Soreness = Color(0xFFE0556B)
        val Arousal = Hrv // shares the HRV hue by request
        val PhysStress = Color(0xFFFF5D5D)
        val HrDip = Color(0xFF36D07A)
        val MealTiming = Color(0xFFFFB03A)

        // load / training
        val Ctl = Color(0xFF3A8DFF) // fitness
        val Atl = Color(0xFFFF844D) // fatigue
        val Tsb = Color(0xFF36D07A) // form
        val Trimp = Color(0xFFFF6B4D)
        val Srpe = Color(0xFFFF9A4D)
        val Rpe = Color(0xFFFF7A3A)
        val Pace = Color(0xFF4AD6C0)
        val Speed = Color(0xFF35C7E0)
        val Power = Color(0xFFFFD23A)
        val Cadence = Color(0xFF9B6CFF)
        val Distance = Color(0xFF5AB0FF)
        val Duration = Color(0xFF9AA6BD)
        val Elevation = Color(0xFFB98A4D)
        val Grip = Color(0xFFE0556B)

        // fuel / nutrition
        val Calories = Color(0xFF8CE06B)
        val Carbs = Color(0xFFFF9A3D)
        val Protein = Color(0xFFFF4D6A)
        val Fat = Color(0xFFFFD23A)
        val Fiber = Color(0xFF5AC86B)
        val Sugar = Color(0xFFFF6EC7)
        val Sodium = Weight
        val Hydration = Color(0xFF3AA8FF)
        val Caffeine = Color(0xFFB98A4D)
        val SweatRate = Color(0xFF35C7E0)

        // digestion: stool consistency
        val StoolHard = Color(0xFF8A5A2B)
        val StoolHealthy = Color(0xFFC79A5A)
        val StoolLoose = Color(0xFFE0A94D)
        val StoolLiquid = Color(0xFFE0774D)
        val GutComfort = Color(0xFF8CE06B)

        // running: kinds of run (the Running page's stacked weeks)
        val RunTrail = Color(0xFF2E9E5B)
        val RunEasy = Color(0xFF3B86D6)
        val RunQuality = Color(0xFFE8692E)

        // routine: how a trip was travelled (the day map's path)
        val ModeOnFoot = Color(0xFF199E70)
        val ModeBicycle = Color(0xFFC98500)
        val ModeVehicle = Color(0xFF3987E5)
        val ModeStop = Color(0xFFD95926)
        val ModeWaiting = Color(0xFF6B7178)
        val ModeUnknown = Color(0xFF3A3F45)

        // routine
        val PhoneSleep = Color(0xFF9B6CFF)
        val NightScreen = Color(0xFF7A6CFF)
        val Daylight = Color(0xFFFFD23A)
        val Commute = Color(0xFF5AB0FF)
        val ActiveTravel = Color(0xFF36D07A)
        // routine: kinds of time on the 24-hour ribbon (asleep is SleepDuration, on the move Commute)
        val TimeHome = Color(0xFF2FB8A0)
        val TimeWork = Color(0xFFE8C26A)
        val TimeOut = Color(0xFFFF8FB1)
        val TimeTraining = Color(0xFFFF6A3D)
        val TimeLogged = Color(0xFFB8E04A)
        val TimePhone = Color(0xFFA7B0FF)
    }

    // ---- ordered ramps (--z-*) ----
    object Zone {
        /** Heart-rate zones 1..5. */
        val Hr = listOf(Color(0xFF6F86B0), Color(0xFF3AA0FF), Color(0xFF35C77A), Color(0xFFFFB03A), Color(0xFFFF4D4D))
        val StressCalm = Color(0xFF36D07A)
        val StressModerate = Color(0xFFFFB03A)
        val StressHigh = Color(0xFFFF4D4D)
    }

    // Section accents (legacy names): identify a section in its header or a destination, never a health state. They
    // now resolve to v1 domain or metric hues.
    val DomainTraining = Domain.Load
    val DomainFuel = Domain.Fuel
    val DomainHeart = Metric.HeartRate // used as the heart-rate ink on charts; the Health page's wash is Domain.Cardio
    val DomainLabs = Analysis
    val DomainMap = Color(0xFF5AB0FF)
    val DomainLog = Color(0xFFFFB03A)
    val DomainUser = Domain.Sleep

    // Category tier: "what kind of thing is this" (a log entry kind, a chart series). Identity marks only (dot, rail,
    // icon, chip outline, series stroke). The 500 anchor of each is a v1 metric hue.
    class CategoryColor(val c300: Color, val c500: Color, val c700: Color, val c900: Color)

    object Category {
        val Sleep = CategoryColor(Color(0xFF8FA6FF), Metric.SleepDuration, Metric.StageDeep, Color(0xFF14246E))
        val Activity = CategoryColor(Color(0xFFFFB79A), Domain.Load, Color(0xFFC75A2E), Color(0xFF6B2E14))
        val Wellbeing = CategoryColor(Color(0xFFFFE27A), Metric.Mood, Color(0xFFB8941A), Color(0xFF5C4A0D))
        val Arousal = CategoryColor(Color(0xFFFFA8DC), Metric.Arousal, Color(0xFFB8418C), Color(0xFF5C2046))
        val Digestion = CategoryColor(Color(0xFFE3C79A), Metric.StoolHealthy, Color(0xFF8F6A3A), Color(0xFF4A361D))
        val Intake = CategoryColor(Color(0xFFBDF0A6), Metric.Calories, Color(0xFF5AA83E), Color(0xFF2D5420))
    }

    // 4 px base scale (contract section 6).
    object Space {
        val XS = 4.dp
        val SM = 8.dp
        val MD = 12.dp
        val LG = 16.dp
        val XL = 20.dp
        val XXL = 24.dp
        val Huge = 32.dp
    }

    // ---- neutral frosted glass (.ft-card / .ft-tile) ----
    /** Top-left stop of the glass gradient (white 8.5%). */
    val GlassFill = Color(0x16FFFFFF)
    /** Where the glass gradient ends, 60% of the way across (white 2.8%). */
    val GlassFillEnd = Color(0x07FFFFFF)
    val GlassStrongFill = Color(0x1FFFFFFF)
    val GlassBorder = Color(0x1FFFFFFF)
    /** The inset top highlight of a glass card (white 15%). */
    val GlassHighlight = Color(0x26FFFFFF)
    /** Progress and bullet tracks (white 8%). */
    val GlassTrack = Color(0x14FFFFFF)
    val Glass: Brush get() = Brush.linearGradient(0f to GlassFill, 0.6f to GlassFillEnd, 1f to GlassFillEnd)

    val RadiusSmall = 12.dp // buttons, segments, chips that are not pills
    val RadiusModule = 16.dp // tiles
    val RadiusCard = 18.dp // cards
    val RadiusCardLarge = 24.dp // hero cards
    val RadiusDock = 26.dp
    val BorderWidth = 1.dp

    // Modal bottom sheets: soft top corners at the hero-card radius.
    val SheetShape = RoundedCornerShape(topStart = RadiusCardLarge, topEnd = RadiusCardLarge)
}
