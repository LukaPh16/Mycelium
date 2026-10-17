package app.mycelium.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.mycelium.app.R
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

// ---- pixel drawing primitives (everything snaps to whole device pixels) ----

/** A rectangle with notched (pixel-rounded) corners of one art pixel. */
private fun DrawScope.notched(color: Color, x: Float, y: Float, w: Float, h: Float, p: Float) {
    if (w <= 2 * p || h <= 2 * p) { drawRect(color, Offset(x, y), Size(w, h)); return }
    drawRect(color, Offset(x + p, y), Size(w - 2 * p, h))
    drawRect(color, Offset(x, y + p), Size(w, h - 2 * p))
}

enum class PanelStyle { Paper, Wood, Quiet }

/** Paper sheet in a wood frame, or a plank of wood. All panels in the app use this. */
fun Modifier.pixelPanel(style: PanelStyle, pal: Pal, p: Float): Modifier = drawBehind {
    val w = size.width.roundToInt().toFloat()
    val h = size.height.roundToInt().toFloat()
    when (style) {
        PanelStyle.Paper -> {
            notched(pal.woodDark, 0f, 0f, w, h, p)
            notched(pal.wood, p, p, w - 2 * p, h - 2 * p, p)
            notched(pal.paper, 3 * p, 3 * p, w - 6 * p, h - 6 * p, p)
            drawRect(pal.woodLight, Offset(2 * p, p), Size(w - 4 * p, p))             // frame highlight
            drawRect(pal.paperEdge, Offset(3 * p, h - 4 * p), Size(w - 6 * p, p))    // paper shade
        }
        PanelStyle.Wood -> {
            notched(pal.woodDark, 0f, 0f, w, h, p)
            notched(pal.wood, p, p, w - 2 * p, h - 2 * p, p)
            drawRect(pal.woodLight, Offset(2 * p, p), Size(w - 4 * p, p))
            val grain = pal.woodDark.copy(alpha = 0.22f)                              // staggered grain dashes
            var row = 0
            var y = 6 * p
            while (y < h - 4 * p) {
                var x = 4 * p + (row % 3) * 9 * p
                while (x < w - 8 * p) { drawRect(grain, Offset(x, y), Size(4 * p, p)); x += 27 * p }
                y += 7 * p; row++
            }
        }
        PanelStyle.Quiet -> {
            notched(pal.paperEdge, 0f, 0f, w, h, p)
            notched(pal.paper, p, p, w - 2 * p, h - 2 * p, p)
        }
    }
}

@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    style: PanelStyle = PanelStyle.Paper,
    padding: Int = 14,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPal.current
    val p = pixel()
    Column(
        modifier.pixelPanel(style, pal, p).padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

enum class ButtonKind { Primary, Secondary, Danger, Quiet }

/** Chunky pixel button: outline, highlight on top, shadow below; pressing moves it down by one art pixel. */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
) {
    val pal = LocalPal.current
    val p = pixel()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (fill, shade, fg) = when (kind) {
        ButtonKind.Primary -> Triple(pal.leaf, pal.moss, pal.onAccent)
        ButtonKind.Secondary -> Triple(pal.paper, pal.paperEdge, pal.ink)
        ButtonKind.Danger -> Triple(pal.berry, pal.woodDark, pal.onAccent)
        ButtonKind.Quiet -> Triple(pal.paperEdge, pal.paperEdge, pal.ink)
    }
    val down = if (pressed && enabled) p else 0f
    val px = with(LocalDensity.current) { p.toDp() }
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp, minWidth = 64.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .drawBehind {
                val w = size.width.roundToInt().toFloat()
                val h = size.height.roundToInt().toFloat()
                val top = 2 * p + down
                notched(pal.woodDark, 0f, top, w, h - top, p)                   // outline
                notched(shade, p, top + p, w - 2 * p, h - top - 2 * p, p)        // shadow (visible below the face)
                val faceH = h - top - 2 * p - (if (down > 0) 0f else 2 * p)
                notched(fill, p, top + p, w - 2 * p, faceH, p)
                if (kind != ButtonKind.Quiet) drawRect(Color.White.copy(alpha = 0.28f), Offset(2 * p, top + p), Size(w - 4 * p, p))
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .offset(y = if (down > 0) px else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Plain words for something that is not obvious: an "i" in a circle that opens a short sheet. */
data class Info(@param:StringRes val title: Int, @param:StringRes val body: Int)

@Composable
fun InfoButton(info: Info, modifier: Modifier = Modifier, tint: Color? = null) {
    var open by remember { mutableStateOf(false) }
    val pal = LocalPal.current
    val ringColor = tint ?: if (pal.night) pal.gold else pal.wood
    val label = stringResource(R.string.info_about, stringResource(info.title))
    Box(
        modifier
            .size(36.dp)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val n = 9                                            // 9x9 pixel circle
            val cell = max(1f, (size.width / n).roundToInt().toFloat())
            val ring = listOf("..XXXXX..", ".X.....X.", "X...i...X", "X.......X", "X...i...X", "X...i...X", "X...i...X", ".X.....X.", "..XXXXX..")
            val ox = ((size.width - n * cell) / 2).roundToInt().toFloat()
            val oy = ((size.height - n * cell) / 2).roundToInt().toFloat()
            for ((y, row) in ring.withIndex()) for ((x, ch) in row.withIndex()) {
                val c = when (ch) { 'X' -> ringColor; 'i' -> tint ?: pal.ink; else -> null } ?: continue
                drawRect(c, Offset(ox + x * cell, oy + y * cell), Size(cell, cell))
            }
        }
    }
    if (open) InfoSheet(info, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(info: Info, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pal.bg) {
        PixelPanel(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelSprite(R.drawable.sprite_mushroom_happy, artPixelDp = 1.5f)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(info.title), style = MaterialTheme.typography.titleMedium, color = pal.ink)
            }
            Text(stringResource(info.body), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
            PixelButton(stringResource(R.string.got_it), onDismiss, Modifier.align(Alignment.End), kind = ButtonKind.Secondary)
        }
    }
}

/** Title in the pixel font with an optional InfoButton. */
@Composable
fun SectionHeader(title: String, info: Info? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = LocalPal.current.ink)
        if (info != null) InfoButton(info)
    }
}

/** A label, a value and an optional InfoButton: one line, or label above value when the value is long. */
@Composable
fun StatLine(label: String, value: String, info: Info? = null) {
    val pal = LocalPal.current
    if (value.length > 24) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
                if (info != null) InfoButton(info)
            }
            Text(value, style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
        Spacer(Modifier.width(8.dp))
        Text(value, Modifier.widthIn(max = 210.dp), style = MaterialTheme.typography.bodyMedium, color = pal.ink, textAlign = TextAlign.End)
        if (info != null) InfoButton(info) else Spacer(Modifier.width(8.dp))
    }
}

/** A dialogue box: paper panel with a wood name plate on top. */
@Composable
fun PixelDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogBox(title, actions, content = content)
    }
}

@Composable
fun DialogBox(title: String, actions: @Composable (() -> Unit)? = null, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    Box(Modifier.fillMaxWidth()) {
        PixelPanel(Modifier.padding(top = 18.dp).fillMaxWidth(), padding = 18) {
            Spacer(Modifier.height(10.dp))
            Column(
                if (scroll) Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
            if (actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
        Box(Modifier.padding(start = 14.dp).pixelPanel(PanelStyle.Wood, pal, pixel()).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = pal.onAccent.takeIf { !pal.night } ?: pal.ink)
        }
    }
}

/** A drawable-nodpi sprite drawn at a whole number of device pixels per art pixel, nearest-neighbor. */
@Composable
fun PixelSprite(@DrawableRes id: Int, artPixelDp: Float = 3f, modifier: Modifier = Modifier) {
    PixelBitmap(ImageBitmap.imageResource(id), artPixelDp, modifier)
}

@Composable
fun PixelBitmap(image: ImageBitmap, artPixelDp: Float = 3f, modifier: Modifier = Modifier, src: IntOffset = IntOffset.Zero, srcSize: IntSize? = null) {
    val density = LocalDensity.current
    val k = max(1, (density.density * artPixelDp).toInt())   // floor: never larger than asked
    val s = srcSize ?: IntSize(image.width, image.height)
    val wDp = with(density) { (s.width * k).toDp() }
    val hDp = with(density) { (s.height * k).toDp() }
    Canvas(modifier.size(wDp, hDp)) {
        drawImage(image, src, s, dstSize = IntSize(s.width * k, s.height * k), filterQuality = FilterQuality.None)
    }
}

/** Friendly "nothing here yet" with a sprite, a line of help and an optional action. */
@Composable
fun EmptyState(@DrawableRes sprite: Int, title: String, body: String, action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelSprite(sprite, artPixelDp = 3f)
        Text(title, style = MaterialTheme.typography.titleMedium, color = pal.ink, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted, textAlign = TextAlign.Center)
        if (action != null) PixelButton(action, onAction)
    }
}

enum class Protection { Protected, Paused, SafeMode, Demo }

@Composable
fun StatusChip(state: Protection, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val p = pixel()
    val (dot, label) = when (state) {
        Protection.Protected -> pal.leaf to R.string.status_protected
        Protection.Paused -> pal.berry to R.string.status_paused
        Protection.SafeMode -> pal.gold to R.string.status_safe_mode
        Protection.Demo -> pal.berry to R.string.status_demo
    }
    val text = stringResource(label)
    Row(
        modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp)
            .pixelPanel(PanelStyle.Quiet, pal, p)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) {                                   // 5x5 pixel dot
            val c = max(1f, (size.width / 5).roundToInt().toFloat())
            drawRect(dot, Offset(c, 0f), Size(3 * c, 5 * c))
            drawRect(dot, Offset(0f, c), Size(5 * c, 3 * c))
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = pal.ink)
    }
}

/** Small pixel glyphs (8x8) for the drawer and top bar, drawn from text maps so they stay crisp. */
@Composable
fun PixelGlyph(rows: List<String>, color: Color, modifier: Modifier = Modifier, sizeDp: Int = 20) {
    Canvas(modifier.size(sizeDp.dp)) {
        val n = rows.size
        val cell = max(1f, (size.width / n).toInt().toFloat())
        val o = ((size.width - n * cell) / 2).roundToInt().toFloat()
        for ((y, row) in rows.withIndex()) for ((x, ch) in row.withIndex()) {
            if (ch != '.') drawRect(color, Offset(o + x * cell, o + y * cell), Size(cell, cell))
        }
    }
}

object Glyphs {
    val menu = listOf("........", "XXXXXXXX", "........", "........", "XXXXXXXX", "........", "........", "XXXXXXXX")
    val jar = listOf(".XXXXXX.", "..X..X..", ".X....X.", "X..XX..X", "X.XXXX.X", "X..XX..X", "X.X..X.X", ".XXXXXX.")
    val apps = listOf("XXX..XXX", "XXX..XXX", "XXX..XXX", "........", "........", "XXX..XXX", "XXX..XXX", "XXX..XXX")
    val wave = listOf("........", "...X....", "..X.X...", "X.X.X..X", ".X...X.X", "......X.", "........", "........")
    val bars = listOf("......XX", "......XX", "...XX.XX", "...XX.XX", "XX.XX.XX", "XX.XX.XX", "XX.XX.XX", "XXXXXXXX")
    val chat = listOf("XXXXXXXX", "X......X", "X.X.X.XX", "X......X", "XXXXXXXX", ".XX.....", ".X......", "........")
    val check = listOf("........", ".......X", "......XX", "X....XX.", "XX..XX..", ".XXXX...", "..XX....", "........")
    val gear = listOf("...XX...", ".XXXXXX.", ".XX..XX.", "XX....XX", "XX....XX", ".XX..XX.", ".XXXXXX.", "...XX...")
    val help = listOf("..XXXX..", ".XX..XX.", ".....XX.", "....XX..", "...XX...", "...XX...", "........", "...XX...")
    val leaf = listOf("......XX", "....XXXX", "..XXXXXX", ".XXXX.XX", ".XXX.XXX", ".XX.XXX.", ".X.XX...", "X.......")
    val back = listOf("...X....", "..XX....", ".XXXXXXX", "XXXXXXXX", ".XXXXXXX", "..XX....", "...X....", "........")
}

/** A switch whose whole row (label included) toggles it, with an optional InfoButton. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, info: Info? = null) {
    val pal = LocalPal.current
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked, null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = pal.leaf))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = if (enabled) pal.ink else pal.inkMuted)
        if (info != null) InfoButton(info)
    }
}


/** "Just a moment": a little mushroom that hops while data loads (still when motion is reduced). */
@Composable
fun LoadingState(text: String, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val still = LocalReduceMotion.current
    var hop by remember { mutableStateOf(false) }
    LaunchedEffect(still) { while (!still) { delay(350); hop = !hop } }
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelSprite(R.drawable.sprite_mushroom_happy, artPixelDp = 2f, modifier = Modifier.offset(y = if (hop) (-4).dp else 0.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted, textAlign = TextAlign.Center)
    }
}

/** Something went wrong: a surprised mushroom, plain words, and a way to try again. */
@Composable
fun ErrorState(title: String, body: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    EmptyState(R.drawable.sprite_mushroom_wow, title, body, if (onRetry != null) stringResource(R.string.try_again) else null, onRetry ?: {}, modifier)
}

/** A "Details" toggle: technical facts stay folded away until asked for. */
@Composable
fun Details(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(if (open) R.string.details_hide else R.string.details_show),
            Modifier.clickable(role = Role.Button) { open = !open }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = if (pal.night) pal.gold else pal.wood,
        )
        if (open) Column(Modifier.fillMaxWidth().pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** A row of choices drawn as pixel buttons; the chosen one is green. */
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in options) PixelButton(label, { onSelect(value) }, Modifier.weight(1f),
            kind = if (value == selected) ButtonKind.Primary else ButtonKind.Secondary)
    }
}

/** A settings line: label, optional smaller hint, an "i", and the control on the right (or below when given). */
@Composable
fun SettingRow(label: String, info: Info?, hint: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = pal.ink)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
        }
        if (info != null) InfoButton(info)
        if (trailing != null) trailing()
    }
}
