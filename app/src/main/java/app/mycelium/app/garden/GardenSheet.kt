package app.mycelium.app.garden

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.mycelium.app.R
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelBitmap
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class SheetStop { PEEK, HALF, FULL }

/** Snap state for the quick-info sheet: heights in px for each stop, animated between them. */
class SheetState(initial: Float) {
    val height = Animatable(initial)
    var stops = floatArrayOf(initial, initial, initial)

    val stop: SheetStop
        get() = SheetStop.entries[stops.indices.minBy { abs(stops[it] - height.value) }]

    suspend fun go(to: SheetStop) = height.animateTo(stops[to.ordinal])

    /** On release: follow a fast fling, else snap to the nearest stop. */
    suspend fun settle(velocity: Float) {
        val i = stops.indices.minBy { abs(stops[it] - height.value) }
        val target = when {
            velocity < -1200f -> (i + 1).coerceAtMost(2)
            velocity > 1200f -> (i - 1).coerceAtLeast(0)
            else -> i
        }
        height.animateTo(stops[target])
    }
}

/**
 * The quick-info sheet over the garden. PEEK: one friendly line plus mini stats. HALF/FULL: the creature list, or one
 * creature's card. Wood frame, parchment fill, notched pixel corners; the scene above stays interactive.
 */
@Composable
fun GardenSheet(
    state: SheetState, pets: List<Pet>, selected: Pet?, pickingCreature: Boolean,
    onSelect: (Pet?) -> Unit, onShoo: (Pet, SnoopVisit) -> Unit, onChangeCreature: (Pet) -> Unit, onReset: (Pet) -> Unit,
    picker: @Composable () -> Unit, modifier: Modifier = Modifier,
) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { d -> scope.launch { state.height.snapTo((state.height.value - d).coerceIn(state.stops[0], state.stops[2])) } }
    Column(
        modifier
            .fillMaxWidth()
            .height(with(androidx.compose.ui.platform.LocalDensity.current) { state.height.value.toDp() })
            .pixelPanel(PanelStyle.Paper, pal, pixel())
            .clipToBounds()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .draggable(drag, Orientation.Vertical, onDragStopped = { v -> scope.launch { state.settle(v) } })
                .clickable(role = Role.Button) { scope.launch { state.go(if (state.stop == SheetStop.PEEK) SheetStop.HALF else SheetStop.PEEK) } }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(width = 40.dp, height = 4.dp).pixelPanel(PanelStyle.Quiet, pal, pixel()))   // handle
            Spacer(Modifier.height(6.dp))
            Peek(pets)
        }
        // collapsed: only the summary shows; the list appears as soon as the sheet is pulled up
        val open = state.height.value > state.stops[0] + 24f
        when {
            !open -> Unit
            pickingCreature -> Box(Modifier.weight(1f)) { picker() }
            selected != null -> PetCard(selected, onBack = { onSelect(null) }, onShoo = onShoo, onChangeCreature = onChangeCreature,
                onReset = onReset, modifier = Modifier.weight(1f))
            else -> PetList(pets, onSelect = { onSelect(it) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Peek(pets: List<Pet>) {
    val pal = LocalPal.current
    val all = pets.flatMap { p -> p.snoops.map { it.company } }.toSet()
    val shooed = pets.flatMap { p -> p.snoops.filter { it.shooed }.map { it.company } }.toSet()
    Text(
        if (all.isEmpty()) stringResource(R.string.terr_sign_none) else pluralStringResource(R.plurals.terr_sign_some, all.size, all.size),
        style = MaterialTheme.typography.titleSmall, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniStat(pluralStringResource(R.plurals.sheet_pets, pets.size, pets.size), Modifier.weight(1f))
        MiniStat(pluralStringResource(R.plurals.sheet_snoops, all.size, all.size), Modifier.weight(1f))
        MiniStat(pluralStringResource(R.plurals.sheet_shooed, shooed.size, shooed.size), Modifier.weight(1f))
    }
}

@Composable
private fun MiniStat(text: String, modifier: Modifier) {
    val pal = LocalPal.current
    Box(modifier.heightIn(min = 32.dp).pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A creature's current sprite frame as a small portrait. */
@Composable
fun PetPortrait(pet: Pet, box: Dp = 48.dp, sleeping: Boolean = false) {
    val atlas = SpriteAtlas.of(LocalContext.current)
    val s = remember(pet.preset, pet.stage, sleeping, pet.infection) { atlas.creature(creatureSprite(pet, sleeping, atlas), pet.look(atlas), pet.infection, crc(pet.pkg)) }
    Box(Modifier.size(box), contentAlignment = Alignment.BottomCenter) {
        PixelBitmap(s.image, artPixelDp = box.value / maxOf(s.w, s.h), srcSize = IntSize(s.w, s.h))
    }
}

@Composable
private fun PetList(pets: List<Pet>, onSelect: (Pet) -> Unit, modifier: Modifier) {
    val pal = LocalPal.current
    val now = System.currentTimeMillis()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sheet_list_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = pal.ink)
                InfoButton(Info(R.string.info_terrarium_t, R.string.info_terrarium_b))
            }
        }
        items(pets, key = { it.pkg }) { p ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).pixelPanel(PanelStyle.Quiet, pal, pixel()).clickable(role = Role.Button) { onSelect(p) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PetPortrait(p, 48.dp, p.sleeping(now))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.bodyLarge, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.terr_org_stage, p.stage, p.stageCount), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                }
                val mood = p.mood(now)
                Chip(moodName(mood), moodColor(mood))
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    val pal = LocalPal.current
    Row(Modifier.pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawRect(color) }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = pal.ink)
    }
}

@Composable
private fun PetCard(pet: Pet, onBack: () -> Unit, onShoo: (Pet, SnoopVisit) -> Unit, onChangeCreature: (Pet) -> Unit, onReset: (Pet) -> Unit, modifier: Modifier) {
    val pal = LocalPal.current
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PetPortrait(pet, 64.dp, pet.sleeping(System.currentTimeMillis()))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pet.label, style = MaterialTheme.typography.titleMedium, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pluralStringResource(R.plurals.terr_org_contacts, pet.contactsToday, pet.contactsToday, pet.label),
                        style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                }
            }
        }
        item {
            val mood = pet.mood(System.currentTimeMillis())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(moodName(mood), moodColor(mood))
                Spacer(Modifier.width(8.dp))
                Text(moodReason(mood), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                InfoButton(Info(R.string.info_mood_t, R.string.info_mood_b))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.terr_org_stage, pet.stage, pet.stageCount) + " · " + stageName(pet.stage), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, color = pal.ink)
                InfoButton(Info(R.string.info_growth_t, R.string.info_growth_b))
            }
            GrowthBar(pet)
            Text(
                pet.next?.let { stringResource(R.string.sheet_next, it.toInt(), pet.growth.toInt()) } ?: stringResource(R.string.sheet_grown),
                style = MaterialTheme.typography.bodySmall, color = pal.inkMuted,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sheet_snoops_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = pal.ink)
                InfoButton(Info(R.string.info_shooed_t, R.string.info_shooed_b))
            }
            if (pet.snoops.isEmpty()) Text(stringResource(R.string.terr_org_no_snoops), style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
        }
        items(pet.snoops, key = { it.company }) { v -> SnoopRow(pet, v, onShoo) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(stringResource(R.string.sheet_all), onBack, Modifier.weight(1f), kind = ButtonKind.Secondary)
                PixelButton(stringResource(R.string.terr_change), { onChangeCreature(pet) }, Modifier.weight(1f), kind = ButtonKind.Secondary)
            }
            Spacer(Modifier.height(8.dp))
            PixelButton(stringResource(R.string.reset_record), { onReset(pet) }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
    }
}

@Composable
private fun SnoopRow(pet: Pet, v: SnoopVisit, onShoo: (Pet, SnoopVisit) -> Unit) {
    val pal = LocalPal.current
    val atlas = SpriteAtlas.of(LocalContext.current)
    val sprite = remember(v.company) { atlas["snoop_${1 + (crc(v.company) % 3).toInt()}"] }
    Row(Modifier.fillMaxWidth().pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelBitmap(sprite.image, artPixelDp = 2f, srcSize = IntSize(sprite.w, sprite.h))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.terr_snoop_title, v.company), style = MaterialTheme.typography.bodyLarge, color = pal.ink)
            Text(
                if (v.shooed) stringResource(R.string.terr_snoop_shooed, pet.label) else pluralStringResource(R.plurals.terr_snoop_visits, v.hits, v.hits, pet.label),
                style = MaterialTheme.typography.bodySmall, color = pal.inkMuted,
            )
        }
        if (!v.shooed) PixelButton(stringResource(R.string.shoo), { onShoo(pet, v) }, kind = ButtonKind.Danger)
    }
}

@Composable
fun moodName(m: Mood): String = stringResource(when (m) {
    Mood.HAPPY -> R.string.mood_happy; Mood.CONTENT -> R.string.mood_content; Mood.GRUMPY -> R.string.mood_grumpy
    Mood.SICK -> R.string.mood_sick; Mood.SLEEPY -> R.string.mood_sleepy
})

@Composable
private fun moodReason(m: Mood): String = stringResource(when (m) {
    Mood.HAPPY -> R.string.mood_happy_b; Mood.CONTENT -> R.string.mood_content_b; Mood.GRUMPY -> R.string.mood_grumpy_b
    Mood.SICK -> R.string.mood_sick_b; Mood.SLEEPY -> R.string.mood_sleepy_b
})

@Composable
fun moodColor(m: Mood): Color {
    val pal = LocalPal.current
    return when (m) { Mood.HAPPY -> pal.leaf; Mood.CONTENT -> pal.gold; Mood.GRUMPY -> pal.berry; Mood.SICK -> pal.berry; Mood.SLEEPY -> pal.sky }
}

/** A friendly name for each of the 8 stages. */
@Composable
fun stageName(stage: Int): String = androidx.compose.ui.res.stringArrayResource(R.array.stage_names).let { it[(stage - 1).coerceIn(0, it.size - 1)] }

/** A Snoop in the terrarium art (which one depends on the company). */
@Composable
fun SnoopIcon(company: String, artPixelDp: Float = 2f) {
    val atlas = SpriteAtlas.of(LocalContext.current)
    val s = remember(company) { atlas["snoop_${1 + (crc(company) % 3).toInt()}"] }
    PixelBitmap(s.image, artPixelDp = artPixelDp, srcSize = IntSize(s.w, s.h))
}

/** Growth toward the next stage as a chunky pixel bar. */
@Composable
private fun GrowthBar(pet: Pet) {
    val pal = LocalPal.current
    val p = pixel()
    val f = pet.next?.let { (pet.growth / it).toFloat().coerceIn(0f, 1f) } ?: 1f
    Canvas(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(16.dp)) {
        drawRect(pal.woodDark, Offset.Zero, size)
        drawRect(pal.paperEdge, Offset(p, p), Size(size.width - 2 * p, size.height - 2 * p))
        val w = ((size.width - 2 * p) * f / p).toInt() * p
        if (w > 0) {
            drawRect(pal.leaf, Offset(p, p), Size(w, size.height - 2 * p))
            drawRect(Color.White.copy(alpha = 0.3f), Offset(p, p), Size(w, p))
        }
    }
}
