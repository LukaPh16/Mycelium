package app.mycelium.app.garden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.json.JSONObject

/** One sprite: a horizontal strip of [frames] frames of [w] x [h] art pixels; [ax],[ay] is the anchor inside a frame. */
class Sprite(val name: String, val image: ImageBitmap, val w: Int, val h: Int, val ax: Int, val ay: Int, val frames: Int, val fps: Int) {
    fun frameAt(ms: Long): Int = if (fps <= 0 || frames <= 1) 0 else ((ms * fps / 1000) % frames).toInt()
}

/**
 * Loads sprites only through assets/sprites/atlas.json (written by scripts/gen_sprites.py). Any PNG can be replaced by
 * hand-drawn art with the same name and frame size; a missing or broken file gets a drawn fallback, never a crash.
 */
class SpriteAtlas(private val context: Context) {
    private class Entry(val file: String, val w: Int, val h: Int, val ax: Int, val ay: Int, val frames: Int, val fps: Int)

    private val entries: Map<String, Entry> = runCatching {
        val root = JSONObject(context.assets.open("sprites/atlas.json").bufferedReader().use { it.readText() })
        val list = root.getJSONArray("sprites")
        (0 until list.length()).associate { i ->
            val o = list.getJSONObject(i)
            val a = o.optJSONArray("anchor")
            o.getString("name") to Entry(o.getString("file"), o.getInt("w"), o.getInt("h"),
                a?.optInt(0) ?: (o.getInt("w") / 2), a?.optInt(1) ?: (o.getInt("h") - 1), o.optInt("frames", 1), o.optInt("fps", 0))
        }
    }.getOrDefault(emptyMap())

    private val cache = HashMap<String, Sprite>()

    fun has(name: String) = name in entries

    @Synchronized operator fun get(name: String): Sprite = cache.getOrPut(name) {
        val e = entries[name] ?: Entry("", 16, 16, 8, 15, 1, 0)
        val bmp = runCatching {
            context.assets.open("sprites/${e.file}").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false }) }
        }.getOrNull()?.takeIf { it.width >= e.w && it.height >= e.h }
        val frames = if (bmp != null) minOf(e.frames, bmp.width / e.w).coerceAtLeast(1) else e.frames
        Sprite(name, (bmp ?: fallback(e.w, e.h, e.frames)).asImageBitmap(), e.w, e.h, e.ax, e.ay, frames, e.fps)
    }

    companion object {
        @Volatile private var shared: SpriteAtlas? = null

        /** One atlas for the whole app, so the scene and the sheet share bitmaps. */
        fun of(context: Context): SpriteAtlas = shared ?: synchronized(this) { shared ?: SpriteAtlas(context.applicationContext).also { shared = it } }
    }

    /** A soft purple blob with two eyes, so a missing sprite still reads as "something lives here". */
    private fun fallback(w: Int, h: Int, frames: Int): Bitmap {
        val b = Bitmap.createBitmap(w * frames, h, Bitmap.Config.ARGB_8888)
        val body = 0xFF8A74A6.toInt()
        val edge = 0xFF433B63.toInt()
        val eye = 0xFF2B2233.toInt()
        for (f in 0 until frames) {
            val cx = f * w + w / 2f
            val cy = h * 0.62f
            val rx = w * 0.36f
            val ry = h * 0.32f
            for (y in 0 until h) for (x in f * w until (f + 1) * w) {
                val d = ((x + 0.5f - cx) / rx).let { it * it } + ((y + 0.5f - cy) / ry).let { it * it }
                if (d <= 1f) b.setPixel(x, y, if (d > 0.8f) edge else body)
            }
            val ey = (cy - ry * 0.2f).toInt()
            b.setPixel((cx - 3).toInt(), ey, eye)
            b.setPixel((cx + 2).toInt(), ey, eye)
        }
        return b
    }
}

/** Draws one frame with its anchor at art pixel ([x], [y]); [scale] device px per art px (whole number), no smoothing. */
fun DrawScope.drawSprite(s: Sprite, frame: Int, x: Int, y: Int, scale: Int, origin: Offset = Offset.Zero, alpha: Float = 1f) {
    val left = (x - s.ax) * scale + origin.x.toInt()
    val top = (y - s.ay) * scale + origin.y.toInt()
    drawImage(
        s.image, srcOffset = IntOffset((frame % s.frames) * s.w, 0), srcSize = IntSize(s.w, s.h),
        dstOffset = IntOffset(left, top), dstSize = IntSize(s.w * scale, s.h * scale), alpha = alpha, filterQuality = FilterQuality.None,
    )
}
