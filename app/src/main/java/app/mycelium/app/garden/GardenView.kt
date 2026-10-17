package app.mycelium.app.garden

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * The full-screen floating-island garden. One animation clock at about 10 fps, running only while the screen is
 * resumed; [still] (Reduce motion or Battery-saver garden) draws one static frame and only follows the clock's sky.
 */
@Composable
fun GardenView(still: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val atlas = remember { SpriteAtlas(context.applicationContext) }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.roundToPx() }
        val viewH = with(density) { maxHeight.roundToPx() }
        if (viewW <= 0 || viewH <= 0) return@BoxWithConstraints
        val base = max(2, viewW / 400)
        val renderer = remember(viewW, viewH) {
            val w = (viewW + base - 1) / base
            SceneRenderer(atlas, World(w, (viewH / base * 1.6f).toInt()), viewW, viewH, base)
        }
        var ms by remember { mutableLongStateOf(0L) }
        var phase by remember { mutableStateOf(skyPhaseNow()) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(still) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var minute = 0L
                while (true) {
                    if (!still) ms = clockMs()
                    if (clockMs() - minute > 60_000) { phase = skyPhaseNow(); minute = clockMs() }
                    delay(if (still) 60_000 else 100)
                }
            }
        }
        if (still) ms = 0L
        val scale = base
        val camY = (renderer.world.surfaces[0].y - viewH / scale * 0.30f).coerceAtLeast(0f)
        Canvas(Modifier.fillMaxSize()) {
            renderer.draw(this, scale, 0f, camY, ms, phase)
            renderer.drawLight(this, scale, 0f, camY, ms, phase)
        }
    }
}
