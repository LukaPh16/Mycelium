package app.mycelium.app

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
    LaunchedEffect(Unit) {
        while (true) {
            watched = Api.io("watched").optJSONArray("apps")?.length() ?: 0
            delay(3000)
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
            else -> TerrariumWeb(prefs, Modifier.fillMaxSize().padding(top = topInset))
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

private const val HOST = "appassets.androidplatform.net"
private const val URL = "https://$HOST/assets/terrarium/index.html"

/** Engine methods the terrarium page may call: its own data, plus today's Snoops and the "Shoo away" rule. */
private val TERRARIUM_METHODS = WebBridge.TERRARIUM + setOf("stats", "rules", "block")

/** Words the terrarium page shows, from strings.xml so they translate. Sentences use %1$s style arguments. */
private val TERR_STRINGS = mapOf(
    "terr_sign_none" to R.string.terr_sign_none, "terr_empty" to R.string.terr_empty, "terr_error" to R.string.terr_error,
    "terr_colors" to R.string.terr_colors, "terr_unseen" to R.string.terr_unseen, "terr_more" to R.string.terr_more,
    "terr_org_no_snoops" to R.string.terr_org_no_snoops, "terr_org_stage" to R.string.terr_org_stage, "terr_org_grow" to R.string.terr_org_grow,
    "terr_change" to R.string.terr_change, "terr_close" to R.string.close, "terr_back" to R.string.nav_back,
    "terr_pick_title" to R.string.terr_pick_title, "terr_snoop_title" to R.string.terr_snoop_title, "terr_snoop_cd" to R.string.terr_snoop_cd,
    "terr_kind_ads" to R.string.terr_kind_ads, "terr_kind_analytics" to R.string.terr_kind_analytics,
    "terr_kind_fingerprinting" to R.string.terr_kind_fingerprinting, "terr_kind_social" to R.string.terr_kind_social,
    "terr_kind_unknown" to R.string.terr_kind_unknown, "terr_snoop_shooed" to R.string.terr_snoop_shooed, "terr_shoo" to R.string.shoo,
    "terr_shoo_q" to R.string.terr_shoo_q, "terr_shoo_body" to R.string.terr_shoo_body, "terr_not_now" to R.string.not_now,
    "terr_shoo_done" to R.string.terr_shoo_done, "terr_shoo_failed" to R.string.terr_shoo_failed,
)
private val TERR_PLURALS = mapOf(
    "terr_sign_some" to R.plurals.terr_sign_some, "terr_sign_shooed" to R.plurals.terr_sign_shooed,
    "terr_org_contacts" to R.plurals.terr_org_contacts, "terr_org_snoops" to R.plurals.terr_org_snoops,
    "terr_org_shooed" to R.plurals.terr_org_shooed, "terr_snoop_visits" to R.plurals.terr_snoop_visits,
)

/** window.MyceliumHost: words and motion settings for the page. Holds no engine access. */
private class TerrariumHost(private val ctx: Context, private val settings: String) {
    @JavascriptInterface
    fun strings(): String = JSONObject(TERR_STRINGS.mapValues { ctx.getString(it.value) }).toString()

    @JavascriptInterface
    fun plural(key: String, n: Int, argsJson: String): String {
        val id = TERR_PLURALS[key] ?: return key
        val a = runCatching { JSONArray(argsJson) }.getOrDefault(JSONArray())
        return ctx.resources.getQuantityString(id, n, *Array(a.length()) { a.get(it) })
    }

    @JavascriptInterface
    fun settings(): String = settings
}

/** Hosts app/src/main/assets/terrarium/ (the replaceable web UI). The Compose app never draws organisms itself. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerrariumWeb(prefs: UiPrefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val day = !LocalPal.current.night
    val hostSettings = obj("reduce_motion" to prefs.reduceMotion, "battery_saver" to prefs.batterySaver).toString()
    val web = remember(day, hostSettings, prefs.textSize) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.textZoom = (prefs.textSize.scale * 100).toInt()
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            addJavascriptInterface(WebBridge(TERRARIUM_METHODS), WebBridge.NAME)
            addJavascriptInterface(TerrariumHost(context.applicationContext, hostSettings), "MyceliumHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                // The terrarium never navigates away from the bundled assets.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != HOST
            }
            loadUrl(URL + if (day) "?theme=day" else "?theme=night")
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }
    LaunchedEffect(web) { WebBridge.attach(web, this) }   // terrarium_update -> window.Feed._push(...)
    DisposableEffect(web) { onDispose { web.destroy() } }
    AndroidView(factory = { web }, modifier = modifier)
}
