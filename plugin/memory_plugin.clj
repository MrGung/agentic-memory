#!/usr/bin/env bb
(require '[babashka.pods :as pods]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(let [home      (System/getProperty "user.home")
      exe-sfx   (if (str/includes? (System/getProperty "os.name") "Windows") ".exe" "")
      local-pod (str home "/.babashka/pods/repository/org.babashka/go-sqlite3/0.3.13/pod-babashka-go-sqlite3" exe-sfx)]
  (try (pods/load-pod 'pod.babashka.go-sqlite3 {:version "0.3.13"})
       (catch Exception _ (pods/load-pod [local-pod]))))
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def db-path
  (or (System/getenv "MEMORY_DB")
      (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(def session-id "copilot-cli")

(defn- now []
  (str (java.time.Instant/now)))

(defn- ensure-db-parent! []
  (when-let [parent (.getParentFile (java.io.File. db-path))]
    (.mkdirs parent)))

(defn- ensure-db! []
  (ensure-db-parent!)
  (sqlite/execute! db-path
                   ["CREATE TABLE IF NOT EXISTS events (
                         id               TEXT PRIMARY KEY,
                         session          TEXT NOT NULL,
                         type             TEXT NOT NULL,
                         data             TEXT NOT NULL,
                         timestamp        TEXT,
                         transaction_time TEXT,
                         valid_time       TEXT
                        )"])
  (sqlite/execute! db-path ["CREATE INDEX IF NOT EXISTS idx_events_session ON events (session)"])
  (sqlite/execute! db-path ["CREATE INDEX IF NOT EXISTS idx_events_type ON events (type)"])
  (sqlite/execute! db-path ["CREATE INDEX IF NOT EXISTS idx_events_valid_time ON events (valid_time)"])
  (try (sqlite/execute! db-path ["ALTER TABLE events ADD COLUMN transaction_time TEXT"]) (catch Exception _ nil))
  (try (sqlite/execute! db-path ["ALTER TABLE events ADD COLUMN valid_time TEXT"]) (catch Exception _ nil))
  (sqlite/execute! db-path ["UPDATE events SET transaction_time = COALESCE(transaction_time, timestamp)"])
  (sqlite/execute! db-path ["UPDATE events SET valid_time = COALESCE(valid_time, timestamp, transaction_time)"]))

(defn- like-pattern [query]
  (str "%"
       (-> (or query "")
           str/lower-case
           (str/replace "\\" "\\\\")
           (str/replace "%" "\\%")
           (str/replace "_" "\\_"))
       "%"))

(defn- parse-data [value]
  (try
    (edn/read-string value)
    (catch Exception _
      value)))

(defn- memory-search [{:keys [query cross_session event_type]}]
  (let [conditions (cond-> [["LOWER(data) LIKE ? ESCAPE '\\'" (like-pattern query)]]
                     (not cross_session) (conj ["session = ?" session-id])
                     event_type (conj ["type = ?" event_type]))
        sql        (into [(str "SELECT type, data, COALESCE(valid_time, timestamp) AS timestamp "
                               "FROM events WHERE "
                               (str/join " AND " (map first conditions))
                               " ORDER BY COALESCE(valid_time, timestamp) DESC LIMIT 15")]
                         (map second conditions))
        rows       (sqlite/query db-path sql)]
    {:matches (mapv (fn [row]
                      {:type      (:type row)
                       :timestamp (:timestamp row)
                       :data      (parse-data (:data row))})
                    rows)
     :count   (count rows)}))

(defn- memory-add [{:keys [text source]}]
  (let [now (now)]
    (sqlite/execute! db-path
                     ["INSERT INTO events (id, session, type, data, timestamp, transaction_time, valid_time)
                        VALUES (?, ?, 'long-term-memory', ?, ?, ?, ?)"
                      (str (java.util.UUID/randomUUID))
                      session-id
                      (pr-str {:text        (or text "")
                               :source      (keyword (or source "copilot-cli"))
                               :promoted-at now})
                      now
                      now
                      now])
    {:saved true :text text}))

(defn- memory-list []
  (let [rows (sqlite/query db-path
                           ["SELECT data, COALESCE(valid_time, timestamp) AS timestamp
                             FROM events
                             WHERE type = 'long-term-memory'
                             ORDER BY COALESCE(valid_time, timestamp) DESC
                             LIMIT 50"])]
    {:entries (mapv (fn [row]
                      {:timestamp (:timestamp row)
                       :data      (let [parsed (parse-data (:data row))]
                                    (if (string? parsed)
                                      {:text parsed}
                                      parsed))})
                    rows)
     :count   (count rows)}))

(defn- session-end! []
  (let [now (now)]
    (sqlite/execute! db-path
                     ["INSERT INTO events (id, session, type, data, timestamp, transaction_time, valid_time)
                        VALUES (?, ?, 'session-end', ?, ?, ?, ?)"
                      (str (java.util.UUID/randomUUID))
                      session-id
                      (pr-str {:source :copilot-cli})
                      now
                      now
                      now])
    {:done true :hint "Run 'b run dream' for memory consolidation"}))

(def tool-definitions
  [{:name        "memory_search"
    :description "Search past events and long-term memory. Use cross_session=true to search across all sessions."
    :inputSchema {:type       "object"
                  :properties {:query         {:type "string" :description "Search query"}
                               :cross_session {:type "boolean" :description "Search across all sessions"}
                               :event_type    {:type "string" :description "Filter by event type"}}
                  :required   ["query"]}}
   {:name        "memory_add"
    :description "Save a fact, decision or preference to long-term memory."
    :inputSchema {:type       "object"
                  :properties {:text   {:type "string" :description "The fact or decision to remember"}
                               :source {:type "string" :description "Source identifier"}}
                  :required   ["text"]}}
   {:name        "memory_list"
    :description "List all entries in long-term memory."
    :inputSchema {:type "object" :properties {} :required []}}
   {:name        "memory_session_end"
    :description "Mark session as ended, prepare for dream consolidation."
    :inputSchema {:type "object" :properties {} :required []}}])

(defn- dispatch [method params]
  (case method
    "initialize" {:protocolVersion "2024-11-05"
                  :capabilities    {:tools {}}
                  :serverInfo      {:name "agentic-memory" :version "1.0.0"}}
    "tools/list" {:tools tool-definitions}
    "tools/call" (let [tool-name (:name params)
                       args      (or (:arguments params) {})]
                   (case tool-name
                     "memory_search" (memory-search args)
                     "memory_add" (memory-add args)
                     "memory_list" (memory-list)
                     "memory_session_end" (session-end!)
                     {:error (str "Unknown tool: " tool-name)}))
    "notifications/initialized" nil
    {:error (str "Unknown method: " method)}))

(ensure-db!)

(loop []
  (when-let [line (read-line)]
    (when-let [request (try
                         (json/parse-string line true)
                         (catch Exception _
                           nil))]
      (let [id     (:id request)
            method (:method request)
            params (or (:params request) {})]
        (when method
          (when-let [result (dispatch method params)]
            (let [response (if (:error result)
                             {:jsonrpc "2.0" :id id :error {:code -32601 :message (:error result)}}
                             {:jsonrpc "2.0" :id id :result result})]
              (println (json/generate-string response))
              (flush))))))
    (recur)))
