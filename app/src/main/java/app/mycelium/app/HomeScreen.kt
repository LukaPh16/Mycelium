package app.mycelium.app

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
            else -> GardenView(still = prefs.reduceMotion || prefs.batterySaver, modifier = Modifier.fillMaxSize())
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

