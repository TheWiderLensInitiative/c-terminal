package info.thewiderlens.cterminal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// From the logo: neon mint on black, brushed silver.
val Mint = Color(0xFF5CFDED)
val MintDeep = Color(0xFF1ED6A6)
val Silver = Color(0xFFD7DEE0)
val Ink = Color(0xFF05080A)
val Panel = Color(0xFF0E1517)
val PanelHigh = Color(0xFF162124)
val Danger = Color(0xFFFF5F6D)
val Mono = FontFamily.Monospace

@Composable
fun CTerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint, onPrimary = Ink, secondary = MintDeep, onSecondary = Ink,
            background = Ink, onBackground = Silver, surface = Panel, onSurface = Silver,
            surfaceVariant = PanelHigh, onSurfaceVariant = Color(0xFF9DB0B2),
            surfaceContainer = Panel, surfaceContainerHigh = PanelHigh, surfaceContainerHighest = PanelHigh,
            surfaceContainerLow = Panel, surfaceContainerLowest = Ink,
            outline = Color(0xFF2E4246), outlineVariant = Color(0xFF1E2C2F), error = Danger,
        ),
        content = content,
    )
}
