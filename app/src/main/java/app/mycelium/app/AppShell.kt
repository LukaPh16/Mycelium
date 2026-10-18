package app.mycelium.app

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.Glyphs
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.MyceliumTheme
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelDialog
import app.mycelium.app.ui.PixelGlyph
import app.mycelium.app.ui.PixelSprite
import app.mycelium.app.ui.Protection
import app.mycelium.app.ui.StatusChip
import app.mycelium.app.ui.UiPrefs
import app.mycelium.app.ui.Sounds
import app.mycelium.app.ui.LocalSounds
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import app.mycelium.engine.MyceliumEngine
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var onConsent: ((Boolean) -> Unit)? = null
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onConsent?.invoke(it.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // Light status-bar icons on the wood/night top bar; a see-through navigation bar over the parchment.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        MyceliumEngine.init(this)
        val prefs = UiPrefs(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        sounds = Sounds(this)
        setContent {
            LaunchedEffect(prefs.muted, prefs.masterVolume, prefs.effectsOn, prefs.effectsVolume, prefs.ambienceOn, prefs.ambienceVolume) {
                sounds.setVolumes(prefs.muted, prefs.masterVolume, prefs.effectsOn, prefs.effectsVolume, prefs.ambienceOn, prefs.ambienceVolume)
            }
            CompositionLocalProvider(LocalSounds provides sounds) {
                MyceliumTheme(prefs.theme, prefs.textSize, prefs.reduceMotion) { AppShell(prefs, ::startProtection, openRoute) }
            }
        }
        openFromIntent(intent)
    }

    private lateinit var sounds: Sounds

    override fun onPause() { super.onPause(); if (::sounds.isInitialized) sounds.onPause() }
    override fun onResume() { super.onResume(); if (::sounds.isInitialized) sounds.onResume() }
    override fun onDestroy() { if (::sounds.isInitialized) sounds.release(); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    /** Debug builds only: `adb shell am start -n app.mycelium/app.mycelium.app.MainActivity --es route activity` opens a screen. */
    private val openRoute = mutableStateOf<String?>(null)

    private fun openFromIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent?.getStringExtra("route")?.let { openRoute.value = it }
        intent?.getStringExtra("evolve")?.let { DebugHooks.evolve.value = it }   // replay that creature's last evolution
    }

    /** Starts protection; shows Android's VPN dialog when needed. Reports "ok", "consent_denied" or an engine error code. */
    private fun startProtection(report: (String) -> Unit) {
        fun start() = Api.call("vpn", obj("action" to "start"))
        val r = start()
        val intent = if (r.optBoolean("needs_consent")) VpnService.prepare(this) else null
        if (intent == null) { report(r.optString("error").ifEmpty { "ok" }); return }
        onConsent = { ok -> report(if (ok) start().optString("error").ifEmpty { "ok" } else "consent_denied") }
        consent.launch(intent)
    }
}

/** Debug builds only: hooks for checking moments on a phone without waiting for real growth. */
object DebugHooks {
    val evolve = mutableStateOf<String?>(null)
}

/** Shows a short pixel toast from anywhere. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Opens a screen from anywhere. */
val LocalGo = staticCompositionLocalOf<(String) -> Unit> { {} }

object Routes {
    const val HOME = "terrarium"
    const val APPS = "apps"
    const val ACTIVITY = "activity"
    const val INSIGHTS = "insights"
    const val GARDENER = "gardener"
    const val SETUP = "setup"
    const val SETTINGS = "settings"
    const val ADVANCED = "advanced"
    const val HELP = "help"
    const val ABOUT = "about"
    const val LIMITS = "limits"
    const val LICENSES = "licenses"
}

private class Screen(
    val route: String, @param:StringRes val title: Int, val info: Info, val glyph: List<String>, val inDrawer: Boolean = true,
    @param:StringRes val topTitle: Int = title,
)

private val SCREENS = listOf(
    Screen(Routes.HOME, R.string.nav_terrarium, Info(R.string.info_terrarium_t, R.string.info_terrarium_b), Glyphs.jar),
    Screen(Routes.APPS, R.string.nav_apps, Info(R.string.info_apps_t, R.string.info_apps_b), Glyphs.apps),
    Screen(Routes.ACTIVITY, R.string.nav_activity, Info(R.string.info_activity_t, R.string.info_activity_b), Glyphs.wave),
    Screen(Routes.INSIGHTS, R.string.nav_insights, Info(R.string.info_insights_t, R.string.info_insights_b), Glyphs.bars),
    Screen(Routes.GARDENER, R.string.nav_gardener, Info(R.string.info_gardener_t, R.string.info_gardener_b), Glyphs.chat, topTitle = R.string.title_gardener),
    Screen(Routes.SETUP, R.string.nav_setup, Info(R.string.info_setup_t, R.string.info_setup_b), Glyphs.check),
    Screen(Routes.SETTINGS, R.string.nav_settings, Info(R.string.info_settings_t, R.string.info_settings_b), Glyphs.gear),
    Screen(Routes.HELP, R.string.nav_help, Info(R.string.info_help_t, R.string.info_help_b), Glyphs.help),
    Screen(Routes.ABOUT, R.string.nav_about, Info(R.string.info_about_t, R.string.info_about_b), Glyphs.leaf),
    Screen(Routes.ADVANCED, R.string.nav_advanced, Info(R.string.info_advanced_t, R.string.info_advanced_b), Glyphs.gear, inDrawer = false),
    Screen(Routes.LIMITS, R.string.nav_limits, Info(R.string.info_limits_t, R.string.info_limits_b), Glyphs.help, inDrawer = false,
        topTitle = R.string.title_limits),
    Screen(Routes.LICENSES, R.string.nav_licenses, Info(R.string.info_licenses_t, R.string.info_licenses_b), Glyphs.leaf, inDrawer = false),
)

private fun protectionOf(state: JSONObject): Protection = when {
    state.optString("mode") == "simulate" -> Protection.Demo
    state.optString("vpn") != "running" -> Protection.Paused
    state.optString("mode") == "online" -> Protection.Protected
    else -> Protection.SafeMode
}

@Composable
private fun AppShell(prefs: UiPrefs, startProtection: ((String) -> Unit) -> Unit, openRoute: MutableState<String?>) {
    val pal = LocalPal.current
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val toast: (String) -> Unit = { msg -> scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) } }
    val stateJson by MyceliumEngine.state.collectAsState()
    val state = remember(stateJson) { JSONObject(stateJson) }
    val protection = protectionOf(state)
    var showProtection by remember { mutableStateOf(false) }
    val entry by nav.currentBackStackEntryAsState()
    val screen = SCREENS.firstOrNull { it.route == entry?.destination?.route } ?: SCREENS.first()
    val feed = rememberEventFeed()
    val ctx = LocalContext.current
    val go: (String) -> Unit = { route -> navigateTo(nav, route); scope.launch { drawer.close() } }
    val messages = mapOf(
        "ok" to ctx.getString(R.string.prot_started),
        "consent_denied" to ctx.getString(R.string.prot_consent_denied),
        "no_apps_selected" to ctx.getString(R.string.prot_no_apps),
    )
    val start: () -> Unit = { startProtection { code -> toast(messages[code] ?: ctx.getString(R.string.error_generic, code)) } }

    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    LaunchedEffect(openRoute.value) { openRoute.value?.let { navigateTo(nav, it); openRoute.value = null } }
    CompositionLocalProvider(LocalToast provides toast, LocalGo provides go) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = { DrawerContent(screen.route, prefs.advanced, go) },
        ) {
            Scaffold(
                containerColor = pal.bg,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { pad ->
                val overScene = screen.route == Routes.HOME
                val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR_HEIGHT
                Box(Modifier.fillMaxSize().padding(pad)) {
                // Home draws its scene under a see-through top bar; other screens start below it.
                Column(Modifier.fillMaxSize().padding(top = if (overScene) 0.dp else top)) {
                    if (protection == Protection.Demo) DemoBanner()
                    NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                        composable(Routes.HOME) { HomeScreen(prefs, protection, start, top) }
                        composable(Routes.APPS) { MyAppsScreen(prefs, protection) }
                        composable(Routes.ACTIVITY) { ActivityScreen(feed, prefs.advanced) }
                        composable(Routes.INSIGHTS) { InsightsScreen() }
                        composable(Routes.GARDENER) { GardenerScreen() }
                        composable(Routes.SETUP) { SetupScreen(protection, start) }
                        composable(Routes.SETTINGS) { SettingsScreen(prefs, protection, start) }
                        composable(Routes.ADVANCED) { AdvancedScreen() }
                        composable(Routes.HELP) { HelpScreen() }
                        composable(Routes.ABOUT) { AboutScreen() }
                        composable(Routes.LIMITS) { LimitsScreen() }
                        composable(Routes.LICENSES) { LicensesScreen() }
                    }
                }
                TopBar(
                    screen = screen,
                    protection = protection,
                    translucent = overScene,
                    onMenu = { scope.launch { drawer.open() } },
                    onBack = if (screen.inDrawer) null else ({ nav.popBackStack() }),
                    onChip = { showProtection = true },
                )
                // Toasts drop in under the top bar, so they never cover a screen's own bottom controls.
                SnackbarHost(snack, Modifier.align(Alignment.TopCenter).padding(top = top)) { data ->
                    Box(Modifier.padding(12.dp).pixelPanel(PanelStyle.Wood, pal, pixel()).padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Text(data.visuals.message, color = if (pal.night) pal.ink else pal.onAccent, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                }
            }
        }
        if (showProtection) ProtectionDialog(state, protection, onDismiss = { showProtection = false }, start = start, toast = toast)
    }
}

private val TOP_BAR_HEIGHT = 60.dp

private fun navigateTo(nav: NavHostController, route: String) {
    if (SCREENS.firstOrNull { it.route == route }?.inDrawer == false) {   // sub-page: Back returns to the page that opened it
        nav.navigate(route) { launchSingleTop = true }
        return
    }
    nav.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun TopBar(screen: Screen, protection: Protection, translucent: Boolean, onMenu: () -> Unit, onBack: (() -> Unit)?, onChip: () -> Unit) {
    val pal = LocalPal.current
    val ink = pal.onAccent
    Row(
        Modifier.fillMaxWidth().background(if (translucent) pal.topBar.copy(alpha = 0.55f) else pal.topBar)
            .statusBarsPadding().height(TOP_BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = stringResource(if (onBack == null) R.string.nav_open else R.string.nav_back)
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClick = onBack ?: onMenu).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            PixelGlyph(if (onBack == null) Glyphs.menu else Glyphs.back, ink, sizeDp = 24)
        }
        val title = stringResource(screen.topTitle)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, Modifier.weight(1f, fill = false), color = ink,
                style = if (title.length > 11) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            InfoButton(screen.info, tint = ink)
        }
        StatusChip(protection, onChip, Modifier.padding(end = 6.dp))
    }
}

@Composable
private fun DrawerContent(current: String, advanced: Boolean, go: (String) -> Unit) {
    val pal = LocalPal.current
    ModalDrawerSheet(drawerContainerColor = pal.bg, modifier = Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth().pixelPanel(PanelStyle.Wood, pal, pixel()).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelSprite(R.drawable.sprite_mushroom_happy, artPixelDp = 2f)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = if (pal.night) pal.ink else pal.onAccent)
                    Text(stringResource(R.string.drawer_tagline), style = MaterialTheme.typography.bodySmall, color = if (pal.night) pal.inkMuted else pal.paper)
                }
            }
            Spacer(Modifier.height(8.dp))
            for (s in SCREENS.filter { it.inDrawer || (advanced && it.route == Routes.ADVANCED) }) {
                val selected = s.route == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .then(if (selected) Modifier.pixelPanel(PanelStyle.Paper, pal, pixel()) else Modifier)
                        .clickable(role = Role.Button) { go(s.route) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelGlyph(s.glyph, if (selected) pal.leaf else pal.inkMuted, sizeDp = 24)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(s.title), style = MaterialTheme.typography.titleSmall, color = pal.ink)
                }
            }
        }
    }
}

@Composable
private fun DemoBanner() {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().background(pal.berry).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sim_banner), Modifier.weight(1f), color = pal.onAccent.takeIf { !pal.night } ?: pal.ink,
            style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.sim_stop),
            Modifier.clickable(role = Role.Button) { Api.call("simulate", obj("stop" to true)) }.padding(8.dp),
            color = pal.onAccent.takeIf { !pal.night } ?: pal.ink, style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ProtectionDialog(state: JSONObject, protection: Protection, onDismiss: () -> Unit, start: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    var watched by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { watched = Api.io("watched").optJSONArray("apps")?.length() ?: 0 }
    val apps = pluralStringResource(R.plurals.watching_apps, watched, watched)
    val go = LocalGo.current
    PixelDialog(
        title = stringResource(R.string.prot_title),
        onDismiss = onDismiss,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(stringResource(R.string.close), onDismiss, kind = ButtonKind.Secondary)
                when {
                    watched == 0 && protection == Protection.Paused ->
                        PixelButton(stringResource(R.string.prot_choose_apps), { onDismiss(); go(Routes.APPS) })
                    protection == Protection.Paused -> PixelButton(stringResource(R.string.prot_start), { onDismiss(); start() })
                    protection == Protection.Demo -> PixelButton(stringResource(R.string.sim_stop), { Api.call("simulate", obj("stop" to true)); onDismiss() })
                    else -> PixelButton(stringResource(R.string.prot_pause), {
                        Api.call("vpn", obj("action" to "stop")); toast(ctx.getString(R.string.prot_paused_toast)); onDismiss()
                    }, kind = ButtonKind.Danger)
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSprite(if (protection == Protection.Paused) R.drawable.sprite_mushroom_sleepy else R.drawable.sprite_mushroom_happy, artPixelDp = 2f)
            Spacer(Modifier.width(12.dp))
            StatusChip(protection, {})
            InfoButton(Info(R.string.info_status_t, R.string.info_status_b))
        }
        val body = when {
            protection == Protection.Demo -> stringResource(R.string.prot_demo_body)
            protection == Protection.Protected -> stringResource(R.string.prot_protected_body, apps)
            protection == Protection.SafeMode -> stringResource(R.string.prot_safe_body)
            state.optString("vpn") == "dead" -> stringResource(R.string.prot_dead_body)
            watched == 0 -> stringResource(R.string.prot_no_apps)
            else -> stringResource(R.string.prot_paused_body)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalPal.current.ink)
    }
}

/** Live events, kept while the app runs so Activity keeps its history across screens. */
@Composable
private fun rememberEventFeed(): List<JSONObject> {
    val feed = remember { mutableStateListOf<JSONObject>() }
    LaunchedEffect(Unit) {
        feed.addAll(Api.io("events", obj("limit" to 200)).optJSONArray("events").objects())
        MyceliumEngine.events.collect {
            val o = JSONObject(it)
            when (o.optString("type")) {
                "event" -> { feed.add(0, o.getJSONObject("row")); while (feed.size > 400) feed.removeAt(feed.lastIndex) }
                "wiped" -> o.optString("package").let { p -> if (p.isEmpty()) feed.clear() else feed.removeAll { it.optString("package") == p } }
            }
        }
    }
    return feed
}
