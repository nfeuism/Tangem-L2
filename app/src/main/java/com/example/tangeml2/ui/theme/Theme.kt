package com.example.tangeml2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val LightScheme = lightColorScheme(
    primary = ElectricBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE7FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF06214F),
    secondary = Mint,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = Amber,
    background = Paper,
    onBackground = Ink,
    surface = PaperCard,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkMuted,
    surfaceContainer = PaperCard,
    surfaceContainerHigh = PaperVariant,
    outline = Hairline,
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFE4E9F1),
    error = Coral,
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFCE4E4),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF5F1212),
)

private val DarkScheme = darkColorScheme(
    primary = ElectricBlueDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF06152E),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF17325E),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E3FF),
    secondary = MintDark,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF00301F),
    tertiary = AmberDark,
    background = Night,
    onBackground = Slate,
    surface = NightCard,
    onSurface = Slate,
    surfaceVariant = NightVariant,
    onSurfaceVariant = SlateMuted,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightVariant,
    outline = NightHairline,
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF232D39),
    error = CoralDark,
    onError = androidx.compose.ui.graphics.Color(0xFF3B0A0A),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF4A1A1A),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFC9C4),
)

/**
 * Brand-locked theme: dynamic colour is deliberately off so the app looks the same
 * on every device (this is a wallet, consistency is a trust signal).
 */
@Composable
fun TangemL2Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val semantic = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalAppColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Shorthand for the semantic palette. */
val MaterialTheme.appColors: AppSemanticColors
    @Composable
    get() = LocalAppColors.current
