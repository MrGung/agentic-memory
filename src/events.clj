(ns events
  (:require [babashka.sqlite3 :as sqlite]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import [java.time Instant]
           [java.util UUID]))

(def ^:dynamic *db-path* "memory.db")

(def ^:dynamic *session-id*
  (str (java.util.UUID/randomUUID)))

(def ^:private conn (atom nil))
(def ^:private conn-db-path (atom nil))

(defn close-db! []
  (when-let [db @conn]
    (sqlite/close db))
  (reset! conn nil)
  (reset! conn-db-path nil)
  nil)

(defn- get-conn []
  (when (or (nil? @conn)
            (not= @conn-db-path *db-path*))
    (close-db!)
    (reset! conn (sqlite/open *db-path*))
    (reset! conn-db-path *db-path*))
  @conn)

(defn init-db! []
  (sqlite/execute!
   (get-conn)
   ["CREATE TABLE IF NOT EXISTS events (
      id        TEXT PRIMARY KEY,
      session   TEXT NOT NULL,
      type      TEXT NOT NULL,
      data      TEXT NOT NULL,
      timestamp TEXT NOT NULL
    )"])
  (sqlite/execute!
   (get-conn)
   ["CREATE INDEX IF NOT EXISTS idx_session ON events (session)"])
  (sqlite/execute!
   (get-conn)
   ["CREATE INDEX IF NOT EXISTS idx_type ON events (type)"])
  nil)

(defn append-event! [event-type data]
  (let [id        (UUID/randomUUID)
        timestamp (.toString (Instant/now))
        record    {:event/id        id
                   :event/session   *session-id*
                   :event/type      event-type
                   :event/timestamp timestamp
                   :event/data      data}]
    (sqlite/execute!
     (get-conn)
     ["INSERT INTO events (id, session, type, data, timestamp) VALUES (?, ?, ?, ?, ?)"
      (str id)
      *session-id*
      (name event-type)
      (pr-str data)
      timestamp])
    record))

(defn- row->event [row]
  {:event/id        (UUID/fromString (:id row))
   :event/session   (:session row)
   :event/type      (keyword (:type row))
   :event/timestamp (:timestamp row)
   :event/data      (edn/read-string (:data row))})

(defn- event-type->db-value [event-type]
  (cond
    (keyword? event-type) (name event-type)
    (string? event-type) event-type
    :else (some-> event-type name)))

(defn- like-pattern [query]
  (str "%"
       (-> (or query "")
           str/lower-case
           (str/replace "\\" "\\\\")
           (str/replace "%" "\\%")
           (str/replace "_" "\\_"))
       "%"))

(defn get-events []
  (mapv row->event
        (sqlite/query (get-conn)
          ["SELECT id, session, type, data, timestamp FROM events
            WHERE session = ? ORDER BY timestamp ASC"
           *session-id*])))

(defn get-latest-summary []
  (first
   (mapv row->event
         (sqlite/query (get-conn)
           ["SELECT id, session, type, data, timestamp FROM events
             WHERE session = ? AND type = 'summary'
             ORDER BY timestamp DESC LIMIT 1"
            *session-id*]))))

(defn get-events-after [timestamp]
  (mapv row->event
        (sqlite/query (get-conn)
          ["SELECT id, session, type, data, timestamp FROM events
            WHERE session = ? AND timestamp > ?
            ORDER BY timestamp ASC"
           *session-id* timestamp])))

(defn get-events-by-type [event-type]
  (mapv row->event
        (sqlite/query (get-conn)
          ["SELECT id, session, type, data, timestamp FROM events
            WHERE session = ? AND type = ? ORDER BY timestamp ASC"
           *session-id*
           (event-type->db-value event-type)])))

(defn get-events-by-query
  ([query event-type]
   (get-events-by-query query event-type false))
  ([query event-type cross-session?]
   (let [event-type-value (event-type->db-value event-type)
         sql (str "SELECT id, session, type, data, timestamp FROM events "
                  "WHERE LOWER(data) LIKE ? ESCAPE '\\' "
                  (when-not cross-session? "AND session = ? ")
                  (when event-type-value "AND type = ? ")
                  "ORDER BY timestamp ASC")
         params (cond-> [(like-pattern query)]
                  (not cross-session?) (conj *session-id*)
                  event-type-value     (conj event-type-value))]
     (mapv row->event
           (sqlite/query (get-conn) (into [sql] params))))))

(defn get-context-window [n]
  (if-let [summary (get-latest-summary)]
    (let [newer-events (get-events-after (:event/timestamp summary))]
      (into [summary] newer-events))
    (->> (sqlite/query (get-conn)
           ["SELECT id, session, type, data, timestamp FROM events
             WHERE session = ? ORDER BY timestamp DESC LIMIT ?"
            *session-id* n])
         (map row->event)
         reverse
         vec)))

(defn list-sessions []
  (->> (sqlite/query (get-conn)
         ["SELECT session, MIN(timestamp) as started, MAX(timestamp) as last_active, COUNT(*) as event_count
           FROM events GROUP BY session ORDER BY last_active DESC"])
       (mapv identity)))

(defn delete-session! [session-id]
  (sqlite/execute!
   (get-conn)
   ["DELETE FROM events WHERE session = ?" session-id]))

(defn get-usage-stats
  ([] (get-usage-stats false))
  ([cross-session?]
   (let [sql (str "SELECT session, data FROM events WHERE type = ? "
                  (when-not cross-session? "AND session = ?"))
         params (cond-> ["llm-usage"]
                  (not cross-session?) (conj *session-id*))
         rows (sqlite/query (get-conn) (into [sql] params))
         usage-events (map (fn [row]
                             (assoc row :data (edn/read-string (:data row))))
                           rows)
         total-requests (count usage-events)
         total-tokens (reduce + 0 (map #(or (get-in % [:data :total-tokens]) 0) usage-events))
         total-prompt (reduce + 0 (map #(or (get-in % [:data :prompt-tokens]) 0) usage-events))
         total-completion (reduce + 0 (map #(or (get-in % [:data :completion-tokens]) 0) usage-events))
         context-sizes (map #(or (get-in % [:data :context-messages]) 0) usage-events)
         avg-context-size (if (pos? total-requests)
                            (/ (double (reduce + 0 context-sizes)) total-requests)
                            0.0)
         max-context-size (if (seq context-sizes) (apply max context-sizes) 0)
         by-session (->> usage-events
                         (group-by :session)
                         (map (fn [[session session-events]]
                                {:session session
                                 :requests (count session-events)
                                 :tokens (reduce + 0 (map #(or (get-in % [:data :total-tokens]) 0)
                                                          session-events))}))
                         (sort-by (juxt (comp - :tokens) :session))
                         vec)]
     {:total-requests total-requests
      :total-tokens total-tokens
      :total-prompt total-prompt
      :total-completion total-completion
      :avg-context-size avg-context-size
      :max-context-size max-context-size
      :by-session by-session})))
