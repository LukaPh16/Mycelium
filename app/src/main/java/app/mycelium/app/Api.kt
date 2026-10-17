package app.mycelium.app

import app.mycelium.engine.MyceliumEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thin JSON wrapper over MyceliumEngine.call; the debug app uses nothing else from the engine. */
object Api {
    fun call(method: String, args: JSONObject = JSONObject()): JSONObject =
        try { JSONObject(MyceliumEngine.call(method, args.toString())) } catch (e: Exception) { JSONObject().put("error", e.toString()) }

    suspend fun io(method: String, args: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) { call(method, args) }
}

fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in pairs) put(k, v) }

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

fun hhmmss(ts: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))

fun dateTime(ts: Long): String = if (ts <= 0) "-" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))

/** Date and time in the phone's own format, with seconds. */
fun fullTime(ts: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM).format(Date(ts))

fun human(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

fun shortPkg(p: String) = p.substringAfterLast('.')

fun pretty(s: String): String = try { JSONObject(s).toString(2) } catch (_: Exception) { s }
