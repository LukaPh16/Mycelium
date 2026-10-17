package app.mycelium.app

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import app.mycelium.app.ui.EmptyState
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelPanel
import app.mycelium.app.ui.PixelSprite
import app.mycelium.app.ui.Protection
import app.mycelium.engine.bridge.WebBridge
import kotlinx.coroutines.delay

/** Home: the terrarium web module, with a friendly banner when protection is paused or offline. */
@Composable
fun HomeScreen(protection: Protection, start: () -> Unit) {
    var watched by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            watched = Api.io("watched").optJSONArray("apps")?.length() ?: 0
            delay(3000)
        }
    }
    val go = LocalGo.current
    Column(Modifier.fillMaxSize()) {
        when {
            watched == 0 && protection != Protection.Demo -> {
                EmptyState(
                    R.drawable.sprite_mushroom_sleepy, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body),
                    action = stringResource(R.string.home_choose), onAction = { go(Routes.APPS) }, modifier = Modifier.padding(top = 48.dp),
                )
                return@Column
            }
            protection == Protection.Paused -> Banner(R.drawable.sprite_mushroom_sleepy, stringResource(R.string.home_paused_title),
                stringResource(R.string.home_paused_body), stringResource(R.string.prot_start), start)
            protection == Protection.SafeMode -> Banner(R.drawable.sprite_mushroom_wow, stringResource(R.string.status_safe_mode),
                stringResource(R.string.home_safe_body), null, {})
            else -> Unit
        }
        if (watched != null) TerrariumWeb(Modifier.fillMaxSize())
    }
}

@Composable
private fun Banner(sprite: Int, title: String, body: String, action: String?, onAction: () -> Unit) {
    val pal = LocalPal.current
    PixelPanel(Modifier.fillMaxWidth().padding(12.dp), style = PanelStyle.Paper) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSprite(sprite, artPixelDp = 1.5f)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = pal.ink)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
            }
        }
        if (action != null) PixelButton(action, onAction, Modifier.fillMaxWidth())
    }
}

private const val HOST = "appassets.androidplatform.net"
private const val URL = "https://$HOST/assets/terrarium/index.html"

/** Hosts app/src/main/assets/terrarium/ (the replaceable web UI). The Compose app never draws organisms itself. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerrariumWeb(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val day = !LocalPal.current.night
    val web = remember(day) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(WebBridge(), WebBridge.NAME)
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
