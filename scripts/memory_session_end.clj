#!/usr/bin/env bb
(require '[babashka.pods :as pods]
         '[clojure.string :as str])

(let [home      (System/getProperty "user.home")
      exe-sfx   (if (str/includes? (System/getProperty "os.name") "Windows") ".exe" "")
      local-pod (str home "/.babashka/pods/repository/org.babashka/go-sqlite3/0.3.13/pod-babashka-go-sqlite3" exe-sfx)]
  (try (pods/load-pod 'pod.babashka.go-sqlite3 {:version "0.3.13"})
       (catch Exception _ (pods/load-pod [local-pod]))))
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def db-path (or (System/getenv "MEMORY_DB")
                 (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(when (.exists (java.io.File. db-path))
  (let [now (str (java.time.Instant/now))]
    (sqlite/execute! db-path
      ["INSERT INTO events (id, session, type, data, timestamp, transaction_time, valid_time)
         VALUES (?, 'copilot-cli', 'session-end', ?, ?, ?, ?)"
       (str (java.util.UUID/randomUUID))
       (pr-str {:source :copilot-cli})
       now
       now
       now])
    (println "[memory] Session gespeichert. Starte 'bb run dream' für Dream-Konsolidierung.")))
