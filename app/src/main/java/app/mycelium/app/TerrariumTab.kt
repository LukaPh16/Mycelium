package app.mycelium.app

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import app.mycelium.engine.bridge.WebBridge

private const val HOST = "appassets.androidplatform.net"
private const val URL = "https://$HOST/assets/terrarium/index.html"

/** Hosts app/src/main/assets/terrarium/ (the replaceable web UI). The Compose app never draws organisms itself. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerrariumTab() {
    val context = LocalContext.current
    val web = remember {
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
            loadUrl(URL)
        }
    }
    LaunchedEffect(web) { WebBridge.attach(web, this) }   // terrarium_update -> window.Feed._push(...)
    DisposableEffect(web) { onDispose { web.destroy() } }
    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
}

