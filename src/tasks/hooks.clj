(ns tasks.hooks
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [events :as events]))

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

(defn session-start
  "Lifecycle: sessionStart — Gibt Langzeit-Gedächtnis und Repository-Gedächtnis als Context aus."
  [& _]
  (when (db-exists?)
    (binding [events/*db-path*    (hook-db-path)
              events/*session-id* hook-session-id]
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
              (println (str "- " (get-in event [:event/data :text]))))))))))

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
