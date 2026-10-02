package dev.nymph.lyrics.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Palette.Primary,
    onPrimary = Color.White,
    secondary = Palette.RangeHighlight,
    onSecondary = Palette.RangeHighlightText,
    background = Palette.CanvasBackground,
    onBackground = Palette.Text,
    surface = Palette.Sidebar,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Card,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Sidebar,
    surfaceContainerLow = Palette.Sidebar,
    surfaceContainerHigh = Palette.Card,
    surfaceContainerHighest = Palette.Border,
    outline = Palette.InputBorder,
    outlineVariant = Palette.Border,
    error = Palette.Error,
)

/** Dark Material 3 theme in the web client's colours. */
@Composable
fun LyricsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
