package app.mycelium.app

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import app.mycelium.app.garden.EvolutionMoment
import app.mycelium.app.garden.GardenSheet
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.DisposableEffect
import app.mycelium.app.garden.SheetState
import app.mycelium.app.garden.SheetStop
import app.mycelium.app.garden.SnoopVisit
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.mycelium.app.garden.Camera
import app.mycelium.app.garden.GardenFx
import app.mycelium.app.garden.MAX_PETS
import app.mycelium.app.garden.Pet
import app.mycelium.app.garden.loadPets
import app.mycelium.app.ui.Glyphs
import app.mycelium.app.ui.PixelGlyph
import app.mycelium.engine.MyceliumEngine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import app.mycelium.app.garden.GardenView
import app.mycelium.app.ui.EmptyState
import app.mycelium.app.ui.LoadingState
import app.mycelium.app.ui.UiPrefs
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelPanel
import app.mycelium.app.ui.PixelSprite
import app.mycelium.app.ui.Protection
import app.mycelium.engine.bridge.WebBridge
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Home: the terrarium web module, with a friendly banner when protection is paused or offline. */
@Composable
fun HomeScreen(prefs: UiPrefs, protection: Protection, start: () -> Unit, topInset: Dp) {
    var watched by remember { mutableStateOf<Int?>(null) }
    var pets by remember { mutableStateOf(emptyList<Pet>()) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    val fx = remember { GardenFx() }
    val camera = remember { Camera() }
    var focus by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var shoo by remember { mutableStateOf<Pair<Pet, SnoopVisit>?>(null) }
    var resetting by remember { mutableStateOf<Pet?>(null) }
    val acked = remember { mutableStateMapOf<String, Int>() }   // stages already celebrated this session
    val sounds = app.mycelium.app.ui.LocalSounds.current
    val seenSnoops = remember { arrayOfNulls<Set<String>>(1) }
    LaunchedEffect(pets) {   // a Snoop that was not there before: an ominous little buzz
        val now = pets.flatMap { p -> p.visiting.map { p.pkg + "|" + it.company } }.toSet()
        val before = seenSnoops[0]
        if (before != null && (now - before).isNotEmpty()) sounds?.play("snoop_arrive", volume = 0.8f)
        seenSnoops[0] = now
    }
    DisposableEffect(Unit) {   // jar ambience only while the Terrarium is on screen; the window sky follows the clock
        sounds?.ambience(if (app.mycelium.app.garden.skyPhaseNow() == app.mycelium.app.garden.SkyPhase.NIGHT) "amb_night" else "amb_day")
        onDispose { sounds?.ambience(null) }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sheet = remember { SheetState(with(density) { 88.dp.toPx() }) }
    LaunchedEffect(reload) {
        while (true) {
            watched = Api.io("watched").optJSONArray("apps")?.length() ?: 0
            pets = loadPets()
            delay(15_000)
        }
    }
    LaunchedEffect(Unit) {   // live updates: refresh shortly after the engine reports activity
        MyceliumEngine.events.collect { ev ->
            if (ev.contains("\"terrarium_update\"") || ev.contains("\"block\"")) { delay(800); reload++ }
        }
    }
    val go = LocalGo.current
    val pal = LocalPal.current
    Box(Modifier.fillMaxSize().background(pal.bg)) {
        when {
            watched == null -> LoadingState(stringResource(R.string.home_loading), Modifier.padding(top = topInset + 48.dp))
            watched == 0 && protection != Protection.Demo -> EmptyState(
                R.drawable.sprite_mushroom_sleepy, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body),
                action = stringResource(R.string.home_choose), onAction = { go(Routes.APPS) }, modifier = Modifier.padding(top = topInset + 48.dp),
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val full = with(density) { (maxHeight - topInset - 8.dp).toPx() }
                val peek = with(density) { (88.dp + navBar).toPx() }
                LaunchedEffect(full, peek) {
                    sheet.stops = floatArrayOf(peek, full * 0.5f, full)
                    if (sheet.height.value < peek || sheet.stop == SheetStop.PEEK) sheet.height.snapTo(peek)
                }
                fun open(pet: Pet) {
                    selected = pet.pkg; focus = pet.pkg; picking = false
                    if (sheet.stop == SheetStop.PEEK) scope.launch { sheet.go(SheetStop.HALF) }
                }
                val sheetPx = sheet.height.value.toInt()
                GardenView(
                    still = prefs.reduceMotion || prefs.batterySaver, pets = pets, selected = selected, fx = fx, camera = camera,
                    focus = focus, bottomInsetPx = sheetPx, fitBottomPx = peek.toInt(),
                    // leave room for the status note when protection is paused or offline, so the lid stays in view
                    topInsetPx = with(density) { (topInset + if (protection == Protection.Paused || protection == Protection.SafeMode) 72.dp else 0.dp).roundToPx() },
                    onTapPet = { open(it) }, onTapSnoop = { p, _ -> open(p) },
                    onTapEmpty = { selected = null; focus = null; picking = false },
                    modifier = Modifier.fillMaxSize(),
                )
                val current = pets.firstOrNull { it.pkg == selected }
                GardenSheet(
                    sheet, pets, current, picking && current != null,
                    onSelect = { p -> if (p == null) { selected = null; focus = null } else open(p) },
                    onShoo = { p, v -> shoo = p to v },
                    onChangeCreature = { picking = true; scope.launch { sheet.go(SheetStop.FULL) } },
                    onReset = { resetting = it },
                    picker = { current?.let { PresetPicker(it.pkg, it.label, onDone = { picking = false; reload++ }) } },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                BackHandler(selected != null || sheet.stop != SheetStop.PEEK) {
                    when {
                        picking -> picking = false
                        selected != null -> { selected = null; focus = null }
                        else -> scope.launch { sheet.go(SheetStop.PEEK) }
                    }
                }
                Column(Modifier.align(Alignment.BottomEnd).padding(bottom = with(density) { sheet.height.value.toDp() } + 8.dp, end = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pets.size > MAX_PETS) Text(
                        stringResource(R.string.garden_more, pets.size - MAX_PETS),
                        Modifier.pixelPanel(PanelStyle.Paper, pal, pixel()).padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = pal.ink,
                    )
                    val cd = stringResource(R.string.garden_reset)
                    Box(
                        Modifier.size(48.dp).pixelPanel(PanelStyle.Paper, pal, pixel()).clickable(role = Role.Button) { camera.reset() }
                            .semantics { contentDescription = cd },
                        contentAlignment = Alignment.Center,
                    ) { PixelGlyph(Glyphs.target, pal.ink, sizeDp = 24) }
                }
            }
        }
        // evolution moments: one stage at a time, saved with terrarium_ack so a moment never replays
        LaunchedEffect(pets) {
            for (p in pets) if (p.stage < (acked[p.pkg] ?: p.stageShown)) {   // fewer stages now (reset or new thresholds): quietly follow
                Api.io("terrarium_ack", obj("app_id" to p.pkg, "stage" to p.stage)); acked[p.pkg] = p.stage
            }
        }
        DebugHooks.evolve.value?.let { pkg -> pets.firstOrNull { it.pkg == pkg }?.let { acked[pkg] = maxOf(1, it.stage - 1); DebugHooks.evolve.value = null } }
        val evolving = pets.firstOrNull { it.stage > (acked[it.pkg] ?: it.stageShown) }
        evolving?.let { p ->
            val from = acked[p.pkg] ?: p.stageShown
            EvolutionMoment(p, from, from + 1) {
                acked[p.pkg] = from + 1
                scope.launch { Api.io("terrarium_ack", obj("app_id" to p.pkg, "stage" to from + 1)) }
            }
        }
        resetting?.let { pet -> ResetRecordDialog(pet.pkg, pet.label) { done -> resetting = null; if (done) reload++ } }
        shoo?.let { (pet, v) ->
            ShooDialog(pet.pkg, pet.label, v.company, onShooed = {
                fx.shooed(pet, v.company)
                reload++
                scope.launch {   // whoosh, poof, healing sparkle, a little heart: timed with the animation
                    sounds?.play("shoo"); delay(140)
                    sounds?.play("poof"); delay(260)
                    sounds?.play("heal"); delay(320)
                    sounds?.play("heart", volume = 0.8f)
                }
            }) { shoo = null }
        }
        if (watched != 0) when (protection) {
            Protection.Paused -> StatusPill(R.drawable.sprite_mushroom_sleepy, stringResource(R.string.home_paused_short), stringResource(R.string.prot_start_short), start, topInset)
            Protection.SafeMode -> StatusPill(R.drawable.sprite_mushroom_wow, stringResource(R.string.home_safe_short), null, {}, topInset)
            else -> Unit
        }
    }
}

/** A slim note floating under the top bar: what is going on, and one button. */
@Composable
private fun StatusPill(sprite: Int, text: String, action: String?, onAction: () -> Unit, topInset: Dp) {
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().padding(top = topInset + 8.dp, start = 16.dp, end = 16.dp)
            .pixelPanel(PanelStyle.Paper, pal, pixel()).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelSprite(sprite, artPixelDp = 1f)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        if (action != null) PixelButton(action, onAction)
    }
}

