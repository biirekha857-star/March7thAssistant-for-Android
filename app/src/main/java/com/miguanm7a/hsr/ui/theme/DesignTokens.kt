package com.miguanm7a.hsr.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计令牌，沿用 MAA Meow 的取值，保证与 MAA-Meow 观感一致。
 */
object MaaDesignTokens {

    object Spacing {
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
        val xl: Dp = 20.dp
        val xxl: Dp = 24.dp

        val listHorizontal: Dp = 16.dp
        val listItemVertical: Dp = 12.dp
        val rowTitleGap: Dp = 6.dp
        val sectionGap: Dp = 20.dp
    }

    object CornerRadius {
        val card: Dp = 12.dp
        val button: Dp = 10.dp
        val pill: Dp = 20.dp
        val inner: Dp = 8.dp
    }

    object Separator {
        val thickness: Dp = 0.5.dp
        val inset: Dp = 16.dp
    }

    object Card {
        val elevation: Dp = 0.dp
        val innerPadding: Dp = 16.dp
    }
}

object MaaThemeAlphas {
    const val DISABLED = 0.38f
    const val SECONDARY = 0.60f
    const val MEDIUM = 0.74f
}
