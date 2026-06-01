#!/usr/bin/env bb
(require '[babashka.sqlite3 :as sqlite]
         '[clojure.edn :as edn])

(def db-path (or (System/getenv "MEMORY_DB")
                 (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(when (.exists (java.io.File. db-path))
  (let [db   (sqlite/open db-path)
        rows (sqlite/query db
               ["SELECT data FROM events
                 WHERE type = 'long-term-memory'
                 ORDER BY COALESCE(transaction_time, timestamp) DESC
                 LIMIT 20"])]
    (when (seq rows)
      (println "## Langzeit-Gedächtnis\n")
      (doseq [row rows]
        (let [data (edn/read-string (:data row))]
          (println (str "- " (:text data))))))
    (sqlite/close db)))
