package app.mycelium.engine.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** mycelium.db: schema from assets/schema.sql on first open, WAL, separate read-only connection. */
class Db(private val context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        val sql = context.assets.open("schema.sql").bufferedReader().use { it.readText() }
        db.beginTransaction()
        try {
            SchemaSql.split(sql).forEach(db::execSQL)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Additive only: add what later versions of docs/schema.sql introduced. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE chat ADD COLUMN latency_ms INTEGER")
            db.execSQL("ALTER TABLE chat ADD COLUMN sentences TEXT")
        }
    }

    val reader: SQLiteDatabase by lazy {
        writableDatabase // ensure the file and schema exist first
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(NAME).path, null,
            SQLiteDatabase.OPEN_READONLY,
        )
    }

    companion object {
        const val NAME = "mycelium.db"
        const val VERSION = 2
    }
}
