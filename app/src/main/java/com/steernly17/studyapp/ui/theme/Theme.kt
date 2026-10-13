package com.steernly17.studyapp.ui.theme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BluePrimary,
    background = NavyDark,
    surface = NavyCard,
    onBackground = TextWhite,
    onSurface = TextWhite,
    surfaceVariant = NavyLightCard
)

@Composable
fun StudyAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, content = content)
}