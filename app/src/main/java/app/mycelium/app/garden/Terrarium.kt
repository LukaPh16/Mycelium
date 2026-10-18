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
             val mouthX: Int, val mouthY: Int, val flies: IntArray, val surfaces: List<Spot> = slots,
             val window: IntArray = intArrayOf(18, 302, 18, 470, 158, 142)) {   // x0, x1, y0, y1, arch center y, arch radius
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
            val w = o.optJSONObject("window")
            val window = if (w == null) intArrayOf(18, 302, 18, 470, 158, 142)
                else intArrayOf(w.getInt("x0"), w.getInt("x1"), w.getInt("y0"), w.getInt("y1"), w.getInt("arch_cy"), w.getInt("arch_r"))
            Layout(world.getInt(0), world.getInt(1), slots, decor, mouth.getInt(0), mouth.getInt(1), IntArray(4) { f.getInt(it) }, surfaces, window)
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
    private val seasonal = HashMap<String, Bitmap>()
    private var lastFlash = -1L
    /** Lightning that struck this frame (true = close by), for the thunder that follows. */
    val thunders = mutableListOf<Boolean>()
    private val windowPath = android.graphics.Path().apply {
        val win = layout.window
        val x0 = win[0]; val x1 = win[1]; val y1 = win[3]; val cyArch = win[4]; val r = win[5]
        addRect(x0.toFloat(), cyArch.toFloat(), x1.toFloat(), y1.toFloat(), android.graphics.Path.Direction.CW)
        addCircle((x0 + x1) / 2f, cyArch.toFloat(), r.toFloat(), android.graphics.Path.Direction.CW)
    }
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
               lifted: String? = null, sky: Sky = Sky.CLEAR, season: Season = Season.SPRING): List<Placed> {
        val t = ms / 1000f
        val night = phase == SkyPhase.NIGHT
        canvas.drawColor(0xFF2A1E2E.toInt())
        val p = when (phase) { SkyPhase.DAY -> "day"; SkyPhase.DUSK -> "dusk"; SkyPhase.NIGHT -> "night" }
        if (atlas.has("sky_$p")) blit(atlas["sky_$p"], 0, 0, 0, cx, cy, anchored = false)
        celestial(cx, cy, ms, sky)
        blit(atlas["room_$p"], 0, 0, 0, cx, cy, anchored = false)
        thunders.clear()
        val flash = weather(cx, cy, ms, sky, season, night)
        blitSeasonal(atlas["terrain"], 0, 0, 0, cx, cy, season, anchored = false)
        for ((name, x, y) in layout.decor) if (!name.startsWith("glowcap")) {
            val s = atlas[name]
            blitSeasonal(s, if (ms == 0L) 0 else s.frameAt(ms + x * 37L), x, y, cx, cy, season)
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
        seasonParticles(cx, cy, ms, season)
        blit(atlas["glass_front"], 0, 0, 0, cx, cy, anchored = false)
        if (!night && (sky == Sky.CLEAR || sky == Sky.CLOUDY && season == Season.SUMMER)) blit(atlas["light_$p"], 0, 0, 0, cx, cy, anchored = false)
        if (flash > 0f) { tint.color = android.graphics.Color.argb((flash * 70).toInt(), 230, 236, 255); canvas.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), tint) }
        if (phase != SkyPhase.DAY) {   // fireflies inside the jar (more of them on summer nights)
            val fly = atlas["firefly"]
            val (x0, y0, x1, y1) = layout.flies.toList()
            for (i in 0 until if (season == Season.SUMMER) 18 else 10) {
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

    /**
     * Weather outside, clipped to the window pane behind the jar: a sky tint for clouds, rain or snow falling, fog
     * drifting, droplets running down the glass, frost in winter, and lightning. Returns the flash strength 0..1.
     */
    private fun weather(cx: Int, cy: Int, ms: Long, sky: Sky, season: Season, night: Boolean): Float {
        val t = ms / 1000f
        canvas.save()
        canvas.translate(-cx.toFloat(), -cy.toFloat())
        canvas.clipPath(windowPath)
        val (wx0, wx1, wy0, wy1) = layout.window.toList()
        val w = (wx1 - wx0).toFloat()
        val h = (wy1 - wy0).toFloat()
        fun fill(color: Int) { tint.color = color; canvas.drawRect(wx0.toFloat(), wy0.toFloat(), wx1.toFloat(), wy1.toFloat(), tint) }
        when (sky) {
            Sky.CLOUDY -> fill(if (night) 0x55141828 else 0x558A93A6)
            Sky.RAIN -> fill(if (night) 0x88101420.toInt() else 0x99586478.toInt())
            Sky.STORM -> fill(if (night) 0x990C0E18.toInt() else 0x993A4256.toInt())
            Sky.FOG -> fill(if (night) 0x88404858.toInt() else 0x88C9D2DA.toInt())
            Sky.SNOW -> fill(if (night) 0x55303A50 else 0x66C9D6E6)
            Sky.CLEAR -> Unit
        }
        var flash = 0f
        if (sky != Sky.CLEAR && sky != Sky.FOG) {   // drifting cloud cover: puffy, two-tone, slower ones further back
            for (i in 0 until if (sky == Sky.CLOUDY) 6 else 8) {
                val speed = 3f + (i % 3) * 2f
                val x = ((i * 97 + t * speed) % (w + 140)) + wx0 - 70
                val y = wy0 + 30 + (i * 53 % 230).toFloat()
                val scale = 0.8f + (i % 3) * 0.25f
                val (light, shade) = when {
                    night -> 0x90505870.toInt() to 0x90404860.toInt()
                    sky == Sky.CLOUDY -> 0xE6E8EEF4.toInt() to 0xE6C2CAD6.toInt()
                    else -> 0xE0A8B2C2.toInt() to 0xE0848EA0.toInt()
                }
                dot.color = shade
                for ((dx, dy, rw, rh) in CLOUD_PUFFS) canvas.drawOval(x + dx * scale, y + dy * scale + 3, x + (dx + rw) * scale, y + (dy + rh) * scale + 3, dot)
                dot.color = light
                for ((dx, dy, rw, rh) in CLOUD_PUFFS) canvas.drawOval(x + dx * scale, y + dy * scale, x + (dx + rw) * scale, y + (dy + rh) * scale, dot)
            }
        }
        if (sky == Sky.RAIN || sky == Sky.STORM) {   // falling rain, slanted, faster in a storm
            val count = if (sky == Sky.STORM) 170 else 110
            val speed = if (sky == Sky.STORM) 420f else 300f
            val slant = if (sky == Sky.STORM) 0.35f else 0.18f
            dot.color = if (night) 0x9A8AA8C8.toInt() else 0xB0C8DCF0.toInt()
            for (i in 0 until count) {
                val y = (i * 73.3f + t * speed * (0.8f + (i % 5) * 0.08f)) % (h + 20) + wy0 - 10
                val x = ((i * 37.7f + y * slant) % w + w) % w + wx0
                val len = if (sky == Sky.STORM) 8 else 6
                for (k in 0 until len) canvas.drawRect(x + k * slant, y + k, x + k * slant + 1, y + k + 1, dot)   // a slanted streak
            }
            dot.color = if (night) 0xAAB8D0E8.toInt() else 0xDDE8F4FF.toInt()   // droplets running down the glass
            for (i in 0 until 26) {
                val x = wx0 + (i * 53 % w.toInt())
                val y = wy0 + ((i * 91 + t * (6 + i % 4 * 3)) % h)
                canvas.drawRect(x.toFloat(), y, x + 2f, y + 2f, dot)
                dot.alpha = 90
                canvas.drawRect(x.toFloat(), y - 6, x + 1f, y, dot)
                dot.alpha = 0xDD
            }
        }
        if (sky == Sky.SNOW) {   // big soft flakes drifting down
            dot.color = 0xEEF4FAFF.toInt()
            for (i in 0 until 90) {
                val y = (i * 61.7f + t * (14f + i % 4 * 5)) % (h + 10) + wy0 - 5
                val x = ((i * 41.3f + kotlin.math.sin(t * 0.8f + i) * 6) % w + w) % w + wx0
                val size = if (i % 3 == 0) 2f else 1f
                canvas.drawRect(x, y, x + size, y + size, dot)
            }
        }
        if (sky == Sky.FOG) {   // slow drifting fog banks
            dot.color = if (night) 0x30A0A8B8 else 0x40F0F4F8
            for (i in 0 until 5) {
                val x = ((i * 90 + t * (5 + i * 2)) % (w + 160)) + wx0 - 80
                val y = wy0 + 60 + i * 70f
                canvas.drawOval(x, y, x + 150, y + 26, dot)
            }
        }
        if (season == Season.WINTER || sky == Sky.SNOW) {   // frost creeping in from the window edges
            dot.color = 0x99E8F4FD.toInt()
            for (i in 0 until 220) {
                val side = i % 4
                val d = (i * 7 % 9).toFloat()
                val along = (i * 37 % 1000) / 1000f
                val (fx, fy) = when (side) {
                    0 -> wx0 + d to wy0 + along * h
                    1 -> wx1 - 1 - d to wy0 + along * h
                    else -> wx0 + along * w to wy1 - 1 - d
                }
                if ((i * 13) % 3 != 0) canvas.drawRect(fx, fy, fx + 1, fy + 1, dot)
            }
        }
        if (sky == Sky.STORM && ms != 0L) {   // lightning every 7-13 s: a double flicker, thunder follows
            val period = 10_000L
            val k = ms / period
            val strikeAt = k * period + (k * 7919 % 6000)
            val since = ms - strikeAt
            if (since in 0..450) {
                flash = if (since < 90 || since in 160..260) 1f else 0.35f * (1 - since / 450f)
                if (k != lastFlash) { lastFlash = k; thunders += (k % 3 != 0L) }
                tint.color = android.graphics.Color.argb((flash * 200).toInt(), 240, 244, 255)
                canvas.drawRect(wx0.toFloat(), wy0.toFloat(), wx1.toFloat(), wy1.toFloat(), tint)
            }
        }
        canvas.restore()
        return flash
    }

    /**
     * The sun crosses the window from left to right between 06:00 and 20:00 (low, warm and red at dusk); the moon
     * crosses it between 18:00 and 07:00 in tonight's real phase. Both hide behind rain, snow and fog, and dim behind clouds.
     */
    private fun celestial(cx: Int, cy: Int, ms: Long, sky: Sky) {
        if (sky == Sky.RAIN || sky == Sky.STORM || sky == Sky.SNOW || sky == Sky.FOG) return
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f
        val win = layout.window
        val left = win[0] + 16f
        val right = win[1] - 16f
        fun arcY(f: Float) = win[2] + 300f - sin(PI.toFloat() * f) * 250f
        canvas.save()
        canvas.translate(-cx.toFloat(), -cy.toFloat())
        canvas.clipPath(windowPath)
        val alpha = if (sky == Sky.CLOUDY) 150 else 255
        if (hour in 6f..20f) {
            val f = (hour - 6f) / 14f
            val dusk = hour < 7.5f || hour > 17.5f
            val s = atlas[if (dusk) "sun_dusk" else "sun"]
            blit(s, if (ms == 0L) 0 else s.frameAt(ms), (left + (right - left) * f).toInt() + cx, arcY(f).toInt() + cy, cx, cy, alpha)
        }
        if (hour >= 18f || hour <= 7f) {
            val f = ((hour - 18f + 24f) % 24f) / 13f
            val m = atlas["moon"]
            blit(m, moonPhase(now.timeInMillis, m.frames), (left + (right - left) * f).toInt() + cx, arcY(f).toInt() + cy, cx, cy, alpha)
        }
        canvas.restore()
    }

    /** Petals in spring, leaves in autumn, a snow-globe flurry in winter, drifting gently inside the jar. */
    private fun seasonParticles(cx: Int, cy: Int, ms: Long, season: Season) {
        if (season == Season.SUMMER) return
        val t = ms / 1000f
        val (x0, y0, x1, y1) = layout.flies.toList()
        val count = when (season) { Season.WINTER -> 30; else -> 12 }
        for (i in 0 until count) {
            val speed = when (season) { Season.WINTER -> 9f + i % 3 * 3; else -> 12f + i % 4 * 3 }
            val y = y0 + ((i * 47.3f + t * speed) % (y1 - y0 + 40)) - 20
            val x = x0 + ((i * 59.1f) % (x1 - x0)) + kotlin.math.sin(t * 0.9f + i * 1.7f) * 8
            when (season) {
                Season.WINTER -> { dot.color = 0xEEFFFFFF.toInt(); canvas.drawRect(x - cx, y - cy, x - cx + 1, y - cy + 1, dot) }
                Season.SPRING -> {
                    dot.color = if (i % 2 == 0) 0xFFF59BB8.toInt() else 0xFFFFE1EA.toInt()
                    canvas.drawRect(x - cx, y - cy, x - cx + 2, y - cy + 1, dot)
                }
                else -> {   // autumn leaf, tumbling between two shapes
                    dot.color = if (i % 3 == 0) 0xFFE0703F.toInt() else if (i % 3 == 1) 0xFFF2BE5E.toInt() else 0xFFA0422A.toInt()
                    if (((t * 3 + i).toInt()) % 2 == 0) canvas.drawRect(x - cx, y - cy, x - cx + 2, y - cy + 1, dot)
                    else canvas.drawRect(x - cx, y - cy, x - cx + 1, y - cy + 2, dot)
                }
            }
        }
    }

    /** A sprite with its plant colors swapped for the season (blossom, grass and moss), cached per sprite and season. */
    private fun blitSeasonal(s: Sprite, f: Int, x: Int, y: Int, cx: Int, cy: Int, season: Season, anchored: Boolean = true) {
        if (season == Season.SPRING) return blit(s, f, x, y, cx, cy, anchored = anchored)
        val bmp = seasonal.getOrPut(s.name + "|" + season) {
            val b = s.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val swap = SEASON_SWAP.getValue(season)
            for (i in px.indices) {
                val c = px[i] or 0xFF000000.toInt()
                swap[c]?.let { px[i] = (px[i] and 0xFF000000.toInt()) or (it and 0x00FFFFFF) }
            }
            b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
            b
        }
        blit(Sprite(s.name, bmp, s.w, s.h, s.ax, s.ay, s.frames, s.fps, s.hx, s.hy), f, x, y, cx, cy, anchored = anchored)
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
/** Tonight's moon phase as a frame index (0 new .. frames/2 full): days since a known new moon over the synodic month. */
fun moonPhase(now: Long, frames: Int): Int {
    val knownNewMoon = 947_182_440_000L   // 2000-01-06 18:14 UTC
    val age = ((now - knownNewMoon) / 86_400_000.0) % 29.530588853
    return ((age / 29.530588853 * frames).roundToIntSafe()) % frames
}

private fun Double.roundToIntSafe() = Math.round(this).toInt()

/** One puffy cloud as overlapping ovals (x, y, width, height) in art pixels. */
private val CLOUD_PUFFS = listOf(listOf(0f, 8f, 22f, 12f), listOf(12f, 2f, 22f, 16f), listOf(28f, 6f, 20f, 13f), listOf(42f, 10f, 14f, 10f))

private fun ramp(vararg hex: String) = hex.map { android.graphics.Color.parseColor(it) }
private val BLOSSOM = ramp("#5A1F3D", "#B0446E", "#E7679A", "#F59BB8", "#FFE1EA")
private val GRASS = ramp("#2F6B33", "#46A83A", "#7FD04A", "#C6EC6A")
private val MOSS = ramp("#3E6B2A", "#5E9A33", "#8CC43F")

/** Plant colors per season (spring is the art as drawn): summer leafy green, autumn amber, winter frosted. */
private val SEASON_SWAP: Map<Season, Map<Int, Int>> = mapOf(
    Season.SUMMER to (BLOSSOM zip ramp("#183E24", "#2F7A2E", "#46A83A", "#7FD04A", "#C6EC6A")).toMap(),
    Season.AUTUMN to ((BLOSSOM zip ramp("#4A1E14", "#A0422A", "#E0703F", "#F7A06A", "#FFDCBC")) +
        (GRASS zip ramp("#6E5A26", "#9A7A33", "#C9A04A", "#E8C880")) + (MOSS zip ramp("#5A5226", "#8A7A33", "#B8A04A"))).toMap(),
    Season.WINTER to ((BLOSSOM zip ramp("#7A8FB8", "#9CB4DA", "#C9E2F7", "#E8F4FD", "#FFFFFF")) +
        (GRASS zip ramp("#6A8A8A", "#9ABCB4", "#CFE6E0", "#F4FFFC")) + (MOSS zip ramp("#6A8A8A", "#9ABCB4", "#CFE6E0"))).toMap(),
)

fun creatureSprite(pet: Pet, sleeping: Boolean, atlas: SpriteAtlas? = null): String {
    val suffix = if (sleeping) "_sleep" else ""
    var stage = pet.stage.coerceIn(1, 8)
    while (atlas != null && stage > 1 && !atlas.has("creature_${pet.preset}_s$stage$suffix")) stage--
    return "creature_${pet.preset}_s$stage$suffix"
}
