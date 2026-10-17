package app.mycelium.app.garden

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** A placed sprite: anchor at art pixel (x, y). [animated] ones are drawn every frame, the rest are baked once. */
class Decor(val sprite: String, val x: Int, val y: Int, val animated: Boolean = false, val phase: Int = 0, val alpha: Float = 1f)

/** One 16 px tile at tile column/row in art pixels. */
class Tile(val sprite: String, val x: Int, val y: Int, val depth: Int = 0)

/** Walkable top of an island: creatures stand with their feet on [y], between [x0] and [x1] (art pixels). */
class Surface(val island: Int, val x0: Int, val x1: Int, val y: Int, val blocked: MutableList<IntRange> = mutableListOf())

/**
 * The floating islands, in art pixels. Built from 16 px tiles: a grass top row, earth rows and a rock underside that
 * narrows into a tip. Same [width]/[height] always gives the same world (fixed seed), so creatures keep their places.
 */
class World(val width: Int, val height: Int) {
    val tiles = mutableListOf<Tile>()
    val decor = mutableListOf<Decor>()
    val surfaces = mutableListOf<Surface>()
    val clouds = mutableListOf<Decor>()
    val pillars = mutableListOf<Decor>()
    private val rng = Random(20261018)

    init {
        val cols = width / T
        // (left column, top row in px, width in columns) for each island, from the top of the sky down
        val plan = listOf(
            Triple(cols * 0.05, height * 0.22, cols * 0.56),
            Triple(cols * 0.60, height * 0.39, cols * 0.36),
            Triple(cols * 0.04, height * 0.41, cols * 0.30),
            Triple(cols * 0.30, height * 0.58, cols * 0.50),
            Triple(cols * 0.05, height * 0.77, cols * 0.40),
            Triple(cols * 0.58, height * 0.74, cols * 0.34),
        )
        plan.forEachIndexed { i, (cx, ty, cw) -> island(i, cx.toInt(), (ty / T).toInt() * T, max(4, cw.toInt())) }
        bridge(surfaces[2], surfaces[1])
        dressUp()
        for (i in 0 until 6) clouds += Decor("cloud_${i % 3 + 1}", rng.nextInt(-40, width), (height * (0.05 + i * 0.15)).toInt() + rng.nextInt(-20, 20), phase = rng.nextInt(1000))
        for (i in 0 until 4) pillars += Decor("pillar_${i % 2 + 1}", (width * (0.1 + i * 0.25)).toInt() + rng.nextInt(-20, 20), (height * (0.25 + (i % 2) * 0.3)).toInt(), alpha = 0.55f)
    }

    private fun island(index: Int, col: Int, top: Int, cw: Int) {
        val x0 = col * T
        tiles += Tile("island_top_left", x0, top)
        for (c in 1 until cw - 1) tiles += Tile("island_top", x0 + c * T, top)
        tiles += Tile("island_top_right", x0 + (cw - 1) * T, top)
        val earthRows = 1
        for (r in 1..earthRows) {
            val y = top + r * T
            tiles += Tile("island_edge_left", x0, y)
            for (c in 1 until cw - 1) tiles += Tile("island_fill", x0 + c * T, y)
            tiles += Tile("island_edge_right", x0 + (cw - 1) * T, y)
        }
        var left = 0
        var right = cw - 1
        var y = top + (earthRows + 1) * T
        var depth = 0
        while (right - left >= 1) {
            if (right - left <= 1) {
                for (c in left..right) tiles += Tile("rock_under_tip", x0 + c * T, y, depth)
                decor += Decor("hanging_root", x0 + left * T + 8, y + T - 2, animated = true, phase = index)
                break
            }
            tiles += Tile("rock_under_left", x0 + left * T, y, depth)
            for (c in left + 1 until right) tiles += Tile("rock_under_mid", x0 + c * T, y, depth)
            tiles += Tile("rock_under_right", x0 + right * T, y, depth)
            if (rng.nextInt(3) == 0) decor += Decor("hanging_root", x0 + (left + 1 + rng.nextInt(max(1, right - left - 1))) * T + 4, y + T - 3, animated = true, phase = rng.nextInt(4))
            left += if (rng.nextInt(3) == 0) 2 else 1
            right -= if (rng.nextInt(3) == 0) 2 else 1
            if (depth >= 3) { left++; right-- }
            y += T
            depth++
        }
        surfaces += Surface(index, x0 + 6, x0 + cw * T - 6, top + 3)
        // vines hanging over the island sides
        decor += Decor("vine_hanging_${index % 2 + 1}", x0 + 3, top + 4, animated = true, phase = index)
        if (cw > 5) decor += Decor("vine_hanging_${(index + 1) % 2 + 1}", x0 + cw * T - 4, top + 4, animated = true, phase = index + 1)
    }

    /** A rope bridge between two islands at about the same height (from the right end of [a] to the left end of [b]). */
    private fun bridge(a: Surface, b: Surface) {
        val from = a.x1 - 2
        val to = b.x0 + 2
        var x = from
        while (x < to) {
            val f = (x - from).toFloat() / max(1, to - from)
            val sag = (kotlin.math.sin(f * Math.PI) * 4).toInt()
            tiles += Tile("rope_bridge", x, (a.y + (b.y - a.y) * f).toInt() - 4 + sag)
            x += T
        }
    }

    private fun place(s: Surface, sprite: String, x: Int, width: Int, animated: Boolean = false, block: Boolean = true) {
        decor += Decor(sprite, x, s.y, animated, phase = x)
        if (block) s.blocked += (x - width / 2)..(x + width / 2)
    }

    private fun dressUp() {
        val top = surfaces[0]
        place(top, "tree_blossom_1", top.x0 + 46, 44)
        place(top, "banner", top.x1 - 6, 10, animated = true)
        place(top, "flowers_1", top.x0 + 86, 14)
        place(top, "fern_1", top.x0 + 14, 18)
        surfaces[1].let { s ->
            place(s, "tree_blossom_2", s.x1 - 30, 40)
            place(s, "mushroom_glow_1", s.x0 + 18, 16, animated = true)
        }
        surfaces[2].let { s -> place(s, "bonsai_1", s.x0 + 16, 24); place(s, "moss_1", s.x0 + 44, 16, block = false) }
        surfaces[3].let { s ->
            val pool = s.x0 + (s.x1 - s.x0) / 2
            for (i in -1..1) tiles += Tile("water", pool + i * T - 8, s.y - 2)
            s.blocked += (pool - 30)..(pool + 30)
            decor += Decor("lily_pads_1", pool - 4, s.y + 3)
            decor += Decor("reeds_1", pool - 28, s.y)
            decor += Decor("reeds_2", pool + 28, s.y)
            decor += Decor("waterfall", s.x1 + 2, s.y + 6, animated = true)
            place(s, "mushroom_glow_2", s.x0 + 14, 16, animated = true)
        }
        surfaces[4].let { s -> place(s, "fern_2", s.x0 + 12, 18); place(s, "flowers_2", s.x1 - 20, 14) }
        surfaces[5].let { s -> place(s, "bonsai_2", s.x1 - 18, 24); place(s, "moss_2", s.x0 + 20, 16, block = false) }
        for (s in surfaces) {
            var x = s.x0 + 6
            while (x < s.x1 - 6) {
                if (s.blocked.none { x in it }) decor += Decor("grass_tuft_${1 + (x / 7) % 2}", x, s.y, animated = true, phase = x)
                x += 18 + rng.nextInt(16)
            }
        }
    }

    companion object {
        const val T = 16
    }
}
