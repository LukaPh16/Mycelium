package app.mycelium.engine

import android.content.Context
import android.database.DatabaseUtils
import android.net.VpnService
import app.mycelium.engine.attr.Apps
import app.mycelium.engine.attr.Attributor
import app.mycelium.engine.attr.Foreground
import android.util.Log
import app.mycelium.engine.ai.CloudAi
import app.mycelium.engine.ai.Http
import app.mycelium.engine.ai.KeyVault
import app.mycelium.engine.ai.Provider
import app.mycelium.engine.chat.AppRef
import app.mycelium.engine.chat.ChatBot
import app.mycelium.engine.chat.TemplateWriter
import app.mycelium.engine.classify.AnomalyJob
import app.mycelium.engine.classify.Classifier
import app.mycelium.engine.classify.RuleSet
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import app.mycelium.engine.attr.Attribution
import app.mycelium.engine.db.Db
import app.mycelium.engine.sim.Simulator
import app.mycelium.engine.terrarium.Terrarium
import app.mycelium.engine.terrarium.TerrariumFeed
import app.mycelium.engine.stats.SqlGuard
import app.mycelium.engine.stats.Stats
import app.mycelium.engine.stats.SessionCollector
import app.mycelium.engine.stats.TrafficCollector
import app.mycelium.engine.db.Row
import app.mycelium.engine.db.Writer
import app.mycelium.engine.db.row
import app.mycelium.engine.db.toContentValues
import app.mycelium.engine.db.toJson
import app.mycelium.engine.vpn.MyceliumVpnService
import app.mycelium.engine.vpn.NetGate
import app.mycelium.engine.vpn.Sanctuary
import app.mycelium.engine.vpn.WatchList
import app.mycelium.engine.vpn.WatchRules
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object MyceliumEngine {
    private lateinit var app: Context
    private lateinit var db: Db
    internal lateinit var writer: Writer
        private set
    internal lateinit var net: NetGate
        private set
    internal lateinit var attr: Attributor
        private set
    private lateinit var apps: Apps
    private lateinit var fg: Foreground
    private lateinit var stats: Stats
    private lateinit var traffic: TrafficCollector
    private lateinit var sessions: SessionCollector
    private lateinit var terrarium: Terrarium
    private lateinit var feed: TerrariumFeed
    private lateinit var chat: ChatBot
    private lateinit var vault: KeyVault
    private lateinit var prefs: android.content.SharedPreferences
    private val cloud by lazy { CloudAi(chat, ::readQuery, Http.post, { Log.i("MyceliumAi", it) }, simulating = { simPackages }) }

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile internal var vpn = "stopped"
        private set
    /** online|sanctuary from NetGate; drives the VPN. Simulation never changes it. */
    @Volatile private var netMode = "sanctuary"
    private val queued = AtomicInteger()
    internal val online get() = netMode == "online"
    private lateinit var sim: Simulator
    private val simPackages: Set<String>? get() = if (::sim.isInitialized) sim.packages else null

    /** What state.mode shows: simulate while a demo script is active, else the live network mode. */
    internal val mode get() = if (simPackages != null) "simulate" else netMode

    private val _state = MutableStateFlow(stateJson())
    val state: StateFlow<String> = _state.asStateFlow()

    @Synchronized fun init(context: Context) {
        if (::db.isInitialized) return
        app = context.applicationContext
        db = Db(app)
        writer = Writer(db.writableDatabase)
        queued.set(DatabaseUtils.longForQuery(db.reader, "SELECT COUNT(*) FROM queue WHERE done=0", null).toInt())
        val known = db.reader.rawQuery("SELECT package FROM app", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        apps = Apps(app, writer, known)
        fg = Foreground(app) { apps.touch(it) }
        attr = Attributor(app, fg, apps)
        WatchList.init(app, writer, apps, db.reader)
        Classifier.init(app, writer, db.reader)
        backfillPtypes()
        terrarium = loadTerrarium()
        WatchList.presetIds = terrarium.presets.keys.toList().ifEmpty { listOf(terrarium.config.defaultPreset) }
        feed = TerrariumFeed(terrarium, { _events.tryEmit(it) })
        AnomalyJob(db.reader, ::logEvent).start()
        sim = Simulator(app, ::logEvent, ::ensureSimApps, ::publish, ::simSession)
        stats = Stats(::readQuery, simulating = { simPackages })
        traffic = TrafficCollector(app, writer).also { it.start() }
        sessions = SessionCollector(app, writer).also { it.start() }
        vault = KeyVault(app)
        prefs = app.getSharedPreferences("mycelium_ai", Context.MODE_PRIVATE)
        chat = ChatBot(::readQuery, TemplateWriter.parse(app.assets.open("chat_templates.json").bufferedReader().use { it.readText() }),
            simulating = { simPackages })
        publish()
        net = NetGate(app, ::onNetwork).also { it.start() }
    }

    fun call(method: String, argsJson: String = "{}"): String {
        val args = try { Json.parseToJsonElement(argsJson).jsonObject } catch (e: Exception) { return error("bad_args") }
        return try {
            when (method) {
                "vpn" -> vpnCall(args)
                "events" -> stats.events(
                    args.int("limit") ?: 50, args.str("package"), args.str("kind"), args.long("since_ms"), args.long("before_id"),
                ).toString()
                "stats" -> {
                    val fn = args.str("fn") ?: return error("missing_fn")
                    if (fn == "screenTime" || fn == "usageDay") sessions.collectIfStale(60_000)
                    stats.call(fn, args, System.currentTimeMillis()).toString()
                }
                "timeline" -> stats.call("timeline", args, System.currentTimeMillis()).toString()
                "garden" -> stats.garden(System.currentTimeMillis(), viewScope(args)).toString()
                "simulate" -> simulateCall(args).also { feed.touchAll() }
                "terrarium" -> terrariumCall(args.str("app_id"))
                "presets" -> presetsCall()
                "terrarium_ack" -> terrariumAck(args.str("app_id") ?: return error("missing_app_id"), args.int("stage") ?: return error("missing_stage"))
                "set_preset" -> setPreset(args.str("app_id") ?: return error("missing_app_id"), args.str("preset_id") ?: return error("missing_preset_id"))
                "packet_types" -> Classifier.packetTypesJson
                "record" -> stats.record(args.long("id") ?: return error("missing_id")).toString()
                "sql" -> sqlCall(args.str("query").orEmpty())
                "export_db" -> exportDb()
                "chat" -> chatCall(args.str("text").orEmpty())
                "chat_history" -> chatHistory(args.int("limit") ?: 50)
                "chat_clear" -> chatClear()
                "ai_key" -> aiKey(args)
                "ai_status" -> buildJsonObject { put("state", aiState()); put("provider", aiProvider().id); put("model", aiModel()) }.toString()
                "ai_settings" -> {
                    args.str("provider")?.let { id ->
                        val p = Provider.entries.firstOrNull { it.id == id } ?: return error("unknown_provider")
                        if (p != aiProvider()) prefs.edit().putString("provider", p.id).remove("model").apply()   // new provider: its default model
                    }
                    args.str("model")?.trim()?.takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("model", it).apply() }
                    buildJsonObject { put("provider", aiProvider().id); put("model", aiModel()) }.toString()
                }
                "ai_preview" -> {
                    val text = args.str("text") ?: return error("missing_text")
                    cloud.preview(text, chatApps(), Classifier.trackers.companies, System.currentTimeMillis(), aiModel(), aiProvider()).toString()
                }
                "ai_ask" -> aiAsk(args.str("preview_id"))
                "wipe" -> when {
                    !args.bool("confirm") -> error("confirm_required")
                    args.str("package") != null -> wipeApp(args.str("package")!!)
                    else -> wipe()
                }
                "refresh_traffic" -> if (!Foreground.granted(app)) error("usage_access_required") else {
                    val n = traffic.collect()
                    feed.touchAll()
                    buildJsonObject { put("ok", true); put("rows", n); put("apps", WatchList.packages().size) }.toString()
                }
                "usage_access" -> buildJsonObject { put("granted", Foreground.granted(app)) }.toString()
                "usage_access_settings" -> { Foreground.openSettings(app); ok() }
                "seed_apps" -> apps.seedLaunchable().let { (n, total) ->
                    buildJsonObject { put("inserted", n); put("total", total) }.toString()
                }
                "how_breakdown" -> howBreakdown(args)
                "apps" -> appsCall(args)
                "watched" -> watchedCall()
                "watch" -> watchCall(args)
                "unwatch" -> unwatchCall(args)
                "block" -> ruleCall(args, block = true)
                "unblock" -> ruleCall(args, block = false)
                "rules" -> rulesCall()
                "anomalies" -> anomaliesCall(args)
                else -> error("unknown_method")
            }
        } catch (e: IllegalArgumentException) {
            error(e.message ?: "bad_args")
        } catch (e: Exception) {
            buildJsonObject { put("error", "internal"); put("message", e.toString()) }.toString()
        }
    }

    private fun vpnCall(args: JsonObject): String = when (args["action"]?.jsonPrimitive?.content) {
        "start" -> when {
            WatchList.startError() != null -> error(WatchList.startError()!!)
            VpnService.prepare(app) != null -> """{"needs_consent":true}"""
            else -> { MyceliumVpnService.start(app); """{"ok":true}""" }
        }
        "stop" -> { MyceliumVpnService.stop(app); """{"ok":true}""" }
        "status" -> state.value
        else -> error("bad_action")
    }

    private fun readQuery(sql: String, args: List<String>): List<Row> =
        db.reader.rawQuery(sql, args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.row()) } }

    /** User/AI SQL: guarded, read-only connection, max 500 rows, cancelled after 2 s. */
    private fun sqlCall(query: String): String {
        SqlGuard.check(query)?.let { return error(it) }
        val cancel = CancellationSignal()
        val timer = Executors.newSingleThreadScheduledExecutor().apply { schedule({ cancel.cancel() }, SqlGuard.TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        return try {
            db.reader.rawQuery(query, null, cancel).use { c ->
                val rows = buildJsonArray {
                    var n = 0
                    while (n < SqlGuard.MAX_ROWS && c.moveToNext()) { add(c.row().toJson()); n++ }
                }
                buildJsonObject {
                    put("columns", buildJsonArray { c.columnNames.forEach { add(JsonPrimitive(it)) } })
                    put("rows", rows)
                    put("truncated", rows.size == SqlGuard.MAX_ROWS && !c.isAfterLast && c.moveToNext())
                }.toString()
            }
        } catch (_: OperationCanceledException) {
            error("timeout")
        } catch (e: SQLiteException) {
            buildJsonObject { put("error", "sql_error"); put("message", e.message ?: "") }.toString()
        } finally {
            timer.shutdownNow()
        }
    }

    /** Template chatbot: answers only from on-device data. Both turns go to the chat table. */
    private fun chatCall(text: String): String {
        if (text.isBlank()) return error("missing_text")
        val question = text.take(500)
        val now = System.currentTimeMillis()
        val started = System.nanoTime()
        val r = chat.answer(question, chatApps(), Classifier.trackers.companies, now)
        val result = JsonObject(r + ("latency_ms" to JsonPrimitive((System.nanoTime() - started) / 1_000_000)))
        storeTurns(question, now, result)
        return result.toString()
    }

    /** Both turns into the chat table; the bot row keeps per-sentence cites, engine and latency. */
    private fun storeTurns(question: String, askedAt: Long, result: JsonObject) {
        writer.insert("chat", mapOf("ts" to askedAt, "role" to "user", "text" to question).toContentValues())
        val sentences = result.getValue("sentences").jsonArray
        writer.insert("chat", mapOf(
            "ts" to System.currentTimeMillis(), "role" to "bot",
            "engine" to result.getValue("engine").jsonPrimitive.content,
            "latency_ms" to result["latency_ms"]?.jsonPrimitive?.longOrNull,
            "text" to sentences.joinToString(" ") { it.jsonObject.getValue("text").jsonPrimitive.content },
            "cites" to sentences.flatMap { s -> s.jsonObject.getValue("cites").jsonArray.map { it.jsonPrimitive.content } }.distinct().joinToString(","),
            "sentences" to sentences.toString(),
        ).toContentValues())
    }

    private fun chatClear(): String {
        writer.runAndWait { d -> d.execSQL("DELETE FROM chat"); d.execSQL("DELETE FROM sqlite_sequence WHERE name='chat'") }
        return ok()
    }

    /** Stores ({key}) or deletes ({clear:true}) the API key. The key is never echoed back. */
    private fun aiKey(args: JsonObject): String {
        if (args.bool("clear")) vault.clear()
        else {
            val key = args.str("key") ?: return error("missing_key")
            if (key.length > 512 || key.any { it.isWhitespace() }) return error("bad_key")
            vault.save(key)
        }
        publish()
        return buildJsonObject { put("ok", true); put("state", aiState()) }.toString()
    }

    /** Sends a previewed payload (only after the user tapped Send). Stores both turns; one kind='ai' event per send. */
    private fun aiAsk(previewId: String?): String {
        val now = System.currentTimeMillis()
        val out = cloud.ask(previewId, if (vault.has()) vault.load() else null, net.online, chatApps(), Classifier.trackers.companies, now)
        out.event?.let(::logEvent)
        val question = out.question ?: return out.result.toString()
        storeTurns(question, now, out.result)
        return out.result.toString()
    }

    /** ready only with a stored key AND a validated non-VPN network. */
    private fun aiState() = when {
        !::vault.isInitialized || !vault.has() -> "no_key"
        ::net.isInitialized && net.online -> "ready"
        else -> "offline"
    }

    private fun aiProvider() = Provider.of(prefs.getString("provider", null))

    private fun aiModel() = prefs.getString("model", null) ?: aiProvider().defaultModel

    /** Last [limit] turns, oldest first. */
    private fun chatHistory(limit: Int): String {
        val rows = readQuery("SELECT * FROM (SELECT * FROM chat ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}) ORDER BY id", emptyList())
        return buildJsonObject { put("turns", buildJsonArray { rows.forEach { add(it.toJson()) } }) }.toString()
    }

    /** Apps the router can name: everything in the app table plus every launchable app. */
    private fun chatApps(): List<AppRef> {
        val known = readQuery("SELECT package, label, watched FROM app", emptyList()).associate { r ->
            val p = r["package"] as String
            p to AppRef(p, r["label"] as String? ?: p, (r["watched"] as Long?) == 1L || simPackages?.contains(p) == true)
        }
        val launchable = apps.launchable().filter { it !in known }.map { AppRef(it, apps.label(it), WatchList.isWatched(it)) }
        return known.values.toList() + launchable
    }

    /** Consistent snapshot (includes WAL content) to the app's external files dir. */
    private fun exportDb(): String {
        val dir = app.getExternalFilesDir(null) ?: return error("no_external_storage")
        val out = java.io.File(dir, "mycelium-${System.currentTimeMillis()}.db")
        writer.runAndWait { d -> d.execSQL("VACUUM INTO '" + out.absolutePath.replace("'", "''") + "'") }
        return buildJsonObject { put("ok", true); put("path", out.absolutePath); put("bytes", out.length()) }.toString()
    }

    /** Deletes captured data (event, queue, traffic, chat, session). Keeps app selection, rules and organisms. */
    private fun wipe(): String {
        writer.runAndWait { d ->
            d.beginTransaction()
            try {
                for (t in listOf("event", "queue", "traffic", "chat", "session")) d.execSQL("DELETE FROM $t")
                d.execSQL("DELETE FROM sqlite_sequence WHERE name IN ('event','queue','chat')")
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        queued.set(0)
        publish()
        _events.tryEmit("""{"type":"wiped"}""")
        return ok()
    }

    /** One app's record: its events, Sanctuary catches, byte counts and screen time; its creature starts over at stage 1. */
    private fun wipeApp(pkg: String): String {
        var deleted = 0
        writer.runAndWait { d ->
            d.beginTransaction()
            try {
                for (t in listOf("event", "queue", "traffic", "session")) deleted += d.delete(t, "package=?", arrayOf(pkg))
                d.execSQL("UPDATE organism SET stage_shown=1 WHERE package=?", arrayOf(pkg))
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        queued.set(readQuery("SELECT COUNT(*) n FROM queue WHERE done=0", emptyList()).firstOrNull()?.get("n")?.let { (it as Number).toInt() } ?: 0)
        if (::feed.isInitialized) feed.touchAll()
        publish()
        _events.tryEmit(buildJsonObject { put("type", "wiped"); put("package", pkg) }.toString())
        return buildJsonObject { put("ok", true); put("package", pkg); put("rows", deleted) }.toString()
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull == true

    /**
     * SQL condition limiting stats, garden and anomalies to watched apps unless {include_unwatched:true}.
     * Rows with no package are kept: only watched apps' traffic enters the tunnel.
     */
    internal fun scope(args: JsonObject): String = "1" + viewScope(args).events

    /** Watched apps and real rows by default; in simulate mode also sim rows and the scripted packages. */
    private fun viewScope(args: JsonObject) = Stats.Scope(args.bool("include_unwatched"), args.bool("include_sim"), simPackages)

    /** {script, speed} starts a replay (mode=simulate); {stop:true} leaves simulate mode. */
    private fun simulateCall(args: JsonObject): String {
        if (args.bool("stop")) {
            sim.stop()
            return buildJsonObject { put("ok", true); put("mode", mode) }.toString()
        }
        val s = sim.start(args.str("script") ?: "demo1", args["speed"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
        return buildJsonObject {
            put("ok", true); put("mode", mode); put("script", s.name); put("rows", s.events.size)
            put("packages", buildJsonArray { s.packages.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    /** Scripted apps get an app row (label, seed) if missing; app.watched is never changed. */
    private fun ensureSimApps(apps: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        writer.runAndWait { d ->
            for ((pkg, label) in apps) {
                val seed = Attribution.seed(pkg)
                d.insertWithOnConflict("app", null,
                    mapOf("package" to pkg, "label" to label, "seed" to seed, "system" to 0).toContentValues(),
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                // An organism so the demo can grow and ack stages; app.watched stays as it is.
                d.execSQL("INSERT OR IGNORE INTO organism(package, preset_id, chosen_ts) VALUES(?,?,?)",
                    arrayOf<Any>(pkg, WatchRules.pickPreset(seed, WatchList.presetIds), now))
            }
        }
    }

    // ---- terrarium (data only; any UI draws it) ----

    private fun loadTerrarium(): Terrarium {
        fun asset(path: String) = app.assets.open(path).bufferedReader().use { it.readText() }
        val config = Terrarium.parseConfig(asset("config/terrarium.json"))
        presetIndex = Json.parseToJsonElement(asset(config.presetsIndex)).jsonObject.getValue("presets").jsonArray.map { it.jsonObject }
        val presets = LinkedHashMap<String, JsonObject>()
        for (p in presetIndex) presets[p.getValue("id").jsonPrimitive.content] = Json.parseToJsonElement(asset(p.getValue("file").jsonPrimitive.content)).jsonObject
        return Terrarium(::readQuery, config, presets, Classifier.typer, simulating = { simPackages })
    }

    private var presetIndex: List<JsonObject> = emptyList()

    private fun terrariumCall(appId: String?): String {
        val apps = terrarium.list(appId)
        if (appId != null && apps.isEmpty()) return error("unknown_app")
        return buildJsonObject {
            put("apps", JsonArray(apps))
            put("stage_thresholds", JsonArray(terrarium.config.thresholds.map { JsonPrimitive(it) }))
        }.toString()
    }

    private fun presetsCall(): String = buildJsonObject {
        put("presets", JsonArray(presetIndex.map { p -> JsonObject(p + ("preset" to terrarium.presets.getValue(p.getValue("id").jsonPrimitive.content))) }))
        put("default_preset", terrarium.config.defaultPreset)
    }.toString()

    private fun terrariumAck(appId: String, stage: Int): String {
        val n = writer.runAndWait { d ->
            d.compileStatement("UPDATE organism SET stage_shown=? WHERE package=?").use { st ->
                st.bindLong(1, stage.toLong()); st.bindString(2, appId); st.executeUpdateDelete()
            }
        }
        if (n == 0) return error("no_organism")
        feed.touchApp(appId)
        return ok()
    }

    /** Changes the organism later; growth and stage are kept (they come from the counts, not the preset). */
    private fun setPreset(appId: String, presetId: String): String {
        if (presetId !in terrarium.presets) return error("unknown_preset")
        if (!WatchList.isWatched(appId) && simPackages?.contains(appId) != true) return error("not_watched")
        writer.runAndWait { d ->
            d.execSQL(
                "INSERT INTO organism(package, preset_id, chosen_ts) VALUES(?,?,?) " +
                    "ON CONFLICT(package) DO UPDATE SET preset_id=excluded.preset_id, chosen_ts=excluded.chosen_ts",
                arrayOf<Any>(appId, presetId, System.currentTimeMillis()),
            )
        }
        feed.touchApp(appId)
        return buildJsonObject { put("ok", true); put("app_id", appId); put("preset_id", presetId) }.toString()
    }

    /** Sets ptype on packet rows written before packet types existed (idempotent: only NULL ptypes). */
    private fun backfillPtypes() = writer.run { d ->
        val typer = Classifier.typer
        for (table in listOf("event", "queue")) {
            val companyCol = if (table == "event") "company" else "NULL"
            val rows = d.rawQuery("SELECT id, kind, port, domain, $companyCol FROM $table WHERE ptype IS NULL AND kind IN ('dns','tripwire')", null).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), Triple(if (c.isNull(2)) null else c.getInt(2), c.getString(3), c.getString(4)))) }
            }
            if (rows.isEmpty()) continue
            d.beginTransaction()
            try {
                for ((id, kind, rest) in rows) {
                    val (port, domain, company) = rest
                    val ptype = if (kind == "dns") typer.type("dns", "udp", 53, domain, company ?: domain?.let { Classifier.classify(it, null, null).company })
                        else typer.type("tripwire", "tcp", port, null, null)
                    d.execSQL("UPDATE $table SET ptype=? WHERE id=?", arrayOf<Any>(ptype, id))
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            Log.i("Mycelium", "backfilled ptype on ${rows.size} $table rows")
        }
    }

    private fun appsCall(args: JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        val includeSystem = args["include_system"]?.jsonPrimitive?.booleanOrNull == true
        val since = System.currentTimeMillis() - 24 * 3600_000L
        val lookups = db.reader.rawQuery(
            "SELECT package, COUNT(*) FROM event WHERE kind='dns' AND ts>=? AND package IS NOT NULL GROUP BY package",
            arrayOf(since.toString()),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
        val pm = app.packageManager
        val list = apps.launchable().mapNotNull { p ->
            val ai = try { pm.getApplicationInfo(p, 0) } catch (_: Exception) { return@mapNotNull null }
            val watched = WatchList.isWatched(p)
            val system = apps.isSystem(ai)
            val label = apps.label(p, ai)
            if (system && !includeSystem && !watched) return@mapNotNull null
            if (query.isNotEmpty() && query !in label.lowercase() && query !in p.lowercase()) return@mapNotNull null
            Triple(label, p, watched) to system
        }.sortedWith(compareBy({ !it.first.third }, { it.first.first.lowercase() }))
        return buildJsonObject {
            put("apps", buildJsonArray {
                for ((t, system) in list) add(buildJsonObject {
                    put("label", t.first); put("package", t.second); put("watched", t.third)
                    put("system", system); put("lookups_24h", lookups[t.second] ?: 0L)
                })
            })
        }.toString()
    }

    private fun watchedCall(): String = buildJsonObject {
        put("apps", buildJsonArray {
            for (p in WatchList.packages()) add(buildJsonObject { put("package", p); put("label", WatchList.label(p)) })
        })
    }.toString()

    private fun packagesArg(args: JsonObject): List<String> =
        args["packages"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun watchCall(args: JsonObject): String {
        val presets = args["presets"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        if (presets.values.any { it !in terrarium.presets }) return error("unknown_preset")
        val (plan, organismsChanged) = WatchList.watch(packagesArg(args), presets)
        if (plan.changes.isNotEmpty() || organismsChanged) selectionChanged()
        return selectionResult(plan)
    }

    private fun unwatchCall(args: JsonObject): String {
        val plan = WatchList.unwatch(packagesArg(args))
        if (plan.changes.isNotEmpty()) selectionChanged()
        return selectionResult(plan)
    }

    /** Seamless tunnel rebuild if running, then one vpn event naming the watched apps. */
    private fun selectionChanged() {
        if (::feed.isInitialized) feed.touchAll()
        val rebuilt = MyceliumVpnService.rebuild()
        val labels = WatchList.packages().map(WatchList::label).sorted().joinToString(", ").ifEmpty { "nothing" }
        logEvent(mapOf("ts" to System.currentTimeMillis(), "kind" to "vpn",
            "note" to "watching: $labels" + if (rebuilt) " (tunnel rebuilt)" else ""))
    }

    private fun selectionResult(plan: WatchRules.Plan) = buildJsonObject {
        put("watching", buildJsonArray { WatchList.packages().forEach { add(JsonPrimitive(it)) } })
        put("rejected", buildJsonObject { plan.rejected.forEach { (p, why) -> put(p, why) } })
    }.toString()

    private fun ruleCall(args: JsonObject, block: Boolean): String {
        val scope = args["scope"]?.jsonPrimitive?.contentOrNull
        val value = args["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: "*"
        if (scope != RuleSet.SCOPE_DOMAIN && scope != RuleSet.SCOPE_COMPANY) return error("bad_scope")
        if (value.isEmpty()) return error("bad_value")
        val rule = if (block) Classifier.block(scope, value, pkg) else Classifier.unblock(scope, value, pkg) ?: return error("no_such_rule")
        val ev = ruleEvent(rule, if (block) "block" else "unblock")
        val id = writer.insert("event", ev.toContentValues()).get(5, TimeUnit.SECONDS)
        emitEvent(id, ev)
        return buildJsonObject { put("ok", true); put("event_id", id) }.toString()
    }

    private fun ruleEvent(r: RuleSet.Rule, kind: String): Row = mapOf(
        "ts" to System.currentTimeMillis(), "kind" to kind, "package" to r.pkg.takeIf { it != "*" },
        "domain" to r.value.takeIf { r.scope == RuleSet.SCOPE_DOMAIN },
        "company" to r.value.takeIf { r.scope == RuleSet.SCOPE_COMPANY },
        "note" to "${r.scope}=${r.value} package=${r.pkg}",
    )

    private fun rulesCall(): String = buildJsonObject {
        put("rules", buildJsonArray {
            for (r in Classifier.rules.rules.sortedByDescending { it.ts }) add(buildJsonObject {
                put("scope", r.scope); put("value", r.value); put("package", r.pkg); put("ts", r.ts)
            })
        })
    }.toString()

    private fun anomaliesCall(args: JsonObject): String {
        val since = args["since_ms"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() - 24 * 3600_000L)
        val rows = db.reader.rawQuery(
            "SELECT * FROM event WHERE kind='anomaly' AND ts>=? AND ${scope(args)} ORDER BY id DESC LIMIT 500",
            arrayOf(since.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.row()) } }
        return buildJsonObject {
            put("anomalies", buildJsonArray {
                for (r in rows) add(buildJsonObject {
                    r.toJson().forEach { (k, v) -> put(k, v) }
                    put("rule", r["category"] as String?)
                    put("evidence", buildJsonArray {
                        (r["note"] as String?).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.forEach { add(JsonPrimitive(it)) }
                    })
                })
            })
        }.toString()
    }

    private fun howBreakdown(args: JsonObject): String {
        val counts = db.reader.rawQuery("SELECT COALESCE(how,'null'), COUNT(*) FROM event WHERE kind IN ('dns','tripwire') AND ${scope(args)} GROUP BY how", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
        }
        return buildJsonObject {
            put("total", counts.sumOf { it.second })
            put("how", buildJsonObject { counts.forEach { (h, n) -> put(h, n) } })
        }.toString()
    }

    private fun ok() = """{"ok":true}"""

    private fun error(code: String) = buildJsonObject { put("error", code) }.toString()

    // ---- internal plumbing used by vpn/ and db/ ----

    internal fun logEvent(row: Row) {
        writer.insert("event", row.toContentValues()).thenAccept { id -> emitEvent(id, row) }
    }

    internal fun emitEvent(id: Long, row: Row) {
        _events.tryEmit(buildJsonObject { put("type", "event"); put("row", (row + ("id" to id)).toJson()) }.toString())
        packetWritten(row)
    }

    private fun simSession(pkg: String, start: Long, end: Long) = writer.run { d ->
        d.insertWithOnConflict("session", null, mapOf("package" to pkg, "start_ts" to start, "end_ts" to end, "sim" to 1).toContentValues(),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    internal fun enqueue(row: Row) {
        writer.insert("queue", row.toContentValues()).thenAccept { queued.incrementAndGet(); publish(); packetWritten(row) }
    }

    /** Sanctuary packets count at once (as queue rows); feed throttles terrarium_update per app. */
    private fun packetWritten(row: Row) {
        val pkg = row["package"] as String? ?: return
        val ptype = row["ptype"] as String? ?: return
        if (::feed.isInitialized) feed.touch(pkg, ptype)
    }

    internal fun setVpn(v: String) {
        vpn = v
        if (v == "running") fg.start() else { fg.stop(); if (::sessions.isInitialized) sessions.collectSoon() }
        publish()
    }

    private fun setMode(m: String) {
        if (netMode == m) return
        netMode = m
        publish()
    }

    /** NetGate callback. Going online: process the queue on device first, then switch mode. */
    private fun onNetwork(isOnline: Boolean) {
        publish()   // state.ai follows the validated network immediately
        if (!isOnline) {
            if (netMode != "sanctuary") logEvent(mapOf("ts" to System.currentTimeMillis(), "kind" to "vpn", "note" to "mode: sanctuary"))
            setMode("sanctuary")
            return
        }
        writer.run { d ->
            Sanctuary.drain(d)
            if (net.online) setMode("online")
            refreshQueue(d)
        }
        // Rows caught while the switch was in flight land after the first pass.
        writer.run { d -> if (online) { Sanctuary.drain(d); refreshQueue(d) } }
    }

    private fun refreshQueue(d: android.database.sqlite.SQLiteDatabase) {
        queued.set(DatabaseUtils.longForQuery(d, "SELECT COUNT(*) FROM queue WHERE done=0", null).toInt())
        publish()
    }

    private fun publish() {
        if (::app.isInitialized) _state.value = stateJson()
    }

    private fun stateJson() = buildJsonObject {
        put("vpn", vpn); put("mode", mode); put("ai", aiState()); put("queue", queued.get())
    }.toString()
}
