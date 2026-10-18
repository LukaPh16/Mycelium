package app.mycelium.app.garden

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.mycelium.app.R
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.LocalReduceMotion
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelPanel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val FLICKER_MS = 2600L
private const val FLASH_MS = 350L

/**
 * A creature reached a new stage: rays turn, the old and new forms flicker as white silhouettes faster and faster,
 * a white burst, then the new creature with sparkles and a card. With Reduce motion it goes straight to the reveal.
 */
@Composable
fun EvolutionMoment(pet: Pet, from: Int, to: Int, onDone: () -> Unit) {
    val pal = LocalPal.current
    val atlas = SpriteAtlas.of(LocalContext.current)
    val still = LocalReduceMotion.current
    val look = pet.look(atlas)
    val oldS = remember(pet.pkg, from) { atlas.creature(creatureSprite(Pet(pet.pkg, pet.label, pet.preset, from, pet.stageCount, pet.growth, pet.next, pet.seed, pet.contactsToday, pet.blockedToday, pet.lastSeen, emptyList()), false, atlas), look, 0, 0) }
    val newS = remember(pet.pkg, to) { atlas.creature(creatureSprite(pet, false, atlas), look, 0, 0) }
    val sparkle = remember { atlas["sparkle"] }
    var t by remember { mutableLongStateOf(if (still) FLICKER_MS + FLASH_MS else 0L) }
    val sounds = app.mycelium.app.ui.LocalSounds.current
    val heard = remember { intArrayOf(-1, 0, 0) }   // last flicker swap ticked, burst played, fanfare played
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it } - t
        while (true) withFrameMillis { t = it - start }
    }
    val revealed = t >= FLICKER_MS + FLASH_MS
    run {   // one tick per flicker swap (rising as it speeds up), then the burst, then the fanfare
        var acc = 0L
        var swaps = 0
        var period = 300L
        while (acc + period <= t && acc < FLICKER_MS) { acc += period; swaps++; period = (period * 0.82f).toLong().coerceAtLeast(50L) }
        if (t < FLICKER_MS && swaps > heard[0]) { heard[0] = swaps; sounds?.play("evo_tick", 0.8f + swaps * 0.05f, 0.7f) }
        if (t >= FLICKER_MS - 250 && heard[1] == 0 && !still) { heard[1] = 1; sounds?.play("evo_burst") }
        if (revealed && heard[2] == 0) { heard[2] = 1; sounds?.play("evo_fanfare") }
    }
    val cd = stringResource(R.string.evo_cd, pet.label)
    Box(
        Modifier.fillMaxSize().background(Color(0xE61B1A33))
            .clickable(remember { MutableInteractionSource() }, null) {}   // the moment owns the screen until "Lovely!"
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.4f)
            rays(c, t, pal.gold)
            val scale = (min(size.width, size.height) * 0.42f / maxOf(newS.h, oldS.h)).toInt().coerceAtLeast(2)
            if (!revealed && t < FLICKER_MS) {
                // flicker between old and new, speeding up: 300 ms per swap down to 50 ms
                var acc = 0L
                var showNew = false
                var period = 300L
                while (acc + period <= t) { acc += period; showNew = !showNew; period = (period * 0.82f).toLong().coerceAtLeast(50L) }
                sprite(if (showNew) newS else oldS, c, scale, silhouette = true)
            } else if (!revealed) {
                sprite(newS, c, scale, silhouette = true)
                drawRect(Color.White.copy(alpha = 1f - (t - FLICKER_MS) / FLASH_MS.toFloat()))   // burst
            } else {
                sprite(newS, c, scale, silhouette = false, frame = newS.frameAt(t))
                for (i in 0 until 8) {   // sparkles orbiting the new form
                    val a = i / 8f * 6.283f + t / 900f
                    val r = newS.w * scale * 0.7f
                    sprite(sparkle, Offset(c.x + cos(a) * r, c.y - newS.h * scale / 2f + sin(a) * r * 0.6f), scale / 2 + 1, false, frame = ((t / 120 + i) % 3).toInt(), anchorCenter = true)
                }
            }
        }
        if (revealed) Column(Modifier.align(Alignment.BottomCenter).padding(24.dp).padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PixelPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.evo_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, color = pal.ink, textAlign = TextAlign.Center)
                Text(stringResource(R.string.evo_body, pet.label, to, stageName(to)), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                    color = pal.ink, textAlign = TextAlign.Center)
                PixelButton(stringResource(R.string.evo_ok), onDone, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun DrawScope.rays(c: Offset, t: Long, gold: Color) {
    val turn = t / 4000f * 6.283f
    for (i in 0 until 12) {
        val a = turn + i / 12f * 6.283f
        val far = size.maxDimension
        val w = 0.09f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(c.x, c.y)
            lineTo(c.x + cos(a - w) * far, c.y + sin(a - w) * far)
            lineTo(c.x + cos(a + w) * far, c.y + sin(a + w) * far)
            close()
        }
        drawPath(path, gold.copy(alpha = if (i % 2 == 0) 0.16f else 0.08f))
    }
}

/** A sprite frame scaled by whole pixels, nearest-neighbor; feet at [at] (or centered), optionally as a white silhouette. */
private fun DrawScope.sprite(s: Sprite, at: Offset, scale: Int, silhouette: Boolean, frame: Int = 0, anchorCenter: Boolean = false) {
    val w = s.w * scale
    val h = s.h * scale
    val left = (at.x - (if (anchorCenter) w / 2f else s.ax * scale.toFloat())).toInt()
    val top = (at.y - (if (anchorCenter) h / 2f else s.ay * scale.toFloat() - h / 2f)).toInt()
    drawImage(s.image, srcOffset = IntOffset((frame % s.frames) * s.w, 0), srcSize = IntSize(s.w, s.h), dstOffset = IntOffset(left, top),
        dstSize = IntSize(w, h), filterQuality = FilterQuality.None,
        colorFilter = if (silhouette) ColorFilter.tint(Color.White, BlendMode.SrcIn) else null)
}
