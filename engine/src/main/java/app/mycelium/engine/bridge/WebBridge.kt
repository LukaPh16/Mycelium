package app.mycelium.engine.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.mycelium.engine.MyceliumEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lets a WebView UI use the same API as native code:
 *  - calls: `webView.addJavascriptInterface(WebBridge(), WebBridge.NAME)`, then in JS
 *    `window.MyceliumBridge.call(method, argsJson)` returns the same JSON string as `MyceliumEngine.call`.
 *  - events: `WebBridge.attach(webView, scope)` pushes engine events to `window.Feed._push(event)`.
 * Only [allowed] methods are reachable, so a replaced UI folder cannot run SQL, wipe data or touch the AI key.
 */
class WebBridge(private val allowed: Set<String> = TERRARIUM) {
    @JavascriptInterface
    fun call(method: String, argsJson: String): String =
        if (method in allowed) MyceliumEngine.call(method, argsJson) else """{"error":"not_allowed"}"""

    companion object {
        const val NAME = "MyceliumBridge"
        val TERRARIUM = setOf("terrarium", "presets", "packet_types", "terrarium_ack", "set_preset")

        /** JS that hands one engine event (a JSON object) to the page's Feed. */
        fun pushScript(eventJson: String) = "window.Feed && window.Feed._push($eventJson);"

        /** Forwards engine events whose "type" is in [types] to the page until [scope] is cancelled. */
        fun attach(webView: WebView, scope: CoroutineScope, types: Set<String> = setOf("terrarium_update")): Job =
            scope.launch(Dispatchers.Main) {
                MyceliumEngine.events.collect { ev ->
                    val type = try { Json.parseToJsonElement(ev).jsonObject["type"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                    if (type in types) webView.evaluateJavascript(pushScript(ev), null)
                }
            }
    }
}
