package app.mycelium.engine.bridge

import android.webkit.JavascriptInterface
import app.mycelium.engine.MyceliumEngine

/**
 * JavaScript interface for a web UI (window.MyceliumBridge). Only [allowed] engine methods are reachable, so a
 * replaced UI folder cannot run SQL, wipe data or touch the AI key. Events are pushed by the host with [pushScript].
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
    }
}
