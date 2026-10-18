package app.mycelium.app.garden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import kotlin.random.Random

/** One sprite: a horizontal strip of [frames] frames of [w] x [h] art pixels; [ax],[ay] anchor, [hx],[hy] head point. */
class Sprite(
    val name: String, val bitmap: Bitmap, val w: Int, val h: Int, val ax: Int, val ay: Int, val frames: Int, val fps: Int,
    val hx: Int, val hy: Int,
) {
    val image: ImageBitmap by lazy { bitmap.asImageBitmap() }
    fun frameAt(ms: Long): Int = if (fps <= 0 || frames <= 1) 0 else ((ms * fps / 1000) % frames).toInt()
}

/**
 * Loads sprites only through assets/sprites/atlas.json (written by scripts/gen_sprites.py). Any PNG can be replaced by
 * hand-drawn art with the same name and frame size; a missing or broken file gets a drawn fallback, never a crash.
 * Creature sprites paint their main color in the atlas KEY ramp; [creature] swaps it for an app's look and paints
 * tracker "infection" on top.
 */
class SpriteAtlas(private val context: Context) {
    private class Entry(val file: String, val w: Int, val h: Int, val ax: Int, val ay: Int, val frames: Int, val fps: Int, val hx: Int, val hy: Int)

    private val root: JSONObject = runCatching {
        JSONObject(context.assets.open("sprites/atlas.json").bufferedReader().use { it.readText() })
    }.getOrDefault(JSONObject())

    private val entries: Map<String, Entry> = root.optJSONArray("sprites")?.let { list ->
        (0 until list.length()).associate { i ->
            val o = list.getJSONObject(i)
            val a = o.optJSONArray("anchor")
            val head = o.optJSONArray("head")
            val w = o.getInt("w")
            val h = o.getInt("h")
            o.getString("name") to Entry(o.getString("file"), w, h, a?.optInt(0) ?: (w / 2), a?.optInt(1) ?: (h - 1),
                o.optInt("frames", 1), o.optInt("fps", 0), head?.optInt(0) ?: (w / 2), head?.optInt(1) ?: 0)
        }
    }.orEmpty()

    private fun colors(key: String): IntArray =
        root.optJSONArray(key)?.let { a -> IntArray(a.length()) { android.graphics.Color.parseColor(a.getString(it)) } } ?: IntArray(0)

    private val keyRamp = colors("key_ramp")
    private val infect = colors("infect").takeIf { it.size == 5 } ?: intArrayOf(0xFF3A0F22.toInt(), 0xFF7A1530.toInt(), 0xFFB8203A.toInt(), 0xFFE8455A.toInt(), 0xFFFF8C8C.toInt())
    val looks: List<String> = root.optJSONObject("recolor")?.keys()?.asSequence()?.toList().orEmpty()
    private val lookRamps: Map<String, IntArray> = looks.associateWith { k ->
        root.getJSONObject("recolor").getJSONArray(k).let { a -> IntArray(a.length()) { android.graphics.Color.parseColor(a.getString(it)) } }
    }
    val hats: List<String> = root.optJSONArray("hats")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    private val cache = HashMap<String, Sprite>()

    fun has(name: String) = name in entries

    @Synchronized operator fun get(name: String): Sprite = cache.getOrPut(name) {
        val e = entries[name] ?: Entry("", 16, 16, 8, 15, 1, 0, 8, 0)
        val bmp = runCatching {
            context.assets.open("sprites/${e.file}").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false; inMutable = false }) }
        }.getOrNull()?.takeIf { it.width >= e.w && it.height >= e.h }
        val frames = if (bmp != null) minOf(e.frames, bmp.width / e.w).coerceAtLeast(1) else e.frames
        Sprite(name, bmp ?: fallback(e.w, e.h, e.frames), e.w, e.h, e.ax, e.ay, frames, e.fps, e.hx, e.hy)
    }

    /**
     * A creature strip in an app's [look] (KEY ramp swapped), with tracker infection [level] 0..3 painted on:
     * red blotches, then veins, then a sickly tint with pus dots. Spots are placed from [seed], so they stay put.
     */
    @Synchronized fun creature(name: String, look: String, level: Int, seed: Long): Sprite = cache.getOrPut("$name|$look|$level|$seed") {
        val base = get(name)
        val src = base.bitmap
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val ramp = lookRamps[look]
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        if (ramp != null && keyRamp.size == ramp.size) {
            for (i in px.indices) {
                val c = px[i] or 0xFF000000.toInt()
                val k = keyRamp.indexOf(c)
                if (k >= 0) px[i] = (px[i] and 0xFF000000.toInt()) or (ramp[k] and 0x00FFFFFF)
            }
        }
        if (level > 0) infectPixels(px, out.width, base, level, seed, ramp)
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        Sprite(base.name, out, base.w, base.h, base.ax, base.ay, base.frames, base.fps, base.hx, base.hy)
    }

    private fun infectPixels(px: IntArray, stride: Int, s: Sprite, level: Int, seed: Long, look: IntArray?) {
        val eyes = setOf(0xFF22182E.toInt(), 0xFFFFFFFF.toInt())
        val body = look?.toSet() ?: emptySet()
        for (f in 0 until s.frames) {
            val ox = f * s.w
            fun at(x: Int, y: Int) = if (x in 0 until s.w && y in 0 until s.h) y * stride + ox + x else -1
            fun isBody(i: Int) = i >= 0 && (px[i] ushr 24) > 0 && (px[i] or 0xFF000000.toInt()) !in eyes
            val cells = (0 until s.h).flatMap { y -> (0 until s.w).map { x -> x to y } }
                .filter { (x, y) -> isBody(at(x, y)) && (body.isEmpty() || (px[at(x, y)] or 0xFF000000.toInt()) in body) }
            if (cells.isEmpty()) continue
            val rnd = Random(seed * 31 + level)   // same spots on every frame
            fun paint(x: Int, y: Int, c: Int) { val i = at(x, y); if (isBody(i)) px[i] = (px[i] and 0xFF000000.toInt()) or (c and 0x00FFFFFF) }
            if (level >= 3) for ((x, y) in cells) {   // sickly cast over the whole body
                val i = at(x, y)
                val c = px[i]
                val r = ((c shr 16 and 255) * 0.6 + 0x7A * 0.4).toInt()
                val g = ((c shr 8 and 255) * 0.6 + 0x15 * 0.4).toInt()
                val b = ((c and 255) * 0.6 + 0x30 * 0.4).toInt()
                px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
            repeat(level * 3 + 1) {
                val (x, y) = cells[rnd.nextInt(cells.size)]
                paint(x, y, infect[2]); paint(x + 1, y, infect[3]); paint(x, y + 1, infect[1]); paint(x - 1, y, infect[1])
                if (rnd.nextBoolean()) paint(x, y - 1, infect[4])
                if (level >= 2) {   // veins creeping out from the blotch
                    var vx = x
                    var vy = y
                    repeat(3 + rnd.nextInt(4)) { vx += rnd.nextInt(-1, 2); vy += rnd.nextInt(-1, 2); paint(vx, vy, infect[1]) }
                }
                if (level >= 3 && rnd.nextInt(3) == 0) paint(x + 1, y + 1, 0xFFD9E36A.toInt())
            }
        }
    }

    companion object {
        @Volatile private var shared: SpriteAtlas? = null

        /** One atlas for the whole app, so the scene and the sheet share bitmaps. */
        fun of(context: Context): SpriteAtlas = shared ?: synchronized(this) { shared ?: SpriteAtlas(context.applicationContext).also { shared = it } }
    }

    /** A soft purple blob with two eyes, so a missing sprite still reads as "something lives here". */
    private fun fallback(w: Int, h: Int, frames: Int): Bitmap {
        val b = Bitmap.createBitmap(w * frames, h, Bitmap.Config.ARGB_8888)
        for (f in 0 until frames) {
            val cx = f * w + w / 2f
            val cy = h * 0.62f
            for (y in 0 until h) for (x in f * w until (f + 1) * w) {
                val d = ((x + 0.5f - cx) / (w * 0.36f)).let { it * it } + ((y + 0.5f - cy) / (h * 0.32f)).let { it * it }
                if (d <= 1f) b.setPixel(x, y, if (d > 0.8f) 0xFF433B63.toInt() else 0xFF8A74A6.toInt())
            }
            b.setPixel((cx - 3).toInt(), (cy - 2).toInt(), 0xFF2B2233.toInt())
            b.setPixel((cx + 2).toInt(), (cy - 2).toInt(), 0xFF2B2233.toInt())
        }
        return b
    }
}
