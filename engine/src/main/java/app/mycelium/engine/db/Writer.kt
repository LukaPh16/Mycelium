package app.mycelium.engine.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The one writer thread. Inserts are batched: flushed every 250 ms or 50 rows. Insert returns the row id (as a future). */
class Writer(private val db: SQLiteDatabase) {
    private sealed interface Job
    private class Insert(val table: String, val values: ContentValues, val id: CompletableFuture<Long>) : Job
    private class Run(val block: (SQLiteDatabase) -> Unit) : Job

    private val jobs = LinkedBlockingQueue<Job>()
    private val batch = ArrayList<Insert>()
    private var deadline = 0L

    init { thread(name = "db-writer", isDaemon = true) { loop() } }

    fun insert(table: String, values: ContentValues): CompletableFuture<Long> =
        CompletableFuture<Long>().also { jobs.put(Insert(table, values, it)) }

    /** Runs [block] on the writer thread after pending inserts are flushed. */
    fun run(block: (SQLiteDatabase) -> Unit) = jobs.put(Run(block))

    /** Like [run], but waits for the result. Never call from the writer thread. */
    fun <T> runAndWait(block: (SQLiteDatabase) -> T): T {
        val f = CompletableFuture<T>()
        jobs.put(Run { d -> try { f.complete(block(d)) } catch (e: Exception) { f.completeExceptionally(e); throw e } })
        return f.get(10, TimeUnit.SECONDS)
    }

    private fun loop() {
        while (true) {
            val job = if (batch.isEmpty()) jobs.take()
            else jobs.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            when (job) {
                null -> flush()
                is Insert -> {
                    if (batch.isEmpty()) deadline = System.currentTimeMillis() + FLUSH_MS
                    batch += job
                    if (batch.size >= FLUSH_ROWS) flush()
                }
                is Run -> {
                    flush()
                    try { job.block(db) } catch (e: Exception) { Log.e(TAG, "writer job failed", e) }
                }
            }
        }
    }

    private fun flush() {
        if (batch.isEmpty()) return
        val ids = LongArray(batch.size)
        val error = try {
            db.beginTransaction()
            try {
                batch.forEachIndexed { i, b -> ids[i] = db.insertOrThrow(b.table, null, b.values) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "batch insert failed", e)
            e
        }
        batch.forEachIndexed { i, b ->
            try {
                if (error == null) b.id.complete(ids[i]) else b.id.completeExceptionally(error)
            } catch (e: Exception) {
                Log.e(TAG, "insert callback failed", e)
            }
        }
        batch.clear()
    }

    companion object {
        private const val TAG = "MyceliumDb"
        const val FLUSH_MS = 250L
        const val FLUSH_ROWS = 50
    }
}
