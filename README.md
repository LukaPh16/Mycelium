# Mycelium

**Your data is kept and processed on your device.** Mycelium shows which apps on your phone contact which domains
and trackers. Capture, classification, stats, anomalies and the chatbot all run on the phone; logs never leave it.
There is no account, no analytics and no server of ours. The only optional network call is an AI analysis you start
yourself, with your own API key, and it sends only the facts you were shown and approved first.

## Build

Requirements: JDK 17, Android SDK (platform 36), `python3` (Pillow optional, for the sprite checks).

```
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :engine:testDebugUnitTest :app:assembleDebug
```
The build also runs `scripts/check_presets.py` (organism sprites). `python3 scripts/check_api_docs.py` checks that
every engine method is documented in [docs/api.md](docs/api.md).

## Install and first run

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
1. **Status → Choose apps**: tick the apps to watch (only these go through the VPN), Apply, pick an organism.
2. **Grant usage access** (foreground detection and per-app byte counts).
3. **Start VPN** and accept Android's VPN dialog. Without internet (airplane mode, Wi-Fi without internet) Mycelium
   runs in *Sanctuary*: it answers lookups locally, sends nothing, and processes what it caught once you are online.

Tabs: Status, Live (events), Terrarium (organisms), Garden, Stats, Tools (SQL, export, wipe, demo simulation), Chat.

## Your own AI key (optional)

Chat answers come from on-device templates by default (works offline, no key). For cloud answers:
Chat → **Key** → paste your Anthropic API key → Save. The key is encrypted with the Android Keystore and never
stored in the database, logs or exports. Then turn on **Ask AI**: each question first shows the exact payload in
"This will be sent to the AI provider"; nothing is sent until you tap **Send**. Without a key, offline, or on any
error, you get the on-device answer. Default model `claude-opus-5-5` (`call("ai_settings", {"model": ...})`).

## Phone setup

- **Private DNS: Off** (Settings → Network & internet → Private DNS). A Private DNS hostname bypasses the VPN.
- **Chrome: Use secure DNS off** (Settings → Privacy and security), or Chrome's own DNS-over-HTTPS bypasses it.
- **Xiaomi HyperOS / MIUI**, so the VPN is not killed in the background:
  - App info → **Pause app activity if unused: off**
  - Battery saver → **No restrictions**
  - **Autostart: on**

## Pull the database

```
adb exec-out run-as app.mycelium cat databases/mycelium.db     > mycelium.db
adb exec-out run-as app.mycelium cat databases/mycelium.db-wal > mycelium.db-wal   # recent writes; keep next to the .db
sqlite3 mycelium.db "SELECT kind, COUNT(*) FROM event GROUP BY kind"
```
Or Tools → **Export DB** (a consistent single file) and `adb pull` the path it shows. Schema: [docs/schema.sql](docs/schema.sql).

## Known limits

- **DNS-only view.** Mycelium sees DNS lookups, tripwire connection attempts and OS byte totals per app. It does not
  see contents, and does not see normal HTTPS/HTTP/QUIC traffic itself (those types are shown as "not seen").
- **Bypass cases** (documented, not worked around): Private DNS with a hostname, app-level DNS-over-HTTPS (Chrome
  Secure DNS, Firefox), hardcoded IP addresses, and answers an app cached before the VPN started (TTLs are capped at 10 s).
- **Attribution** uses the connection owner uid; when Android reports the system resolver instead, the event falls
  back to the foreground app (`how=guess_fg`) or stays unknown.
- **Byte counts are coarse**: NetworkStatsManager reports in buckets of about two hours, with a delay; cellular
  totals can be incomplete on dual-SIM phones.
- Only one VPN can run at a time; starting another VPN app stops Mycelium.

## Docs

- [docs/api.md](docs/api.md): every `MyceliumEngine.call()` method with an example, events, the WebView adapter
- [docs/terrarium.md](docs/terrarium.md): real sprites, thresholds and colors, replacing the terrarium UI
- [docs/schema.sql](docs/schema.sql): the database
- `CLAUDE.md`: project rules
