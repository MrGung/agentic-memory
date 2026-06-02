(ns tasks.hooks
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [events :as events]
            [memory-config :as mcfg]))

(def ^:private hook-session-id "copilot-cli")

(defn- hook-db-path []
  (or (System/getenv "MEMORY_DB")
      (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(defn- db-exists? []
  (.exists (java.io.File. (hook-db-path))))

(defn- current-repo-id
  "Detects the current git repository and returns a stable repo-scope session-id,
  or nil if not in a git repo or remote origin is not configured."
  []
  (try
    (let [result (process/sh "git" "remote" "get-url" "origin")]
      (when (zero? (:exit result))
        (events/normalize-repo-url (:out result))))
    (catch Exception _ nil)))

(defn- count-memories-since
  "Returns the number of :long-term-memory and :repository-memory events
  written after the given ISO timestamp in the current session."
  [since-timestamp]
  (let [ltm  (events/get-events-after-type since-timestamp :long-term-memory)
        repo (events/get-events-after-type since-timestamp :repository-memory)]
    (+ (count ltm) (count repo))))

(defn- last-session-start-timestamp
  "Returns the timestamp of the most recent :session-start event, or nil."
  []
  (let [starts (events/get-recent-events-by-type :session-start 1 false)]
    (some-> starts first :event/timestamp)))

(defn session-start
  "Lifecycle: sessionStart — Gibt Langzeit-Gedächtnis und Repository-Gedächtnis als Context aus."
  [& _]
  (when (db-exists?)
    (binding [events/*db-path*    (hook-db-path)
              events/*session-id* hook-session-id]
      (events/append-event! :session-start {:source :copilot-cli})
      (let [ltm-rows (events/get-recent-events-by-type :long-term-memory 20 true)]
        (when (seq ltm-rows)
          (println "## Langzeit-Gedächtnis\n")
          (doseq [event ltm-rows]
            (println (str "- " (get-in event [:event/data :text]))))))
      (when-let [repo-id (current-repo-id)]
        (let [repo-rows (events/get-repository-memory repo-id)]
          (when (seq repo-rows)
            (println "\n## Repository-Gedächtnis\n")
            (doseq [event repo-rows]
              (println (str "- " (get-in event [:event/data :text])))))))
      (when (#{:advisory :strict} (mcfg/enforcement-level))
        (println "\n💡 Nutze `memory_add` oder `memory_add_repo` um wichtige Erkenntnisse dieser Session zu speichern.")))))

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
  "Lifecycle: sessionEnd — Markiert Session als beendet. Prüft Enforcement-Level."
  [& _]
  (when (db-exists?)
    (binding [events/*db-path*    (hook-db-path)
              events/*session-id* hook-session-id]
      (let [level (mcfg/enforcement-level)]
        (events/append-event! :session-end {:source :copilot-cli})
        (if (#{:advisory :strict} level)
          (let [since     (last-session-start-timestamp)
                n-saved   (if since (count-memories-since since) 0)
                saved?    (pos? n-saved)]
            (if saved?
              (println (str "[memory] Session gespeichert. " n-saved " Erinnerung(en) gespeichert. Starte 'bb run dream' für Dream-Konsolidierung."))
              (if (= level :strict)
                (do
                  (println "[memory] ❌ STRICT: Keine Erinnerungen in dieser Session gespeichert! Session wird nicht als erfolgreich markiert.")
                  (System/exit 1))
                (println "[memory] ⚠️  Session gespeichert, aber keine neuen Erinnerungen gespeichert. Starte 'bb run dream' für Dream-Konsolidierung."))))
          (println "[memory] Session gespeichert. Starte 'bb run dream' für Dream-Konsolidierung."))))))
