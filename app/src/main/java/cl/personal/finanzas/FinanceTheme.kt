package cl.personal.finanzas

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object FinancePalette {
    val Navy = Color(0xFF071F50)
    val NavyDeep = Color(0xFF041637)
    val Cyan = Color(0xFF3AC9E8)
    val Surface = Color(0xFFF4F7FC)
    val Ink = Color(0xFF18243D)
    val Muted = Color(0xFF5A687F)
    val Border = Color(0xFFE2E8F3)
}

private val financeColors = lightColorScheme(
    primary = FinancePalette.Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEAF7),
    onPrimaryContainer = FinancePalette.Navy,
    secondary = Color(0xFF216A86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F7FB),
    onSecondaryContainer = FinancePalette.NavyDeep,
    tertiary = Color(0xFF316D59),
    tertiaryContainer = Color(0xFFDEF4E9),
    background = FinancePalette.Surface,
    onBackground = FinancePalette.Ink,
    surface = Color.White,
    onSurface = FinancePalette.Ink,
    onSurfaceVariant = FinancePalette.Muted,
    surfaceVariant = Color(0xFFEBF0F7),
    outline = Color(0xFFB9C5D4),
    outlineVariant = FinancePalette.Border,
    error = Color(0xFFB32635)
)

@Composable
fun FinanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = financeColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(30.dp)
        ),
        content = content
    )
}
