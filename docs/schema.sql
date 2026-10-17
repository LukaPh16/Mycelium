CREATE TABLE app (package TEXT PRIMARY KEY, uid INTEGER, label TEXT, seed TEXT,
  system INTEGER DEFAULT 0, last_used INTEGER,
  watched INTEGER DEFAULT 0,   -- 1 = user selected this app for monitoring
  watched_ts INTEGER);         -- when it was selected (data before this is not expected)
CREATE TABLE event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,  -- citation id
  ts INTEGER NOT NULL,
  kind TEXT NOT NULL,      -- dns|tripwire|block|unblock|vpn|anomaly|ai
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
  note TEXT,               -- vpn/anomaly/debug text; anomaly evidence ids as comma list; ai: bytes, model, latency, status (never payload)
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
  cites TEXT, engine TEXT,   -- role user|bot; engine cloud|template
  latency_ms INTEGER,        -- bot: time to answer (added in DB version 2)
  sentences TEXT);           -- bot: JSON [{text,cites}] so each sentence keeps its citations (version 2)
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
