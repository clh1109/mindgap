package com.mindgap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7A46),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F5E4),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF3B6653),
    secondaryContainer = Color(0xFFDCEFE3),
    surfaceVariant = Color(0xFFEDF1ED)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF64DFA1),
    onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF00522D),
    onPrimaryContainer = Color(0xFFD8F5E4),
    secondary = Color(0xFFA8CDB9),
    secondaryContainer = Color(0xFF244B3A),
    surfaceVariant = Color(0xFF262A27)
)

@Composable
fun MindGapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
