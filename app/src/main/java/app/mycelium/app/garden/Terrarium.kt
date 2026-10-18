package app.mycelium.app.garden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

enum class SkyPhase { DAY, DUSK, NIGHT }

fun skyPhaseNow(): SkyPhase = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 7..16 -> SkyPhase.DAY
    5, 6, 17, 18, 19 -> SkyPhase.DUSK
    else -> SkyPhase.NIGHT
}

/** Monotonic ms for the animation clock. */
fun clockMs(): Long = SystemClock.uptimeMillis()

/** Where things go in the scene, from assets/sprites/terrarium_layout.json (written by scripts/gen_sprites.py). */
class Layout(val worldW: Int, val worldH: Int, val slots: List<Spot>, val decor: List<Triple<String, Int, Int>>,
             val mouthX: Int, val mouthY: Int, val flies: IntArray, val surfaces: List<Spot> = slots) {
    /** The walkable point nearest to (x, y): a dropped creature lands there, never in mid-air. */
    fun nearestSurface(x: Float, y: Float): Spot = surfaces.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) } ?: Spot(x.toInt(), y.toInt())

    companion object {
        fun load(context: Context): Layout = runCatching {
            val o = JSONObject(context.assets.open("sprites/terrarium_layout.json").bufferedReader().use { it.readText() })
            val world = o.getJSONArray("world")
            val slots = o.getJSONArray("slots").let { a -> (0 until a.length()).map { val s = a.getJSONObject(it); Spot(s.getInt("x"), s.getInt("y")) } }
            val decor = o.getJSONArray("decor").let { a -> (0 until a.length()).map { val d = a.getJSONObject(it); Triple(d.getString("sprite"), d.getInt("x"), d.getInt("y")) } }
            val mouth = o.getJSONArray("mouth")
            val f = o.getJSONArray("fireflies")
            val surfaces = o.optJSONArray("surfaces")?.let { lines ->
                (0 until lines.length()).flatMap { i -> lines.getJSONArray(i).let { l -> (0 until l.length()).map { l.getJSONArray(it).let { p -> Spot(p.getInt(0), p.getInt(1)) } } } }
            }.orEmpty().ifEmpty { slots }
            Layout(world.getInt(0), world.getInt(1), slots, decor, mouth.getInt(0), mouth.getInt(1), IntArray(4) { f.getInt(it) }, surfaces)
        }.getOrElse {
            Layout(320, 600, (0 until 12).map { Spot(40 + it * 22, 440) }, emptyList(), 160, 80, intArrayOf(40, 140, 280, 440))
        }
    }
}

/** Where a creature stands: feet at art pixel ([x], [y]). */
class Spot(val x: Int, val y: Int)

/** What a creature looks like on screen this frame (for hit tests and name tags). */
class Placed(val pet: Pet, val spot: Spot, val sprite: Sprite, val snoops: List<Pair<SnoopVisit, Spot>>)

/**
 * Draws the terrarium in art pixels into an offscreen bitmap: room and sky (by clock phase), the jar's inside, moving
 * plants and water, the night tint, glowing things, creatures with their infection, hats and Snoops, the red data
 * threads Snoops pull out through the lid, effects, then the glass in front. The view scales it up (nearest-neighbor).
 */
class TerrariumRenderer(val atlas: SpriteAtlas, val layout: Layout, viewArtW: Int, viewArtH: Int) {
    val frame: Bitmap = Bitmap.createBitmap(viewArtW + 2, viewArtH + 2, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(frame)
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val tint = Paint()
    private val dot = Paint().apply { isAntiAlias = false }
    private val src = Rect()
    private val beats = HashMap<String, Long>()
    /** Mood moments that started this frame (a creature just showed its bubble), for sounds. */
    val moodStarts = mutableListOf<Pair<Pet, Mood>>()
    private val dst = Rect()

    private fun blit(s: Sprite, f: Int, x: Int, y: Int, cx: Int, cy: Int, alpha: Int = 255, anchored: Boolean = true) {
        val l = (if (anchored) x - s.ax else x) - cx
        val t = (if (anchored) y - s.ay else y) - cy
        src.set((f % s.frames) * s.w, 0, (f % s.frames) * s.w + s.w, s.h)
        dst.set(l, t, l + s.w, t + s.h)
        paint.alpha = alpha
        canvas.drawBitmap(s.bitmap, src, dst, paint)
    }

    /** Renders the region starting at world art pixel ([cx], [cy]); returns the creatures as placed. */
    fun render(cx: Int, cy: Int, ms: Long, phase: SkyPhase, pets: List<Pet>, spots: Map<String, Spot>, selected: String?, fx: GardenFx,
               lifted: String? = null): List<Placed> {
        val t = ms / 1000f
        val night = phase == SkyPhase.NIGHT
        canvas.drawColor(0xFF2A1E2E.toInt())
        val p = when (phase) { SkyPhase.DAY -> "day"; SkyPhase.DUSK -> "dusk"; SkyPhase.NIGHT -> "night" }
        blit(atlas["room_$p"], 0, 0, 0, cx, cy, anchored = false)
        blit(atlas["terrain"], 0, 0, 0, cx, cy, anchored = false)
        for ((name, x, y) in layout.decor) if (!name.startsWith("glowcap")) {
            val s = atlas[name]
            blit(s, if (ms == 0L) 0 else s.frameAt(ms + x * 37L), x, y, cx, cy)
        }
        when (phase) {   // evening and night darken the room and the jar; glowing things and creatures come after
            SkyPhase.NIGHT -> { tint.color = 0x701B1A33; canvas.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), tint) }
            SkyPhase.DUSK -> { tint.color = 0x30433B63; canvas.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), tint) }
            else -> Unit
        }
        for ((name, x, y) in layout.decor) if (name.startsWith("glowcap")) {
            val s = atlas[name]
            blit(s, if (ms == 0L || !night && phase != SkyPhase.DUSK) 0 else s.frameAt(ms + x * 37L), x, y, cx, cy)
        }
        val placed = mutableListOf<Placed>()
        val now = System.currentTimeMillis()
        moodStarts.clear()
        for (pet in pets.sortedBy { spots[it.pkg]?.y ?: 0 }) {
            val spot = spots[pet.pkg] ?: continue
            placed += drawPet(pet, spot, cx, cy, ms, now, pet.pkg == selected || pet.pkg == lifted, fx, lift = if (pet.pkg == lifted) 6 else 0)
        }
        threads(placed, cx, cy, ms)
        blit(atlas["glass_front"], 0, 0, 0, cx, cy, anchored = false)
        if (!night) blit(atlas["light_$p"], 0, 0, 0, cx, cy, anchored = false)
        if (phase != SkyPhase.DAY) {   // fireflies inside the jar
            val fly = atlas["firefly"]
            val (x0, y0, x1, y1) = layout.flies.toList()
            for (i in 0 until 10) {
                val x = x0 + (x1 - x0) * ((i * 37 % 100) / 100f) + sin(t * 0.5f + i) * 14
                val y = y0 + (y1 - y0) * ((i * 61 % 100) / 100f) + sin(t * 0.8f + i * 2f) * 9
                blit(fly, if (ms == 0L) 0 else ((ms / 260 + i) % 2).toInt(), x.toInt(), y.toInt(), cx, cy)
            }
        } else {
            val mote = atlas["mote"]
            for (i in 0 until 14) {
                val x = (i * 53 % layout.worldW) + sin(t * 0.3f + i) * 8
                val y = 120 + (i * 97 % 300) + sin(t * 0.5f + i) * 6
                blit(mote, 0, x.toInt(), y.toInt(), cx, cy, alpha = 160)
            }
        }
        return placed
    }

    private fun drawPet(pet: Pet, spot: Spot, cx: Int, cy: Int, ms: Long, now: Long, selected: Boolean, fx: GardenFx, lift: Int = 0): Placed {
        val hopStart = fx.hops[pet.pkg]
        val asleep = pet.sleeping(now) && hopStart == null
        val s = atlas.creature(creatureSprite(pet, asleep, atlas), pet.look(atlas), pet.infection, crc(pet.pkg))
        val mood = pet.mood(now)
        // a mood moment every 8 s (each creature on its own beat): bubble over the head plus a little behavior
        val beat = if (ms == 0L) 0L else (ms + crc(pet.pkg) % 8000) % 8000
        val moment = ms == 0L || beat < 2200
        if (ms != 0L) {
            val cycle = (ms + crc(pet.pkg) % 8000) / 8000
            if (beats.put(pet.pkg, cycle).let { it != null && it != cycle }) moodStarts += pet to (if (asleep) Mood.SLEEPY else mood)
        }
        var moodHop = 0
        var shake = 0
        if (moment && ms != 0L) when (mood) {
            Mood.HAPPY -> moodHop = intArrayOf(0, -2, -3, -2, 0).getOrElse((beat / 70).toInt()) { 0 }
            Mood.GRUMPY -> shake = if ((ms / 70) % 2 == 0L) -1 else 1
            else -> Unit
        }
        val droop = if (mood == Mood.SICK) 1 else 0
        val hop = -lift + moodHop + droop + if (hopStart != null && ms != 0L) intArrayOf(0, -3, -6, -4, -1, 0, -2, 0).getOrElse(((clockMs() - hopStart).coerceAtLeast(0) / 70).toInt()) { 0 } else 0
        if (selected) ring(spot, s.w, cx, cy)
        val f = if (ms == 0L) 0 else s.frameAt((if (mood == Mood.SICK) ms / 2 else ms) + crc(pet.pkg) % 1000)   // sick: sluggish
        blit(s, f, spot.x + shake, spot.y + hop, cx, cy)
        val hx = spot.x - s.ax + s.hx
        val hy = spot.y - s.ay + s.hy + hop
        pet.hat(atlas)?.let { blit(atlas["hat_$it"], 0, hx, hy + 2, cx, cy) }
        if (asleep) {
            val z = atlas["zzz"]
            blit(z, if (ms == 0L) 2 else z.frameAt(ms), hx + 6, hy - 2, cx, cy)
        } else if (moment && hopStart == null && lift == 0) {
            val bubble = when (mood) { Mood.HAPPY -> "mood_happy"; Mood.GRUMPY -> "mood_grumpy"; Mood.SICK -> "mood_sick"; else -> null }
            if (bubble != null) { val b = atlas[bubble]; blit(b, if (ms == 0L) 0 else b.frameAt(ms), hx + 7, hy - 1, cx, cy) }
        }
        val snoops = pet.visiting.take(3).mapIndexed { n, v ->
            val (dx, dy) = when (n) { 0 -> -s.w / 2 + 3 to -s.h / 2; 1 -> s.w / 2 - 3 to -s.h / 3; else -> 2 to -s.h + 4 }
            val wob = if (ms == 0L) 0 else (sin(ms / 300.0 + n * 2) * 1.5).toInt()
            val at = Spot(spot.x + dx + wob, spot.y + dy + hop)
            val sp = atlas["snoop_${1 + (crc(v.company) % 3).toInt()}"]
            blit(sp, if (ms == 0L) 0 else sp.frameAt(ms + n * 250L), at.x, at.y + 8, cx, cy)
            v to at
        }
        for (pf in fx.poofs) if (pf.pkg == pet.pkg) {
            val (dx, dy) = when (pf.slot) { 0 -> -s.w / 2 + 3 to -s.h / 2; 1 -> s.w / 2 - 3 to -s.h / 3; else -> 2 to -s.h + 4 }
            val poof = atlas["poof"]
            val k = ((clockMs() - pf.start) / 100).toInt()
            if (k < poof.frames) blit(poof, k, spot.x + dx, spot.y + dy, cx, cy)
            val sparkle = atlas["sparkle"]
            if (k in 1..6) for (j in 0..2) blit(sparkle, (k + j) % sparkle.frames, spot.x + dx - 9 + j * 9, spot.y + dy - 10 - (j % 2) * 7, cx, cy)
            if (k in 3..9) blit(atlas["heart"], 0, hx, hy - 6 - k, cx, cy)
        }
        return Placed(pet, spot, s, snoops)
    }

    /** Red dotted threads: data pulled from an infected creature by its Snoop, up and out through the lid. */
    private fun threads(placed: List<Placed>, cx: Int, cy: Int, ms: Long) {
        for (pl in placed) for ((_, at) in pl.snoops) {
            val sx = at.x.toFloat()
            val sy = (at.y + 2).toFloat()
            val len = hypot(layout.mouthX - sx, layout.mouthY - sy)
            val n = (len / 2).toInt()
            for (i in 0..n) {
                val f = i / n.toFloat()
                if (((i + ms / 90) % 5).toInt() > 2) continue   // moving dashes, flowing up toward the lid
                val x = sx + (layout.mouthX - sx) * f + sin(f * PI * 3 + at.x).toFloat() * 6 * (1 - f)
                val y = sy + (layout.mouthY - sy) * f
                dot.color = if (i % 3 == 0) 0xFFFF8C8C.toInt() else 0xEEE8455A.toInt()
                canvas.drawRect(x - cx, y - cy, x - cx + 2, y - cy + 1, dot)
                dot.color = 0x887A1530.toInt()
                canvas.drawRect(x - cx, y - cy + 1, x - cx + 2, y - cy + 2, dot)
            }
        }
    }

    private fun ring(spot: Spot, w: Int, cx: Int, cy: Int) {
        val rx = w / 2 + 2
        for (deg in 0 until 360 step 6) {
            val a = Math.toRadians(deg.toDouble())
            val x = spot.x + Math.round(kotlin.math.cos(a) * rx).toInt()
            val y = spot.y + Math.round(sin(a) * 4).toInt()
            dot.color = if (deg % 24 < 12) 0xFFF1E4C3.toInt() else 0xFFC6EC6A.toInt()
            canvas.drawRect((x - cx).toFloat(), (y - cy).toFloat(), (x - cx + 1).toFloat(), (y - cy + 1).toFloat(), dot)
        }
    }
}

/** The sprite for a creature's stage (1..8); art made for fewer stages falls back to its highest one. */
fun creatureSprite(pet: Pet, sleeping: Boolean, atlas: SpriteAtlas? = null): String {
    val suffix = if (sleeping) "_sleep" else ""
    var stage = pet.stage.coerceIn(1, 8)
    while (atlas != null && stage > 1 && !atlas.has("creature_${pet.preset}_s$stage$suffix")) stage--
    return "creature_${pet.preset}_s$stage$suffix"
}
