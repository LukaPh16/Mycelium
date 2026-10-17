# Mycelium — project rules

Mycelium is an Android app that shows which apps contact which domains.
Main message: your data is kept and processed on your device.
- Logs are stored on the device only. Capture, classification, stats and anomalies all run on device.
- There is no on-device AI. AI analysis of logs is cloud-only and needs the user's own API key.
  It runs only when the user asks, and only sends the facts the user was shown first.
- No accounts, no analytics, no other network calls of our own.
A separate team builds the final UI in Lovable; we build the engine, the chatbot, and a plain debug UI.

## How to work
- Never skip a failing step. On any build, test, or install failure: read the error, fix the cause,
  re-run. Repeat until it passes. Never stub code, delete tests, add @Ignore, or weaken checks.
  After 5 failed attempts on the same error: stop, print the error, what you tried, your best guess.
- Be efficient. Do only the current step. Do not re-read files you just wrote. No long comments.
  One clear file beats three abstract ones. No single-implementation interfaces, no DI framework.
- After every step run: ./gradlew :engine:testDebugUnitTest :app:assembleDebug
- If `adb devices` lists a phone, install the debug APK and run the step's on-device check.
  If not, print the manual check steps and continue.
- End every step with a 5-line summary: done, verified, not verified, next risk.

## Stack
- Kotlin 2.x, AGP 8.x, Gradle wrapper, Kotlin DSL, Java 17.
- compileSdk 36, targetSdk 36, minSdk 30.
- Allowed deps: kotlinx-coroutines, kotlinx-serialization-json, AndroidX core/activity/lifecycle,
  Jetpack Compose Material 3 (debug UI only), JUnit 4.
- Cloud AI calls use java.net.HttpURLConnection (no extra HTTP library).
- Not allowed: Room, Retrofit, OkHttp, analytics, TTS, speech-to-text, on-device LLMs,
  any cloud API other than the user-keyed AI call in Step 9.

## Layout
- :engine  Android library. ALL logic. No UI.
- :app     Debug app, Compose, thin.
- docs/    schema.sql, api.md
- Packages: app.mycelium.engine.{vpn,dns,attr,classify,db,stats,chat,ai,sim,bridge,terrarium}

## Public API (UI-agnostic)
object MyceliumEngine {
  fun init(context: Context)
  fun call(method: String, argsJson: String = "{}"): String   // JSON in, JSON out
  val events: SharedFlow<String>                                // one event JSON each
  val state: StateFlow<String>   // {"vpn":"running|stopped|dead","mode":"online|sanctuary|simulate","ai":"no_key|ready|offline","queue":<n>}
}
Every feature is reachable through call(). New methods go into docs/api.md with one example.

## Database (light, flat, AI-readable)
One SQLite file mycelium.db, WAL, plain android.database.sqlite. One writer thread.
Reads use a separate read-only connection. Times are epoch ms. Schema lives in docs/schema.sql:

CREATE TABLE app (package TEXT PRIMARY KEY, uid INTEGER, label TEXT, seed TEXT,
  system INTEGER DEFAULT 0, last_used INTEGER,
  watched INTEGER DEFAULT 0,   -- 1 = user selected this app for monitoring
  watched_ts INTEGER);         -- when it was selected (data before this is not expected)
CREATE TABLE event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,  -- citation id
  ts INTEGER NOT NULL,
  kind TEXT NOT NULL,      -- dns|tripwire|block|unblock|vpn|anomaly
  ptype TEXT,              -- packet type id from packet_types.json (dns, https, tracker, ...); NULL for non-packet kinds
  package TEXT,            -- NULL = unknown
  how TEXT,                -- uid_dns|uid_tcp|guess_fg|unknown
  domain TEXT, qtype INTEGER,
  company TEXT,
  category TEXT,           -- benign|ads|analytics|fingerprinting|social|unknown (anomaly: rule name)
  verdict TEXT,            -- allowed|blocked|offline|error
  fg INTEGER,              -- 1 foreground, 0 background, NULL unknown
  port INTEGER,            -- tripwire only
  answer TEXT,             -- answered IPs, comma list
  note TEXT,               -- vpn/anomaly/debug text; anomaly evidence ids as comma list
  sim INTEGER DEFAULT 0);
CREATE INDEX ix_event_ts ON event(ts);
CREATE INDEX ix_event_pkg ON event(package, ts);
CREATE TABLE rule (scope TEXT NOT NULL, value TEXT NOT NULL, package TEXT NOT NULL DEFAULT '*',
  ts INTEGER NOT NULL, PRIMARY KEY (scope, value, package));
CREATE TABLE traffic (       -- per-app bytes and packets from NetworkStatsManager
  package TEXT, uid INTEGER, start_ts INTEGER, end_ts INTEGER,
  rx_bytes INTEGER, tx_bytes INTEGER, fg INTEGER, net TEXT,   -- net: wifi|cell
  rx_packets INTEGER, tx_packets INTEGER,                    -- OS packet counters, type unknown
  PRIMARY KEY (uid, start_ts, fg, net));
CREATE TABLE chat (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, role TEXT, text TEXT,
  cites TEXT, engine TEXT);  -- role user|bot; engine cloud|template
CREATE TABLE queue (         -- traffic caught in Sanctuary, waiting for on-device processing
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts INTEGER NOT NULL,
  kind TEXT NOT NULL,        -- dns|tripwire
  ptype TEXT,                -- same packet type ids as event.ptype
  uid INTEGER, package TEXT, how TEXT, fg INTEGER,
  domain TEXT, qtype INTEGER, port INTEGER,
  done INTEGER DEFAULT 0);   -- 1 = processed into event rows
CREATE TABLE organism (      -- one living organism per watched app
  package TEXT PRIMARY KEY,  -- app_id
  preset_id TEXT NOT NULL,   -- folder name under assets/organisms/
  chosen_ts INTEGER NOT NULL,
  stage_shown INTEGER DEFAULT 1);  -- last stage the UI played; prevents replaying upgrades
CREATE VIEW v_recent AS SELECT * FROM event ORDER BY id DESC LIMIT 200;
CREATE VIEW v_parasites AS SELECT package, company, category, COUNT(*) hits, MAX(ts) last_ts
  FROM event WHERE kind='dns' AND company IS NOT NULL AND sim=0 GROUP BY package, company;
CREATE VIEW v_night AS SELECT * FROM event WHERE kind='dns' AND fg=0
  AND CAST(strftime('%H', ts/1000, 'unixepoch', 'localtime') AS INTEGER) < 6;

Batch inserts: flush every 250 ms or 50 rows. Insert returns the row id.
Schema changes: additive only. Never rename columns.

## App selection (core rule)
- The user picks specific apps to watch. Only watched apps are routed into the VPN, using
  VpnService.Builder.addAllowedApplication(pkg) once per watched app. Unwatched apps are untouched.
- Never watch "all apps". At least 1 app must be watched to start the VPN; otherwise
  call("vpn",{action:"start"}) returns {error:"no_apps_selected"}.
- Our own package can never be selected.
- Changing the selection while the VPN runs = rebuild the Builder and establish() a new tun,
  then close the old one (seamless restart, no consent dialog). Write a kind='vpn' event noting it.
- Stats, garden, anomalies and chat only cover watched apps by default. Data of apps that were
  unwatched later is kept and marked as "not currently watched".

## Sanctuary mode (core rule)
- Online = at least one non-VPN network has NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED
  (the OS-confirmed "has real internet" signal). Anything else = Sanctuary: airplane mode, Wi-Fi
  without internet, captive portal, or no network.
- Use ConnectivityManager.registerNetworkCallback with a NetworkRequest that has
  NET_CAPABILITY_INTERNET and NET_CAPABILITY_NOT_VPN (so our own VPN never counts). Keep a map of
  network -> latest NetworkCapabilities. Re-evaluate on every onAvailable, onLost,
  onCapabilitiesChanged and onLinkPropertiesChanged: online only if any tracked network has
  NET_CAPABILITY_VALIDATED. Do not poll, do not ping, do not trust network presence alone.
- Sanctuary still starts the VPN and catches outgoing traffic of watched apps. Nothing is sent
  upstream. Each caught DNS query or tripwire SYN gets an immediate local answer (so apps do not
  hang) and is written to the local `queue` table.
- When a validated network returns: process the queue on device first (attribution check,
  classification, block rules, event rows, anomalies), mark rows done, write one kind='vpn'
  summary event ("sanctuary: N caught, M trackers"), then switch to online. Caught queries are
  never replayed to the network; apps retry on their own.
- state.mode and state.queue reflect this live.

## Packet types (core rule)
- One config file, engine/src/main/assets/config/packet_types.json, is the single source of truth
  for packet type ids, labels, colors, detection rule, and whether the current engine can see it.
  Engine and every UI read it; never hardcode a type or color anywhere else.
- Every caught packet gets exactly one ptype. Precedence: tracker (domain classified as a tracker)
  > protocol type (dns, https, http, quic, ...) > other. Unknown or unlisted types -> "other",
  which always renders in the neutral color.
- Honest limit: the DNS-only engine sees DNS queries and tripwire connection attempts (TCP SYN,
  port known). It does not see normal HTTPS/HTTP/TCP/UDP traffic. Those types keep their color and
  legend entry, marked observed:false, so a future full-tunnel engine fits without UI changes.
  NetworkStatsManager packet totals are counted as type "os_packets" (type unknown, neutral color).

## Terrarium and UI replaceability (core rule)
- Each watched app = one organism with a preset (25 presets, chosen by the user when watching).
- The engine only exposes data: call("terrarium") and the terrarium_update event. It knows
  nothing about sprites, frames or animation.
- The terrarium UI is one self-contained web module in app/src/main/assets/terrarium/
  (index.html + terrarium.js + terrarium.css, no build step, no framework), shown in a WebView via
  WebViewAssetLoader and WebBridge. Replacing the UI = replacing that folder, or having any other UI
  (e.g. the Lovable app) call the same API. The Compose debug app never draws organisms.
- Stage thresholds, packet weights and defaults live in assets/config/terrarium.json only.

## Facts to respect (do not "fix")
- addAllowedApplication and addDisallowedApplication cannot be mixed in one Builder. We use only
  addAllowedApplication. addDisallowedApplication excludes an app; it does not block.
- allowFamily(AF_INET6) is required because we add no IPv6 route.
- getConnectionOwnerUid works only for traffic inside our tunnel (API 29+).
- Strict Private DNS, hardcoded IPs, and app-level DoH bypass us. Document, do not work around.
