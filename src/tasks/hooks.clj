(ns tasks.hooks
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [events :as events]))

(def ^:private hook-session-id "copilot-cli")

(defn- hook-db-path []
  (or (System/getenv "MEMORY_DB")
      (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(defn- db-exists? []
  (.exists (java.io.File. (hook-db-path))))

(defn session-start
  "Lifecycle: sessionStart — Gibt Langzeit-Gedächtnis als Context aus."
  [& _]
  (when (db-exists?)
    (binding [events/*db-path*    (hook-db-path)
              events/*session-id* hook-session-id]
      (let [rows (events/get-recent-events-by-type :long-term-memory 20 true)]
        (when (seq rows)
          (println "## Langzeit-Gedächtnis\n")
          (doseq [event rows]
            (println (str "- " (get-in event [:event/data :text])))))))))

(defn post-tool
  "Lifecycle: postToolUse — Speichert Tool-Result als Event."
  [& _]
  (when (db-exists?)
    (let [raw   (slurp *in*)
          input (try (json/parse-string raw true) (catch Exception _ nil))
          tool  (or (:toolName input) "unknown")
          result (:result input)]
      (binding [events/*db-path*    (hook-db-path)
                events/*session-id* hook-session-id]
        (events/append-event! :tool-result {:tool   tool
                                            :result result
                                            :source :copilot-cli})))))

(defn session-end
  "Lifecycle: sessionEnd — Markiert Session als beendet."
  [& _]
  (when (db-exists?)
    (binding [events/*db-path*    (hook-db-path)
              events/*session-id* hook-session-id]
      (events/append-event! :session-end {:source :copilot-cli})
      (println "[memory] Session gespeichert. Starte 'bb run dream' für Dream-Konsolidierung."))))
