package app.mycelium.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import app.mycelium.app.R
import kotlin.math.max
import kotlin.math.roundToInt

/** Design tokens from docs/ui-plan.md (floating-island palette). Day and night; components read colors from [LocalPal]. */
@Immutable
data class Pal(
    val bg: Color, val paper: Color, val paperEdge: Color,
    val wood: Color, val woodDark: Color, val woodLight: Color,
    val ink: Color, val inkMuted: Color,
    val leaf: Color, val moss: Color, val gold: Color, val berry: Color, val sky: Color,
    val topBar: Color, val onAccent: Color, val onLeaf: Color, val night: Boolean,
)

val DayPal = Pal(
    bg = Color(0xFFDFF6F4), paper = Color(0xFFF1E4C3), paperEdge = Color(0xFFC99A5B),
    wood = Color(0xFF8A5A36), woodDark = Color(0xFF5A3B28), woodLight = Color(0xFFB98450),
    ink = Color(0xFF2B2233), inkMuted = Color(0xFF5F5385),
    leaf = Color(0xFF6DCB45), moss = Color(0xFF2F7A2E), gold = Color(0xFFA8723F), berry = Color(0xFFE7679A), sky = Color(0xFF5FA8F0),
    topBar = Color(0xFF5A3B28), onAccent = Color(0xFFF1E4C3), onLeaf = Color(0xFF2B2233), night = false,
)

val NightPal = Pal(
    bg = Color(0xFF1B1A33), paper = Color(0xFF3A2A22), paperEdge = Color(0xFF5B3A2A),
    wood = Color(0xFF5A3B28), woodDark = Color(0xFF2B2233), woodLight = Color(0xFF8A5A36),
    ink = Color(0xFFF1E4C3), inkMuted = Color(0xFFC99A5B),
    leaf = Color(0xFF6DCB45), moss = Color(0xFF2F7A2E), gold = Color(0xFFC99A5B), berry = Color(0xFFF59BB8), sky = Color(0xFF5F5385),
    topBar = Color(0xFF2B2842), onAccent = Color(0xFFF1E4C3), onLeaf = Color(0xFF1B1A33), night = true,
)

val LocalPal = staticCompositionLocalOf { DayPal }

/** True when the user asked for less motion (Settings > Motion): no typewriter, no bobbing. */
val LocalReduceMotion = staticCompositionLocalOf { false }

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
fun MyceliumTheme(mode: ThemeMode, textSize: TextSize = TextSize.NORMAL, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val night = when (mode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val p = if (night) NightPal else DayPal
    val scheme = if (night) {
        darkColorScheme(
            primary = p.leaf, onPrimary = p.onLeaf, secondary = p.wood, onSecondary = p.ink, tertiary = p.gold,
            background = p.bg, onBackground = p.ink, surface = p.paper, onSurface = p.ink,
            surfaceVariant = p.paperEdge, onSurfaceVariant = p.inkMuted, surfaceContainer = p.paper,
            surfaceContainerLow = p.paper, surfaceContainerHigh = p.paperEdge, surfaceContainerHighest = p.paperEdge,
            error = p.berry, outline = p.woodLight, outlineVariant = p.paperEdge,
        )
    } else {
        lightColorScheme(
            primary = p.leaf, onPrimary = p.onLeaf, secondary = p.wood, onSecondary = p.onAccent, tertiary = p.gold,
            background = p.bg, onBackground = p.ink, surface = p.paper, onSurface = p.ink,
            surfaceVariant = p.paperEdge, onSurfaceVariant = p.inkMuted, surfaceContainer = p.paper,
            surfaceContainerLow = p.paper, surfaceContainerHigh = p.paperEdge, surfaceContainerHighest = p.paperEdge,
            error = p.berry, outline = p.wood, outlineVariant = p.paperEdge,
        )
    }
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalPal provides p,
        LocalReduceMotion provides reduceMotion,
        LocalDensity provides Density(d.density, d.fontScale * textSize.scale),   // in-app text size on top of the phone's
    ) {
        MaterialTheme(colorScheme = scheme, typography = Type, content = content)
    }
}
