package com.miguanm7a.hsr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightBackground = Color(0xFFF5F2ED)
private val LightSurface = Color(0xFFF9F7F3)
private val LightSurfaceVariant = Color(0xFFE8E4DE)
private val LightOnSurface = Color(0xFF1C1B18)
private val LightOnSurfaceVariant = Color(0xFF8A8580)
private val LightOutline = Color(0xFFC9C4BE)

private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1C1C1E)
private val DarkSurfaceVariant = Color(0xFF2C2C2E)
private val DarkOnSurface = Color(0xFFFFFFFF)
private val DarkOnSurfaceVariant = Color(0xFF98989D)
private val DarkOutline = Color(0xFF3A3A3C)

private val PureDarkBackground = Color(0xFF000000)
private val PureDarkSurface = Color(0xFF000000)
private val PureDarkSurfaceVariant = Color(0xFF121212)

private fun createLightColorScheme(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = Color(0xFF8A8580),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E4DE),
    onSecondaryContainer = Color(0xFF1C1B18),
    tertiary = primary.copy(alpha = 0.8f),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = primaryContainer.copy(alpha = 0.5f),
    onTertiaryContainer = onPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightSurfaceVariant,
    error = Color(0xFFF53F3F),
    onError = Color.White,
    errorContainer = Color(0xFFFFD8D6),
    onErrorContainer = Color(0xFF690005),
)

private fun createDarkColorScheme(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    isPureDark: Boolean = false,
): ColorScheme {
    val bg = if (isPureDark) PureDarkBackground else DarkBackground
    val surface = if (isPureDark) PureDarkSurface else DarkSurface
    val surfaceVariant = if (isPureDark) PureDarkSurfaceVariant else DarkSurfaceVariant

    return darkColorScheme(
        primary = primary,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = Color(0xFF98989D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF2C2C2E),
        onSecondaryContainer = Color(0xFFE5E5EA),
        tertiary = primary.copy(alpha = 0.8f),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = primaryContainer.copy(alpha = 0.5f),
        onTertiaryContainer = onPrimaryContainer,
        background = bg,
        onBackground = DarkOnSurface,
        surface = surface,
        onSurface = DarkOnSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = DarkOutline,
        outlineVariant = surfaceVariant,
        error = Color(0xFFFF453A),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )
}

// 三月七（March7thAssistant）主题粉
private val March7Light = createLightColorScheme(
    primary = Color(0xFFD9739E),
    primaryContainer = Color(0xFFFFE3EF),
    onPrimaryContainer = Color(0xFF4A0E2A),
)

private val March7Dark = createDarkColorScheme(
    primary = Color(0xFFF18CB9),
    primaryContainer = Color(0xFF6E2A48),
    onPrimaryContainer = Color(0xFFFFD9E7),
)

private val March7PureDark = createDarkColorScheme(
    primary = Color(0xFFF18CB9),
    primaryContainer = Color(0xFF6E2A48),
    onPrimaryContainer = Color(0xFFFFD9E7),
    isPureDark = true,
)

// MAA Meow 主题蓝
private val MaaLight = createLightColorScheme(
    primary = Color(0xFF2B6BCA),
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF002453),
)

private val MaaDark = createDarkColorScheme(
    primary = Color(0xFF2B6BCA),
    primaryContainer = Color(0xFF004088),
    onPrimaryContainer = Color(0xFFD6E8FF),
)

private val MaaPureDark = createDarkColorScheme(
    primary = Color(0xFF2B6BCA),
    primaryContainer = Color(0xFF004088),
    onPrimaryContainer = Color(0xFFD6E8FF),
    isPureDark = true,
)

val MaaShapes = Shapes(
    extraSmall = RoundedCornerShape(MaaDesignTokens.CornerRadius.inner),
    small = RoundedCornerShape(MaaDesignTokens.CornerRadius.button),
    medium = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
    large = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
    extraLarge = RoundedCornerShape(MaaDesignTokens.CornerRadius.pill),
)

enum class ThemeMode { SYSTEM, WHITE, DARK, PURE_DARK }

/** 强调色主题：三月七粉 / MAA 蓝。 */
enum class AccentTheme { MARCH7, MAA }

@Composable
fun MaaTermuxTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentTheme: AccentTheme = AccentTheme.MARCH7,
    useSystemMonetColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.WHITE -> false
        ThemeMode.DARK, ThemeMode.PURE_DARK -> true
    }
    val isPureDark = themeMode == ThemeMode.PURE_DARK

    val colorScheme: ColorScheme = remember(themeMode, accentTheme, useSystemMonetColor, isDark, context) {
        when {
            useSystemMonetColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val dynamic =
                    if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                if (isPureDark) {
                    dynamic.copy(
                        background = PureDarkBackground,
                        surface = PureDarkSurface,
                        surfaceVariant = PureDarkSurfaceVariant,
                    )
                } else {
                    dynamic
                }
            }

            accentTheme == AccentTheme.MARCH7 -> when (themeMode) {
                ThemeMode.SYSTEM -> if (systemDark) March7Dark else March7Light
                ThemeMode.WHITE -> March7Light
                ThemeMode.DARK -> March7Dark
                ThemeMode.PURE_DARK -> March7PureDark
            }

            else -> when (themeMode) {
                ThemeMode.SYSTEM -> if (systemDark) MaaDark else MaaLight
                ThemeMode.WHITE -> MaaLight
                ThemeMode.DARK -> MaaDark
                ThemeMode.PURE_DARK -> MaaPureDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = MaaShapes,
        content = content,
    )
}
