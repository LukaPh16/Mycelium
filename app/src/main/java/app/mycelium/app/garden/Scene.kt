package app.mycelium.app.garden

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.util.Calendar
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SkyPhase { DAY, DUSK, NIGHT }

private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

fun skyPhaseNow(): SkyPhase {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 7..16 -> SkyPhase.DAY
        5, 6, 17, 18, 19 -> SkyPhase.DUSK
        else -> SkyPhase.NIGHT
    }
}

/** Sky bands (top to horizon), all from the island palette. */
private val SKY = mapOf(
    SkyPhase.DAY to listOf(0xFF5FA8F0, 0xFF8CC4F7, 0xFFA9D8FF, 0xFFC9E2F7),
    SkyPhase.DUSK to listOf(0xFF433B63, 0xFF8A74A6, 0xFFF59BB8, 0xFFFFD3E1),
    SkyPhase.NIGHT to listOf(0xFF1B1A33, 0xFF2B2842, 0xFF433B63, 0xFF5F5385),
)

/**
 * Everything the garden draws, in art pixels. [base] is device px per art px at 1x zoom (a whole number).
 * Terrain is baked once into [terrain]; the sky is cached per phase.
 */
class SceneRenderer(val atlas: SpriteAtlas, val world: World, val viewW: Int, val viewH: Int, val base: Int) {
    val terrain: ImageBitmap = bake()
    private var skyCache: Pair<SkyPhase, ImageBitmap>? = null
    private val behindDecor = world.decor.filter { it.animated && !it.sprite.startsWith("mushroom_glow") }
    private val glowDecor = world.decor.filter { it.animated && it.sprite.startsWith("mushroom_glow") }

    private fun bake(): ImageBitmap {
        val bmp = Bitmap.createBitmap(world.width, world.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        fun blit(name: String, x: Int, y: Int, anchored: Boolean) {
            val s = atlas[name]
            val src = s.image.asAndroidBitmap()
            val l = if (anchored) x - s.ax else x
            val t = if (anchored) y - s.ay else y
            c.drawBitmap(src, Rect(0, 0, s.w, s.h), Rect(l, t, l + s.w, t + s.h), null)
        }
        val shade = android.graphics.Paint()
        for (t in world.tiles.filter { it.sprite != "water" }) {
            // deeper rock rows lean darker and bluer (light from above)
            shade.colorFilter = if (t.depth == 0) null else
                android.graphics.PorterDuffColorFilter(android.graphics.Color.argb(minOf(150, t.depth * 38), 27, 26, 51), android.graphics.PorterDuff.Mode.SRC_ATOP)
            val sp = atlas[t.sprite]
            c.drawBitmap(sp.image.asAndroidBitmap(), Rect(0, 0, sp.w, sp.h), Rect(t.x, t.y, t.x + sp.w, t.y + sp.h), shade)
        }
        for (d in world.decor.filter { !it.animated }) blit(d.sprite, d.x, d.y, anchored = true)
        return bmp.asImageBitmap()
    }

    private fun sky(phase: SkyPhase): ImageBitmap {
        skyCache?.let { if (it.first == phase) return it.second }
        val w = (viewW + base - 1) / base
        val h = (viewH + base - 1) / base
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bands = SKY.getValue(phase)
        for (y in 0 until h) {
            val f = y.toFloat() / h * (bands.size - 1)
            val b = floor(f).toInt().coerceAtMost(bands.size - 2)
            val mixRow = f - b
            for (x in 0 until w) {
                // ordered 4x4 Bayer dither between neighbouring bands: chunky pixels, gradual blend
                val threshold = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f
                bmp.setPixel(x, y, bands[if (mixRow > threshold) b + 1 else b].toInt())
            }
        }
        return bmp.asImageBitmap().also { skyCache = phase to it }
    }

    /**
     * One frame. [scale] device px per art px (whole number), [camX]/[camY] the world art pixel at the view's top-left,
     * [ms] the animation clock (0 when still).
     */
    fun draw(ds: DrawScope, scale: Int, camX: Float, camY: Float, ms: Long, phase: SkyPhase) = with(ds) {
        val s = sky(phase)
        drawImage(s, srcOffset = IntOffset.Zero, srcSize = IntSize(s.width, s.height), dstSize = IntSize(s.width * base, s.height * base),
            filterQuality = FilterQuality.None)
        if (phase != SkyPhase.DAY) {
            val star = atlas["star"]
            for (i in 0 until 46) {
                val sx = ((i * 7919) % 997) / 997f * (viewW / base)
                val sy = ((i * 104729) % 991) / 991f * (viewH / base) * 0.7f
                val f = if (ms == 0L) 0 else ((ms / 700 + i) % 5 == 0L).let { if (it) 1 else 0 }
                if (phase == SkyPhase.NIGHT || i % 3 == 0) drawSprite(star, f, sx.toInt(), sy.toInt(), base)
            }
            if (phase == SkyPhase.NIGHT) drawSprite(atlas["moon"], 0, viewW / base - 40, 46, base)
        }
        // far pillars and clouds move slower than the islands (parallax)
        for (p in world.pillars) {
            drawSprite(atlas[p.sprite], 0, p.x, p.y, base, Offset(-camX * 0.35f * base, -camY * 0.35f * base),
                alpha = if (phase == SkyPhase.NIGHT) 0.35f else p.alpha)
        }
        for (c in world.clouds) {
            val drift = if (ms == 0L) 0f else ms / 1000f * (2f + c.phase % 3)
            val span = world.width + 120
            val x = (((c.x + drift + c.phase) % span) + span) % span - 60
            drawSprite(atlas[c.sprite], 0, x.toInt(), c.y, base, Offset(-camX * 0.6f * base, -camY * 0.6f * base),
                alpha = if (phase == SkyPhase.NIGHT) 0.35f else 0.95f)
        }
        val origin = Offset(-(camX * scale).roundToInt().toFloat(), -(camY * scale).roundToInt().toFloat())
        drawImage(terrain, srcOffset = IntOffset.Zero, srcSize = IntSize(terrain.width, terrain.height),
            dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()), dstSize = IntSize(terrain.width * scale, terrain.height * scale),
            filterQuality = FilterQuality.None)
        for (t in world.tiles.filter { it.sprite == "water" }) {
            val w = atlas["water"]
            drawSprite(w, w.frameAt(ms), t.x + w.ax, t.y + w.ay, scale, origin)
        }
        for (d in behindDecor) drawDecor(d, ms, scale, origin)
    }

    /** Dusk/night tint over the land, then the things that glow on top of it. */
    fun drawLight(ds: DrawScope, scale: Int, camX: Float, camY: Float, ms: Long, phase: SkyPhase) = with(ds) {
        val origin = Offset(-(camX * scale).roundToInt().toFloat(), -(camY * scale).roundToInt().toFloat())
        when (phase) {
            SkyPhase.NIGHT -> drawRect(Color(0xFF1B1A33).copy(alpha = 0.42f), Offset.Zero, Size(size.width, size.height))
            SkyPhase.DUSK -> drawRect(Color(0xFF433B63).copy(alpha = 0.18f), Offset.Zero, Size(size.width, size.height))
            else -> Unit
        }
        for (d in glowDecor) drawDecor(d, if (phase == SkyPhase.DAY) 0 else ms, scale, origin)
        val t = ms / 1000f
        if (phase == SkyPhase.DAY) {
            val pollen = atlas["pollen"]
            for (i in 0 until 14) {
                val x = (i * 61 % world.width) + sin(t * 0.4f + i) * 10
                val y = (world.height * (0.15f + (i % 7) * 0.11f)) + sin(t * 0.7f + i * 1.3f) * 6
                drawSprite(pollen, 0, x.toInt(), y.toInt(), scale, origin)
            }
        } else {
            val fly = atlas["firefly"]
            for (s in world.surfaces) for (k in 0 until 3) {
                val i = s.island * 3 + k
                val x = s.x0 + (s.x1 - s.x0) * ((k + 0.5f) / 3f) + sin(t * 0.6f + i) * 12
                val y = s.y - 14 - k * 6 + sin(t * 0.9f + i * 2f) * 6
                drawSprite(fly, if (ms == 0L) 0 else ((ms / 300 + i) % 2).toInt(), x.toInt(), y.toInt(), scale, origin)
            }
        }
    }

    private fun DrawScope.drawDecor(d: Decor, ms: Long, scale: Int, origin: Offset) {
        val sp = atlas[d.sprite]
        val frame = if (ms == 0L) 0 else sp.frameAt(ms + d.phase * 137L)
        if (d.sprite == "waterfall") {
            for (k in 0 until 4) drawSprite(sp, (frame + k) % sp.frames, d.x, d.y + k * sp.h, scale, origin, alpha = 1f - k * 0.22f)
        } else drawSprite(sp, frame, d.x, d.y, scale, origin)
    }
}

/** Monotonic ms for the animation clock. */
fun clockMs(): Long = SystemClock.uptimeMillis()
