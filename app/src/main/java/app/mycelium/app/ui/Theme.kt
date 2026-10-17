package app.mycelium.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.mycelium.app.R
import kotlin.math.max
import kotlin.math.roundToInt

/** Design tokens from docs/ui-plan.md. Day and night palettes; components read colors from [LocalPal]. */
@Immutable
data class Pal(
    val bg: Color, val paper: Color, val paperEdge: Color,
    val wood: Color, val woodDark: Color, val woodLight: Color,
    val ink: Color, val inkMuted: Color,
    val leaf: Color, val moss: Color, val gold: Color, val berry: Color, val sky: Color,
    val topBar: Color, val onAccent: Color, val night: Boolean,
)

val DayPal = Pal(
    bg = Color(0xFFF3E3C3), paper = Color(0xFFFBF1DA), paperEdge = Color(0xFFE2CC9F),
    wood = Color(0xFF9A6233), woodDark = Color(0xFF5C3A1E), woodLight = Color(0xFFC28A52),
    ink = Color(0xFF3B2A1A), inkMuted = Color(0xFF7D6A55),
    leaf = Color(0xFF5E9C3A), moss = Color(0xFF3F6B2A), gold = Color(0xFFE3A73C), berry = Color(0xFFC8553D), sky = Color(0xFF8CC7DE),
    topBar = Color(0xFF9A6233), onAccent = Color(0xFFFFF8E7), night = false,
)

val NightPal = Pal(
    bg = Color(0xFF1A1E2E), paper = Color(0xFF2A2F45), paperEdge = Color(0xFF3A4060),
    wood = Color(0xFF5E3D24), woodDark = Color(0xFF2E1D10), woodLight = Color(0xFF7E5434),
    ink = Color(0xFFF2E8D2), inkMuted = Color(0xFFA99F8E),
    leaf = Color(0xFF7FC15A), moss = Color(0xFF4E8A35), gold = Color(0xFFF2C14E), berry = Color(0xFFE2775E), sky = Color(0xFF3E4C80),
    topBar = Color(0xFF12152A), onAccent = Color(0xFF1A1E2E), night = true,
)

val LocalPal = staticCompositionLocalOf { DayPal }

enum class ThemeMode { AUTO, DAY, NIGHT }

/** Pixelify Sans (SIL OFL 1.1), a variable font: titles and buttons only. Body text uses the system font. */
@OptIn(ExperimentalTextApi::class)
val PixelFont = FontFamily(
    Font(R.font.pixelify_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(450))),
    Font(R.font.pixelify_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(650))),
)

private val Type = Typography(
    titleLarge = TextStyle(fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = PixelFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

/** Device pixels per art pixel: about 2 dp, always a whole number so pixel art never blurs. */
@Composable
fun pixel(): Float = max(1f, (LocalDensity.current.density * 2f).roundToInt().toFloat())

@Composable
fun MyceliumTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val night = when (mode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val p = if (night) NightPal else DayPal
    val scheme = if (night) {
        darkColorScheme(
            primary = p.leaf, onPrimary = p.onAccent, secondary = p.wood, onSecondary = p.ink, tertiary = p.gold,
            background = p.bg, onBackground = p.ink, surface = p.paper, onSurface = p.ink,
            surfaceVariant = p.paperEdge, onSurfaceVariant = p.inkMuted, surfaceContainer = p.paper,
            surfaceContainerLow = p.paper, surfaceContainerHigh = p.paperEdge, surfaceContainerHighest = p.paperEdge,
            error = p.berry, outline = p.woodLight, outlineVariant = p.paperEdge,
        )
    } else {
        lightColorScheme(
            primary = p.leaf, onPrimary = p.onAccent, secondary = p.wood, onSecondary = p.onAccent, tertiary = p.gold,
            background = p.bg, onBackground = p.ink, surface = p.paper, onSurface = p.ink,
            surfaceVariant = p.paperEdge, onSurfaceVariant = p.inkMuted, surfaceContainer = p.paper,
            surfaceContainerLow = p.paper, surfaceContainerHigh = p.paperEdge, surfaceContainerHighest = p.paperEdge,
            error = p.berry, outline = p.wood, outlineVariant = p.paperEdge,
        )
    }
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = scheme, typography = Type, content = content)
    }
}
