package app.mycelium.app.garden

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.mycelium.app.ui.PixelFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Short-lived effects: a poof where a Snoop was shooed, and a happy hop for its host. */
class GardenFx {
    class Poof(val pkg: String, val slot: Int, val start: Long)
    val poofs = mutableStateListOf<Poof>()
    val hops = mutableStateMapOf<String, Long>()

    fun shooed(pet: Pet, company: String) {
        val slot = pet.visiting.indexOfFirst { it.company == company }.coerceAtLeast(0)
        poofs += Poof(pet.pkg, slot, clockMs())
        hops[pet.pkg] = clockMs() + 350
    }
}

/** Camera in art pixels: [x],[y] = world point at the view's top-left; [zoom] 1..3 on top of the fit-to-screen scale. */
class Camera {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var fling: Job? = null
    var resetRequest by mutableLongStateOf(0L)

    fun reset() { fling?.cancel(); resetRequest = clockMs() }
}

private const val MAX_ZOOM = 3f

/**
 * The full-screen terrarium. Each frame is drawn in art pixels into one offscreen bitmap and scaled up with
 * nearest-neighbor, so zoom and pan are smooth while pixels stay crisp. One animation clock at about 10 fps, only while
 * the screen is resumed; [still] (Reduce motion or Battery-saver garden) draws static frames.
 */
@Composable
fun GardenView(
    still: Boolean, pets: List<Pet>, selected: String?, fx: GardenFx, camera: Camera, focus: String?,
    topInsetPx: Int, bottomInsetPx: Int, fitBottomPx: Int,
    onTapPet: (Pet) -> Unit, onTapSnoop: (Pet, SnoopVisit) -> Unit, onTapEmpty: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val atlas = remember { SpriteAtlas.of(context) }
    val layout = remember { Layout.load(context.applicationContext) }
    val measurer = rememberTextMeasurer()
    val sounds = app.mycelium.app.ui.LocalSounds.current
    val lastMoodSound = remember { longArrayOf(0L) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.roundToPx() }
        val viewH = with(density) { maxHeight.roundToPx() }
        if (viewW <= 0 || viewH <= 0) return@BoxWithConstraints
        // fit the jar (lid to shelf, full width) between the top bar and the sheet at 1x
        // (sized for the sheet at its smallest, so dragging the sheet never rescales the scene)
        val fit = min(viewW / layout.worldW.toFloat(), (viewH - topInsetPx - fitBottomPx).toFloat() / 528f).coerceAtLeast(1f)
        val renderer = remember(viewW, viewH) {
            TerrariumRenderer(atlas, layout, ceil(viewW / fit).toInt() + 2, ceil(viewH / fit).toInt() + 2)
        }
        val frameImage = remember(renderer) { renderer.frame.asImageBitmap() }
        // where the user dragged creatures to, kept on this phone (pkg -> "x,y")
        val store = remember { context.getSharedPreferences("mycelium_garden", android.content.Context.MODE_PRIVATE) }
        val moved = remember {
            mutableStateMapOf<String, Spot>().apply {
                for ((k, v) in store.all) if (k.startsWith("spot_")) (v as? String)?.split(',')?.let { if (it.size == 2) put(k.removePrefix("spot_"), Spot(it[0].toInt(), it[1].toInt())) }
            }
        }
        var lifted by remember { mutableStateOf<String?>(null) }
        var dragAt by remember { mutableStateOf<Spot?>(null) }
        val baseSpots = remember(layout, pets.map { it.pkg + it.seed }, moved.toMap()) { placePets(layout, pets, moved) }
        val spots = lifted?.let { l -> dragAt?.let { baseSpots + (l to it) } } ?: baseSpots
        fun scale() = fit * camera.zoom
        fun clamp() {
            val vw = viewW / scale()
            val vh = viewH / scale()
            camera.x = if (vw >= layout.worldW) (layout.worldW - vw) / 2 else camera.x.coerceIn(0f, layout.worldW - vw)
            val minY = -topInsetPx / scale() - 8
            val maxY = 720f - vh
            camera.y = if (maxY <= minY) (minY + maxY) / 2 else camera.y.coerceIn(minY, maxY)
        }
        LaunchedEffect(renderer, camera.resetRequest) {
            camera.zoom = 1f
            camera.x = layout.worldW / 2f - viewW / fit / 2
            camera.y = 64f - topInsetPx / fit
            clamp()
        }
        fun zoomTo(z: Float, focusPx: Offset) {
            val wx = camera.x + focusPx.x / scale()
            val wy = camera.y + focusPx.y / scale()
            camera.zoom = z.coerceIn(1f, MAX_ZOOM)
            camera.x = wx - focusPx.x / scale()
            camera.y = wy - focusPx.y / scale()
            clamp()
        }
        LaunchedEffect(focus, spots) {   // the sheet asked to look at a creature: center it above the sheet
            val spot = spots[focus] ?: return@LaunchedEffect
            camera.fling?.cancel()
            camera.x = spot.x - viewW / scale() / 2
            camera.y = spot.y - (viewH - bottomInsetPx) / scale() * 0.6f
            clamp()
        }

        var ms by remember { mutableLongStateOf(0L) }
        var phase by remember { mutableStateOf(skyPhaseNow()) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(still) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var minute = 0L
                while (true) {
                    val busy = fx.poofs.isNotEmpty() || fx.hops.isNotEmpty()
                    ms = if (!still || busy) clockMs() else 0L
                    if (clockMs() - minute > 60_000) { phase = skyPhaseNow(); minute = clockMs() }
                    fx.poofs.removeAll { clockMs() - it.start > 1000 }
                    fx.hops.keys.filter { clockMs() - (fx.hops[it] ?: 0L) > 700 }.forEach { fx.hops.remove(it) }
                    delay(if (still && !busy) 1000 else 100)
                }
            }
        }

        val placed = remember { arrayOf(emptyList<Placed>()) }   // last frame's creatures, for hit tests (not state)
        /** Hit test in art pixels: Snoops first (small and on top), then creatures. */
        fun hit(p: Offset): Pair<Pet, SnoopVisit?>? {
            val wx = camera.x + p.x / scale()
            val wy = camera.y + p.y / scale()
            val slop = 10 / camera.zoom
            for (pl in placed[0]) for ((v, at) in pl.snoops) {
                if (abs(wx - at.x) < 9 + slop && abs(wy - (at.y + 2)) < 8 + slop) return pl.pet to v
            }
            for (pl in placed[0].reversed()) {
                val s = pl.sprite
                if (wx in (pl.spot.x - s.w / 2f - slop)..(pl.spot.x + s.w / 2f + slop) && wy in (pl.spot.y - s.h - slop)..(pl.spot.y + slop)) {
                    return pl.pet to null
                }
            }
            return null
        }

        val gestures = Modifier
            .pointerInput(renderer, pets, spots) {
                detectTapGestures(
                    onTap = { p ->
                        val h = hit(p)
                        when {
                            h == null -> onTapEmpty()
                            h.second != null -> {
                                sounds?.play("snoop_${listOf("mite", "moth", "leech")[(crc(h.second!!.company) % 3).toInt()]}")
                                fx.hops[h.first.pkg] = clockMs(); onTapSnoop(h.first, h.second!!)
                            }
                            else -> {
                                if (h.first.sleeping(System.currentTimeMillis())) sounds?.play("hop")   // woken up with a boing
                                sounds?.play(h.first.voice, h.first.voicePitch)
                                fx.hops[h.first.pkg] = clockMs(); onTapPet(h.first)
                            }
                        }
                    },
                    onDoubleTap = { p ->   // a smooth zoom in, or back out
                        scope.launch {
                            Animatable(camera.zoom).animateTo(if (camera.zoom > 1.5f) 1f else 2f, tween(260)) { zoomTo(value, p) }
                        }
                    },
                )
            }
            .pointerInput(renderer) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    camera.fling?.cancel()
                    // hold a creature for a moment to pick it up; drag it; let go and it lands on the nearest surface
                    val grab = hit(down.position)?.takeIf { it.second == null }?.first
                    if (grab != null) {
                        val stillHeld = withTimeoutOrNull(200) {
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                            }
                            @Suppress("UNREACHABLE_CODE") true
                        } == null
                        if (stillHeld) {
                            sounds?.play("pickup")
                            lifted = grab.pkg
                            fx.hops[grab.pkg] = clockMs()
                            var last = down.position
                            do {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                last = c.position
                                dragAt = Spot((camera.x + last.x / scale()).roundToInt(), (camera.y + last.y / scale()).roundToInt() + 6)
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                            val land = layout.nearestSurface(camera.x + last.x / scale(), camera.y + last.y / scale() + 6)
                            moved[grab.pkg] = land
                            store.edit().putString("spot_${grab.pkg}", "${land.x},${land.y}").apply()
                            fx.hops[grab.pkg] = clockMs()
                            sounds?.play("drop")
                            lifted = null
                            dragAt = null
                            return@awaitEachGesture
                        }
                    }
                    val tracker = VelocityTracker()
                    do {
                        val ev = awaitPointerEvent()
                        val pan = ev.calculatePan()
                        if (ev.changes.count { it.pressed } >= 2) zoomTo(camera.zoom * ev.calculateZoom(), ev.calculateCentroid())
                        if (pan != Offset.Zero && ev.changes.any { it.positionChanged() }) {
                            camera.x -= pan.x / scale()
                            camera.y -= pan.y / scale()
                            clamp()
                            ev.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                            if (pan.getDistance() > 2f) ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    val v = tracker.calculateVelocity()
                    if (abs(v.x) + abs(v.y) > 300f) {
                        camera.fling = scope.launch {   // inertia: px/s, decays smoothly
                            var vx = v.x
                            var vy = v.y
                            while (isActive && abs(vx) + abs(vy) > 40f) {
                                camera.x -= vx * 0.016f / scale()
                                camera.y -= vy * 0.016f / scale()
                                clamp()
                                vx *= 0.9f
                                vy *= 0.9f
                                delay(16)
                            }
                        }
                    }
                }
            }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val s = scale()
            val cx = floor(camera.x).toInt()
            val cy = floor(camera.y).toInt()
            placed[0] = renderer.render(cx, cy, ms, phase, pets, spots, selected, fx, lifted)
            // a mood sound when a bubble pops up: only creatures on screen, at most one every 4 s
            renderer.moodStarts.firstOrNull { (p, m) ->
                m != Mood.CONTENT && spots[p.pkg]?.let { sp -> (sp.x - camera.x) * s in 0f..size.width && (sp.y - camera.y) * s in 0f..size.height } == true
            }?.let { (_, m) ->
                if (clockMs() - lastMoodSound[0] > 4000) {
                    lastMoodSound[0] = clockMs()
                    sounds?.play(when (m) { Mood.HAPPY -> "mood_happy"; Mood.GRUMPY -> "mood_grumpy"; Mood.SICK -> "mood_sick"; else -> "mood_sleepy" }, volume = 0.7f)
                }
            }
            val rw = min(renderer.frame.width, ceil(viewW / s).toInt() + 2)
            val rh = min(renderer.frame.height, ceil(viewH / s).toInt() + 2)
            drawImage(frameImage, srcOffset = IntOffset.Zero, srcSize = IntSize(rw, rh),
                dstOffset = IntOffset((-(camera.x - cx) * s).roundToInt(), (-(camera.y - cy) * s).roundToInt()),
                dstSize = IntSize((rw * s).roundToInt(), (rh * s).roundToInt()), filterQuality = FilterQuality.None)
            for (pl in placed[0]) {   // name tags at screen resolution, under the feet
                val tag = measurer.measure(pl.pet.label, TextStyle(fontFamily = PixelFont, fontSize = 11.sp, color = Color(0xFF2B2233)))
                val tx = (pl.spot.x - camera.x) * s - tag.size.width / 2f
                val ty = (pl.spot.y + 2 - camera.y) * s
                drawRect(Color(0xE6F1E4C3), Offset(tx - 6, ty), Size(tag.size.width + 12f, tag.size.height.toFloat()))
                drawText(tag, topLeft = Offset(tx, ty))
            }
        }
    }
}
