# Mycelium engine API

All features go through `MyceliumEngine.call(method, argsJson)`, JSON in, JSON out.
Errors look like `{"error":"<code>"}`.

## state
`MyceliumEngine.state` (StateFlow<String>):
```
{"vpn":"running|stopped|dead","mode":"online|sanctuary|simulate","ai":"no_key|ready|offline","queue":3}
```

## events
`MyceliumEngine.events` (SharedFlow<String>), one JSON per emission. A new event row:
```
{"type":"event","row":{"id":42,"ts":1760000000000,"kind":"dns","how":"unknown","domain":"example.com","qtype":1,"verdict":"allowed","answer":"93.184.216.34"}}
```
Null columns are omitted.

## vpn
```
call("vpn", {"action":"start"})  -> {"ok":true} | {"needs_consent":true} | {"error":"no_apps_selected"}
call("vpn", {"action":"stop"})   -> {"ok":true}
call("vpn", {"action":"status"}) -> same JSON as state
```
`needs_consent`: the UI must launch `VpnService.prepare(context)` and call start again after RESULT_OK.

## events (read)
The raw log, newest first. Filters are optional; rows with a package carry `watched` 1|0.
```
call("events", {"limit":20,"package":"com.android.chrome","kind":"dns","since_ms":1760000000000,"before_id":900})
  -> {"events":[{"id":899,"ts":...,"kind":"dns","package":"com.android.chrome","watched":1,...}, ...]}   // limit 1..500, default 50
```

## record
One event with its app; anomalies include their evidence rows.
```
call("record", {"id":901}) -> {"record":{"id":901,"kind":"anomaly","app_label":"Chrome",...},"evidence":[{"id":880,...}],"event_ids":[901,880]}
```
Error: `not_found`, `missing_id`.

## garden
One entry per watched app (organism + last 24 h), for the terrarium and Lovable UI.
```
call("garden") -> {"apps":[{"package":"com.android.chrome","label":"Chrome","seed":"3966e13ad3d5eb0c","watched":1,
    "watched_ts":...,"preset_id":"preset01","stage_shown":1,"lookups_24h":312,"tracker_hits_24h":40,"companies_24h":6,
    "blocked_24h":3,"anomalies_24h":1,"last_seen":...,"bytes_24h":18340221}],"event_ids":[...]}
```
`{"include_unwatched":true}` adds apps that are no longer watched but have data.

## stats
`call("stats", {"fn":..., ...})`. Common args: `from_ms` (default `to_ms` - 24 h), `to_ms` (default now),
`include_unwatched` (default false), `include_sim` (default false). Every result has `fn` and `event_ids` (max 50,
the event rows it is based on; empty for byte stats, which come from the traffic table).
```
{"fn":"appSummary","package":"com.android.chrome"}
  -> lookups, unique_domains, tracker_hits, companies, blocked, fg, bg, fg_unknown, tripwire_hits, first_seen, last_seen,
     rx_bytes, tx_bytes, rx_packets, tx_packets, trackers:[{company,category,hits,last_ts}]
{"fn":"topApps","by":"lookups|trackers|bytes|background","limit":10}  -> apps:[{package,label,value,...}]
{"fn":"topCompanies","limit":10}                -> companies:[{company,category,hits,apps,packages,last_ts}]
{"fn":"timeline","bucket_ms":3600000,"package":"com.android.chrome"}  -> buckets:[{start_ms,lookups,tracker_hits,blocked,bg}]
{"fn":"domainHistory","domain":"doubleclick.net"}  -> company, category, total, apps:[{package,hits,first_ts,last_ts,verdicts}], events:[...]
{"fn":"lastEvents","package":"com.android.chrome","domain":"doubleclick.net","limit":20}  -> events:[...]
{"fn":"nightReport","date":"2026-10-17"}        -> 00:00-06:00 local: lookups, background_lookups, apps:[...], top_domains:[...]
{"fn":"compare","package":"com.android.chrome","period1":{"from_ms":..,"to_ms":..},"period2":{"from_ms":..,"to_ms":..}}
  -> period1, period2, delta:{lookups,...,rx_bytes,tx_bytes}, new_companies, gone_companies
```
Domains match subdomains. Errors: `unknown_fn`, `missing_<arg>`, `bad_by`, `bad_date`, `bad_range`, `bad_bucket`, `too_many_buckets`.

## timeline
Shortcut for `call("stats", {"fn":"timeline", ...})`, same args and result.

## sql
Read-only SQL for the AI and power users. One `SELECT` or `WITH` statement, no `;`, no PRAGMA/ATTACH or write keywords,
read-only connection, max 500 rows, cancelled after 2 s.
```
call("sql", {"query":"SELECT company, COUNT(*) n FROM event WHERE kind='dns' GROUP BY company ORDER BY n DESC LIMIT 5"})
  -> {"columns":["company","n"],"rows":[{"company":"Google","n":120}, ...],"truncated":false}
```
Errors: `empty_query`, `semicolon_not_allowed`, `only_select`, `forbidden_keyword`, `timeout`, `sql_error` (+ message).

## traffic
Per-app bytes/packets from NetworkStatsManager for watched apps (last 48 h, wifi + cell, fg/bg buckets), every 15 min.
```
call("refresh_traffic") -> {"ok":true,"rows":96,"apps":2} | {"error":"usage_access_required"}
```

## attribution
Event rows carry `package`, `how` (uid_dns|uid_tcp|guess_fg|unknown) and `fg` (1|0|null).
```
call("usage_access")          -> {"granted":true}
call("usage_access_settings") -> {"ok":true}            // opens Android's Usage Access screen
call("seed_apps")             -> {"inserted":41,"total":43}   // all launchable apps into the app table
call("how_breakdown")         -> {"total":120,"how":{"uid_dns":98,"guess_fg":12,"unknown":10}}   // dns+tripwire events
```

## app selection
Only watched apps are routed into the VPN. Our own package is never selectable.
```
call("apps", {"query":"chr","include_system":false})
  -> {"apps":[{"label":"Chrome","package":"com.android.chrome","watched":true,"system":false,"lookups_24h":312}, ...]}
     // launchable apps, watched first then by label; watched apps always listed even if system
call("watched") -> {"apps":[{"package":"com.android.chrome","label":"Chrome"}]}
call("watch",   {"packages":["org.mozilla.firefox"],"presets":{"org.mozilla.firefox":"preset07"}})
  -> {"watching":["com.android.chrome","org.mozilla.firefox"],"rejected":{}}
call("unwatch", {"packages":["org.mozilla.firefox"]}) -> {"watching":["com.android.chrome"],"rejected":{}}
```
- `rejected` reasons: `own_package`, `not_installed`.
- watch sets `watched_ts` to now (unchanged if already watched); unwatch keeps it. Data of unwatched apps is kept.
- Every watched app gets an organism row: the given preset, else seed mod preset count (only `preset01` until Step 12).
- If the VPN runs, the tunnel is rebuilt seamlessly (no consent). Each change writes a vpn event `watching: <labels>`.
- `call("vpn",{"action":"start"})` with no watched apps -> `{"error":"no_apps_selected"}`.
- `events` rows carry `watched` (1|0) when they have a package; 0 = not currently watched.
- Stats, garden and anomalies (`how_breakdown` now) cover watched apps only; pass `{"include_unwatched":true}` for all.

## classification and rules
dns events carry `company` and `category` (ads|analytics|fingerprinting|social from assets/trackers.json, else unknown).
Domains match by suffix: `googleads.g.doubleclick.net` -> `doubleclick.net`.
```
call("block",   {"scope":"domain","value":"doubleclick.net"})                          -> {"ok":true,"event_id":812}
call("block",   {"scope":"company","value":"AppsFlyer","package":"com.example.game"})  -> {"ok":true,"event_id":813}
call("unblock", {"scope":"domain","value":"doubleclick.net"})                          -> {"ok":true,"event_id":814}
call("rules") -> {"rules":[{"scope":"company","value":"AppsFlyer","package":"com.example.game","ts":1760000000000}]}
```
- Errors: `bad_scope`, `bad_value`, `no_such_rule` (unblock).
- `package` defaults to `"*"` (all apps). Per-app rules apply only when the event's `how` is `uid_dns` or `uid_tcp`.
- Domain rules also cover subdomains. Writes a `block` / `unblock` event.
- Blocked lookups (any mode): A -> 10.111.222.9 (tripwire), other types -> NODATA, verdict `blocked`.
- Sanctuary: trackers -> 10.111.222.9, others -> 0.0.0.0. Queue processing classifies and applies rules.

## anomalies
A job every 60 s checks the last 10 min and writes `kind='anomaly'` events (`category` = rule, `note` = evidence ids).
De-duplicated per app per rule per hour (`new_company`: per app per company per hour).
- `new_domains_burst`: >=5 domains the app never looked up before, in 10 min
- `night_background`: >=3 background (fg=0) lookups between 00:00 and 06:00 local time
- `new_company`: first contact with a tracker company
- `tripwire_retry`: >=2 tripwire hits after a block rule applying to the app
```
call("anomalies", {"since_ms":1760000000000})   // default: last 24 h; watched apps only unless include_unwatched
  -> {"anomalies":[{"id":901,"ts":...,"kind":"anomaly","package":"com.example.game","category":"new_company",
                    "company":"AppsFlyer","note":"880,884","rule":"new_company","evidence":[880,884]}]}
```

## chat
A grounded chatbot with no AI model: works offline, on every phone, without an API key. Answers come only from
on-device data; every sentence cites event ids, and a validator drops any sentence whose ids, apps, companies,
times or numbers do not match FACTS (`dropped` counts them). Both turns are stored in the `chat` table.
```
call("chat", {"text":"What did Chrome do today?"})
  -> {"sentences":[{"text":"Chrome fern made 212 lookups today, reaching 41 distinct domains.","cites":[930,929,928,927,926]},
                   {"text":"Chrome fern was last seen at 14:05.","cites":[930]}],
      "facts":{"intent":"app_summary","app":{...},"time":{"from_ms":...,"to_ms":...,"label":"today"},"data":{...}},
      "intent":"app_summary","engine":"template","dropped":0}
call("chat_history", {"limit":50}) -> {"turns":[{"id":1,"ts":...,"role":"user","text":"..."},
    {"id":2,"role":"bot","text":"...","cites":"930,929","engine":"template","latency_ms":12,"sentences":"[{\"text\":\"...\",\"cites\":[930]}]"}]}
call("chat_clear") -> {"ok":true}
```
- Intents: app_summary, top_apps, top_companies, when_contacted, what_happened_recently, night_report, blocked_report,
  bytes_report, compare, anomaly_report, help, limits, watch_app, unwatch_app, out_of_scope.
- Time words: today, yesterday, last night, last hour, this week, hh:mm (default: the past day).
- Species name: "<label> <kingdom>", kingdom = fern|mushroom|coral|vine from the low two bits of the seed's first byte.
- Not-watched app: says so, gives traffic byte totals if any, and returns `"suggest_watch":"<package>"`.
- "watch X" / "stop watching X": returns `"confirm_action":{"method":"watch"|"unwatch","packages":[...]}`.
  The engine never changes the selection itself; the UI calls the method after the user taps.
- Byte totals come from NetworkStatsManager (traffic table) and carry no event ids.
- Results include `latency_ms`. Errors: `missing_text`.

## cloud AI (optional, user's own key)
There is no on-device AI. Logs stay on the device; a cloud request is never automatic and only sends the payload the
user was shown. Provider: Anthropic Messages API, default model `claude-opus-5-5`, 20 s timeout.
```
call("ai_key", {"key":"sk-ant-..."}) -> {"ok":true,"state":"ready"}     // stored encrypted (Android Keystore AES-GCM), never echoed
call("ai_key", {"clear":true})       -> {"ok":true,"state":"no_key"}
call("ai_status")                    -> {"state":"no_key|ready|offline","model":"claude-opus-5-5"}
call("ai_settings", {"model":"claude-opus-5-5"}) -> {"model":"claude-opus-5-5"}
call("ai_preview", {"text":"What did Chrome do today?"})
  -> {"preview_id":"5f0c...","model":"claude-opus-5-5","payload_bytes":2210,"request_bytes":3604,
      "payload":{"question":"...","intent":"app_summary","timezone":"Europe/Tbilisi","facts":{...},"evidence":[...]},
      "request":{...exact body that would be POSTed, without the key header...},
      "intent":"app_summary","template":[{"text":"...","cites":[...]}]}
call("ai_ask", {"preview_id":"5f0c..."})
  -> {"sentences":[{"text":"...","cites":[930,929]}],"facts":{...payload...},"intent":"app_summary",
      "engine":"cloud","model":"claude-opus-5-5","dropped":0,"latency_ms":2140}
  -> or the template answer with "engine":"template","fallback_reason":"no_key|offline|http_401|timeout|network_error|refusal|max_tokens|invalid_json|validation_failed_<n>"
```
- `state` is `ready` only with a stored key AND a validated non-VPN network; it is mirrored in `state.ai`.
- Payload: question, intent, time zone, the Step 8 FACTS, and the evidence rows (id, time, kind, app, domain, company,
  verdict); max 50 event rows, max 8 KB; `*_local` time strings sit next to timestamps. No raw DB, no full logs.
- Preview ids are single use and expire after 10 min. `ai_ask` without one: `{"error":"preview_required"}`.
- The model must return `{"sentences":[{"text","cites"}]}` citing ids from the payload. The Step 8 validator runs on the
  reply; more than one dropped sentence -> template answer.
- Each send writes one `kind='ai'` event: `note` = `bytes=<n> model=<m> latency_ms=<ms> status=<s>`. The payload is never stored.
- The key lives only in the Keystore-encrypted file (no-backup dir): never in the DB, events, logs or `export_db`.

## packet types
`assets/config/packet_types.json` is the only definition of packet type ids, labels, colors, rules (ports are read from
the file) and `observed` (can the DNS-only engine see it; `observed_note:"tripwire only"` for TCP types).
Every packet row (event and queue, live / Sanctuary / simulate) gets one `ptype`:
tracker > most specific matching protocol rule (named host > ports > protocol; ties by file order) > `other`.
Unknown ids render in `neutral` (#9AA0A6).
```
call("packet_types") -> {"version":2,"neutral":"#9AA0A6","doh_hosts":[...],
  "types":[{"id":"dns","label":"DNS query","color":"#4FC3F7","rule":"UDP/TCP 53 to our resolver","observed":true,
            "match":{"proto":["udp","tcp"],"ports":["53"]}}, ...16 types]}
```

## terrarium (data only; the UI draws)
`assets/config/terrarium.json`: `stage_thresholds` (one entry per stage), `weights` (`default`, per type),
`count_os_packets`, `default_preset`, `presets_index`. A preset's own `stage_thresholds` wins.
growth = sum(count * weight); stage = highest stage whose threshold <= growth (1-based).
Counts = event rows with ptype (sim=0 unless simulating) + Sanctuary queue rows not yet processed + OS packet totals.
```
call("terrarium", {"app_id":"com.android.chrome"})      // app_id optional: all watched apps
  -> {"apps":[{"app_id":"com.android.chrome","label":"Chrome","preset_id":"preset07",
       "packet_counts_by_type":{"dns":120,"tracker":31,"https":0,...every type...,"os_packets":5400,"other":0},
       "total_packets":5551,"growth":236.0,"stage":3,"stage_count":3,"next_threshold":null,"stage_shown":2,
       "sanctuary_pending":0}],"stage_thresholds":[0,50,200]}
call("presets") -> {"presets":[{"id":"preset01","name":"Ink Cap","file":"organisms/preset01/preset.json",
                     "preset":{"id":"preset01","name":"Ink Cap","kingdom":"mushroom","palette":[...],"shape":{...},...}}, ...25],
                   "default_preset":"preset01"}
call("terrarium_ack", {"app_id":"com.android.chrome","stage":3}) -> {"ok":true}     // sets organism.stage_shown
call("set_preset", {"app_id":"com.android.chrome","preset_id":"preset12"}) -> {"ok":true,"app_id":"...","preset_id":"preset12"}
```
- Errors: `unknown_app`, `no_organism`, `unknown_preset`, `not_watched`, `missing_*`. `watch` with an unknown preset id
  returns `{"error":"unknown_preset"}` and changes nothing.
- Event `{"type":"terrarium_update","app_id":"...","stage":2,"stage_shown":1,"counts":{...}}`: at most once per second per
  app, and at once when the stage changes.

## simulation
```
call("simulate", {"script":"demo1","speed":1}) -> {"ok":true,"mode":"simulate","script":"demo1","rows":87,"packages":[...]}
call("simulate", {"stop":true})                -> {"ok":true,"mode":"online"}     // back to the live network mode
```
- Replays `assets/sim/<script>.json` through the real classifier, block rules, PacketTyper and DB writer; every row has `sim=1`.
- Script: `{"name","apps":[{"package","label"}],"events":[{"dt_ms","package","domain","fg","kind":"dns|tripwire|block","port"?,"ptype"?,"at_local"?:"03:12:00"}]}`.
  `at_local` backdates a row (e.g. a night burst). A script `block` writes a block event and applies only to the replay; your rules are untouched.
- While `state.mode == "simulate"`: stats, garden, anomalies and chat include sim rows and treat the scripted packages as
  watched for display (`app.watched` is unchanged). Otherwise sim rows are excluded and the validator rejects sim cites.
- The VPN keeps using the live network mode during a simulation. Speed 0.25..50.

## tools
```
call("export_db")               -> {"ok":true,"path":"/storage/emulated/0/Android/data/app.mycelium/files/mycelium-1760000000000.db","bytes":524288}
call("wipe", {"confirm":true})  -> {"ok":true}     // without confirm: {"error":"confirm_required"}
```
`wipe` deletes event, queue, traffic and chat rows; app selection, rules and organisms stay. It emits `{"type":"wiped"}` on `events`.

## unknown method
```
call("nope") -> {"error":"unknown_method"}
```
