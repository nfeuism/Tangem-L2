package com.example.tangeml2.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Brand palette ────────────────────────────────────────────────────────────
// A calm, high-contrast "vault" palette. Every text/background pairing below was
// chosen to clear ~4.5:1; v1 hard-coded light greys onto a DayNight theme, which
// made text disappear in dark mode.

val ElectricBlue = Color(0xFF1B60E0)
val ElectricBlueDark = Color(0xFF5B8DFF)
val Mint = Color(0xFF0EA47A)
val MintDark = Color(0xFF34D399)
val Amber = Color(0xFFB06E00)
val AmberDark = Color(0xFFFBBF24)
val Coral = Color(0xFFC62828)
val CoralDark = Color(0xFFFF8A80)

val Ink = Color(0xFF0F1620)
val InkMuted = Color(0xFF54657A)
val Paper = Color(0xFFF6F8FB)
val PaperCard = Color(0xFFFFFFFF)
val PaperVariant = Color(0xFFEDF1F7)
val Hairline = Color(0xFFD3DBE6)

val Slate = Color(0xFFE6EDF5)
val SlateMuted = Color(0xFF9AA9BC)
val Night = Color(0xFF0B0F14)
val NightCard = Color(0xFF141A21)
val NightVariant = Color(0xFF1C242E)
val NightHairline = Color(0xFF2A3441)

/**
 * Semantic colours that Material 3's [androidx.compose.material3.ColorScheme] does
 * not model (success / warning / neutral surfaces).
 */
@Immutable
data class AppSemanticColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val codeSurface: Color,
)

val LightSemanticColors = AppSemanticColors(
    success = Mint,
    successContainer = Color(0xFFDDF5EC),
    onSuccessContainer = Color(0xFF04432F),
    warning = Amber,
    warningContainer = Color(0xFFFDF0D5),
    onWarningContainer = Color(0xFF5C3A00),
    danger = Coral,
    dangerContainer = Color(0xFFFCE4E4),
    onDangerContainer = Color(0xFF5F1212),
    neutralContainer = PaperVariant,
    onNeutralContainer = Ink,
    codeSurface = Color(0xFFEFF3F9),
)

val DarkSemanticColors = AppSemanticColors(
    success = MintDark,
    successContainer = Color(0xFF0C3B2E),
    onSuccessContainer = Color(0xFF9CEBCE),
    warning = AmberDark,
    warningContainer = Color(0xFF3D2E0A),
    onWarningContainer = Color(0xFFF7D794),
    danger = CoralDark,
    dangerContainer = Color(0xFF4A1A1A),
    onDangerContainer = Color(0xFFFFC9C4),
    neutralContainer = NightVariant,
    onNeutralContainer = Slate,
    codeSurface = Color(0xFF10161D),
)

val LocalAppColors = staticCompositionLocalOf { LightSemanticColors }
