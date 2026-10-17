package app.mycelium.engine.db

import android.content.ContentValues
import android.database.Cursor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Rows travel as Map<column, value>: Long/Int/String/Double or null. */
typealias Row = Map<String, Any?>

fun Row.toContentValues() = ContentValues().also { cv ->
    for ((k, v) in this) when (v) {
        null -> cv.putNull(k)
        is Long -> cv.put(k, v)
        is Int -> cv.put(k, v)
        is Double -> cv.put(k, v)
        is Boolean -> cv.put(k, if (v) 1 else 0)
        else -> cv.put(k, v.toString())
    }
}

/** JSON object with the non-null columns. */
fun Row.toJson(): JsonObject = buildJsonObject {
    for ((k, v) in this@toJson) when (v) {
        null -> Unit
        is Number -> put(k, v)
        is Boolean -> put(k, if (v) 1 else 0)
        else -> put(k, v.toString())
    }
}

fun Cursor.row(): Row = (0 until columnCount).associate { i ->
    getColumnName(i) to when (getType(i)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_STRING -> getString(i)
        else -> null
    }
}
