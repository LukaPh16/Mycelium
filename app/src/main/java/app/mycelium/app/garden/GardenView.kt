package app.mycelium.app.garden

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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.mycelium.app.ui.PixelFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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

/** Camera in art pixels: [x],[y] = world point at the view's top-left; [scale] = device px per art px (whole number). */
class Camera {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var scale by mutableIntStateOf(2)
    var home: Triple<Float, Float, Int>? = null
    var fling: Job? = null

    fun reset() { fling?.cancel(); home?.let { (hx, hy, s) -> x = hx; y = hy; scale = s } }
}

/**
 * The full-screen floating-island garden with its creatures. One animation clock at about 10 fps, running only while
 * the screen is resumed; [still] (Reduce motion or Battery-saver garden) draws static frames. Pinch or double-tap to
 * zoom 1x-2x in whole-number steps, drag to pan (with inertia), tap a creature or Snoop.
 */
@Composable
fun GardenView(
    still: Boolean, pets: List<Pet>, selected: String?, fx: GardenFx, camera: Camera, focus: String?, bottomInsetPx: Int,
    onTapPet: (Pet) -> Unit, onTapSnoop: (Pet, SnoopVisit) -> Unit, onTapEmpty: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val atlas = remember { SpriteAtlas.of(context) }
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.roundToPx() }
        val viewH = with(density) { maxHeight.roundToPx() }
        if (viewW <= 0 || viewH <= 0) return@BoxWithConstraints
        val base = max(2, viewW / 320)   // creatures stay about 40 dp tall at 1x
        val renderer = remember(viewW, viewH) {
            val w = ((viewW + base - 1) / base * 1.6f).toInt()   // wider than the screen: room for 12 creatures, pan sideways
            SceneRenderer(atlas, World(w, (viewH / base * 1.6f).toInt()), viewW, viewH, base)
        }
        val world = renderer.world
        val spots = remember(world, pets.map { it.pkg + it.seed }) {
            placePets(world, pets, viewW / base / 2, world.surfaces[0].y + viewH / base / 5)
        }
        LaunchedEffect(renderer) {
            val top = world.surfaces[0]
            val hx = ((top.x0 + top.x1) / 2f - viewW / base / 2f).coerceIn(0f, (world.width - viewW / base).toFloat())
            camera.home = Triple(hx, (top.y - viewH / base * 0.30f).coerceAtLeast(0f), base)
            camera.reset()
        }
        fun clamp() {
            val maxX = world.width - viewW / camera.scale.toFloat()
            val maxY = world.height - viewH / camera.scale.toFloat()
            camera.x = if (maxX <= 0) maxX / 2 else camera.x.coerceIn(0f, maxX)
            camera.y = if (maxY <= 0) maxY / 2 else camera.y.coerceIn(0f, maxY)
        }
        fun zoomTo(newScale: Int, focus: Offset) {
            val s = newScale.coerceIn(base, base * 2)
            if (s == camera.scale) return
            val wx = camera.x + focus.x / camera.scale
            val wy = camera.y + focus.y / camera.scale
            camera.scale = s
            camera.x = wx - focus.x / s
            camera.y = wy - focus.y / s
            clamp()
        }

        LaunchedEffect(focus, spots) {   // the sheet asked to look at a creature: center it above the sheet
            val spot = spots[focus] ?: return@LaunchedEffect
            camera.fling?.cancel()
            camera.x = spot.x - viewW / camera.scale / 2f
            camera.y = spot.y - (viewH - bottomInsetPx) / camera.scale * 0.6f
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
                    fx.poofs.removeAll { clockMs() - it.start > 600 }
                    fx.hops.keys.filter { clockMs() - (fx.hops[it] ?: 0L) > 700 }.forEach { fx.hops.remove(it) }
                    delay(if (still && !busy) 1000 else 100)
                }
            }
        }

        /** Hit test in art pixels: Snoops first (small and on top), then creatures. */
        fun hit(p: Offset): Pair<Pet, SnoopVisit?>? {
            val wx = camera.x + p.x / camera.scale
            val wy = camera.y + p.y / camera.scale
            val slop = 6
            for (pet in pets) {
                val spot = spots[pet.pkg] ?: continue
                val s = atlas[creatureSprite(pet, false)]
                pet.visiting.take(3).forEachIndexed { n, v ->
                    val (dx, dy) = snoopOffset(n, s.h)
                    if (abs(wx - (spot.x + dx)) < 8 + slop && abs(wy - (spot.y + dy - 6)) < 8 + slop) return pet to v
                }
            }
            for (pet in pets) {
                val spot = spots[pet.pkg] ?: continue
                val s = atlas[creatureSprite(pet, false)]
                if (wx in (spot.x - s.w / 2f - slop)..(spot.x + s.w / 2f + slop) && wy in (spot.y - s.h - slop).toFloat()..(spot.y + slop).toFloat()) {
                    return pet to null
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
                            h.second != null -> { fx.hops[h.first.pkg] = clockMs(); onTapSnoop(h.first, h.second!!) }
                            else -> { fx.hops[h.first.pkg] = clockMs(); onTapPet(h.first) }
                        }
                    },
                    onDoubleTap = { p -> zoomTo(if (camera.scale >= base * 2) base else camera.scale + base, p) },
                )
            }
            .pointerInput(renderer) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    camera.fling?.cancel()
                    val tracker = VelocityTracker()
                    var zoomAcc = 1f
                    do {
                        val ev = awaitPointerEvent()
                        val pan = ev.calculatePan()
                        if (ev.changes.count { it.pressed } >= 2) {
                            zoomAcc *= ev.calculateZoom()
                            val target = (camera.scale * zoomAcc).roundToInt()   // whole-number steps only
                            if (target != camera.scale) { zoomTo(target, ev.calculateCentroid()); zoomAcc = 1f }
                        }
                        if (pan != Offset.Zero && ev.changes.any { it.positionChanged() }) {
                            camera.x -= pan.x / camera.scale
                            camera.y -= pan.y / camera.scale
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
                                camera.x -= vx * 0.016f / camera.scale
                                camera.y -= vy * 0.016f / camera.scale
                                clamp()
                                vx *= 0.9f
                                vy *= 0.9f
                                delay(16)
                            }
                        }
                    }
                }
            }

        val night = phase == SkyPhase.NIGHT
        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val sc = camera.scale
            renderer.draw(this, sc, camera.x, camera.y, ms, phase)
            val origin = Offset(-(camera.x * sc).roundToInt().toFloat(), -(camera.y * sc).roundToInt().toFloat())
            val now = System.currentTimeMillis()
            renderer.drawLight(this, sc, camera.x, camera.y, ms, phase)
            for (pet in pets) {   // creatures stay bright and readable at night: drawn after the tint
                val spot = spots[pet.pkg] ?: continue
                drawPet(atlas, pet, spot, sc, origin, ms, now, pet.pkg == selected, fx)
            }
            for (pet in pets) {   // name tags stay readable at night: drawn after the tint
                val spot = spots[pet.pkg] ?: continue
                val tag = measurer.measure(pet.label, TextStyle(fontFamily = PixelFont, fontSize = 11.sp, color = Color(0xFF2B2233)))
                val tx = origin.x + spot.x * sc - tag.size.width / 2f
                val ty = origin.y + (spot.y + 4) * sc
                drawRect(Color(0xFFF1E4C3).copy(alpha = if (night) 0.85f else 0.92f), Offset(tx - 6, ty), Size(tag.size.width + 12f, tag.size.height.toFloat()))
                drawText(tag, topLeft = Offset(tx, ty))
            }
        }
    }
}

fun creatureSprite(pet: Pet, sleeping: Boolean): String =
    "creature_${pet.preset}_s${pet.stage.coerceIn(1, 3)}" + if (sleeping) "_sleep" else ""

private fun DrawScope.drawPet(atlas: SpriteAtlas, pet: Pet, spot: Spot, sc: Int, origin: Offset, ms: Long, now: Long, selected: Boolean, fx: GardenFx) {
    val hopStart = fx.hops[pet.pkg]
    val asleep = pet.sleeping(now) && hopStart == null
    val s = atlas[creatureSprite(pet, asleep)]
    val hop = if (hopStart != null && ms != 0L) {
        intArrayOf(0, -3, -5, -3, -1, 0, -2, 0).getOrElse(((clockMs() - hopStart).coerceAtLeast(0) / 80).toInt()) { 0 }
    } else 0
    if (selected) ring(spot, sc, origin, s.w)
    drawSprite(s, if (ms == 0L) 0 else s.frameAt(ms + crc(pet.pkg) % 1000), spot.x, spot.y + hop, sc, origin)
    if (asleep) {
        val z = atlas["zzz"]
        drawSprite(z, if (ms == 0L) 2 else z.frameAt(ms), spot.x + s.w / 2 - 4, spot.y - s.h + 6, sc, origin)
    }
    pet.visiting.take(3).forEachIndexed { n, v ->
        val (dx, dy) = snoopOffset(n, s.h)
        val sp = atlas["snoop_${1 + (crc(v.company) % 2).toInt()}"]
        drawSprite(sp, if (ms == 0L) 0 else sp.frameAt(ms + n * 250L), spot.x + dx, spot.y + dy, sc, origin)
    }
    for (p in fx.poofs) if (p.pkg == pet.pkg) {
        val (dx, dy) = snoopOffset(p.slot, s.h)
        val poof = atlas["poof"]
        val f = ((clockMs() - p.start) / 110).toInt()
        if (f < poof.frames) drawSprite(poof, f, spot.x + dx, spot.y + dy - 6, sc, origin)
        val sparkle = atlas["sparkle"]
        if (f in 1..4) for (k in 0..2) drawSprite(sparkle, (f + k) % sparkle.frames, spot.x + dx - 8 + k * 8, spot.y + dy - 14 - (k % 2) * 6, sc, origin)
    }
}

/** A pixel highlight ring around a creature's feet. */
private fun DrawScope.ring(spot: Spot, sc: Int, origin: Offset, w: Int) {
    val rx = w / 2 + 2
    for (deg in 0 until 360 step 6) {
        val a = Math.toRadians(deg.toDouble())
        val x = spot.x + (cos(a) * rx).roundToInt()
        val y = spot.y + (sin(a) * 4).roundToInt()
        drawRect(if (deg % 24 < 12) Color(0xFFF1E4C3) else Color(0xFFA8E85A), Offset(origin.x + x * sc, origin.y + y * sc), Size(sc.toFloat(), sc.toFloat()))
    }
}
