#!/usr/bin/env bb
(require '[babashka.sqlite3 :as sqlite])

(def db-path (or (System/getenv "MEMORY_DB")
                 (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(when (.exists (java.io.File. db-path))
  (let [now (str (java.time.Instant/now))
        db (sqlite/open db-path)]
    (sqlite/execute! db
      ["INSERT INTO events (id, session, type, data, timestamp, transaction_time, valid_time)
        VALUES (?, 'copilot-cli', 'session-end', ?, ?, ?, ?)"
       (str (java.util.UUID/randomUUID))
       (pr-str {:source :copilot-cli})
       now
       now
       now])
    (sqlite/close db)
    (println "[memory] Session gespeichert. Starte 'bb run dream' für Dream-Konsolidierung.")))
