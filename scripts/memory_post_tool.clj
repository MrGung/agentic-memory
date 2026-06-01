#!/usr/bin/env bb
(require '[cheshire.core :as json]
         '[babashka.sqlite3 :as sqlite])

(def db-path (or (System/getenv "MEMORY_DB")
                 (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(when (.exists (java.io.File. db-path))
  (let [raw    (slurp *in*)
        input  (try (json/parse-string raw true) (catch Exception _ nil))
        tool   (or (:toolName input) "unknown")
        result (:result input)
        now    (str (java.time.Instant/now))
        db     (sqlite/open db-path)]
    (sqlite/execute! db
      ["INSERT INTO events (id, session, type, data, timestamp, transaction_time, valid_time)
        VALUES (?, 'copilot-cli', 'tool-result', ?, ?, ?, ?)"
       (str (java.util.UUID/randomUUID))
       (pr-str {:tool tool :result result :source :copilot-cli})
       now
       now
       now])
    (sqlite/close db)))
