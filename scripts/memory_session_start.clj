#!/usr/bin/env bb
(require '[babashka.pods :as pods]
         '[clojure.edn :as edn]
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
  (let [rows (sqlite/query db-path
               ["SELECT data FROM events
                 WHERE type = 'long-term-memory'
                 ORDER BY COALESCE(transaction_time, timestamp) DESC
                 LIMIT 20"])]
    (when (seq rows)
      (println "## Langzeit-Gedächtnis\n")
      (doseq [row rows]
        (let [data (edn/read-string (:data row))]
          (println (str "- " (:text data))))))))
