package app.mycelium.engine

import android.content.Context
import app.mycelium.engine.db.Db
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object MyceliumEngine {
    private lateinit var db: Db

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _state = MutableStateFlow("""{"vpn":"stopped","mode":"online","ai":"no_key","queue":0}""")
    val state: StateFlow<String> = _state.asStateFlow()

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = Db(context.applicationContext)
        db.writableDatabase
    }

    fun call(method: String, argsJson: String = "{}"): String = when (method) {
        else -> """{"error":"unknown_method"}"""
    }
}
