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

(declare migrate-db!)

(defn init-db! []
  (sqlite/execute!
   (get-conn)
   ["CREATE TABLE IF NOT EXISTS events (
     id               TEXT PRIMARY KEY,
     session          TEXT NOT NULL,
     type             TEXT NOT NULL,
     data             TEXT NOT NULL,
     timestamp        TEXT,
     transaction_time TEXT,
     valid_time       TEXT
    )"])
    (sqlite/execute!
     (get-conn)
     ["CREATE INDEX IF NOT EXISTS idx_session ON events (session)"])
    (sqlite/execute!
     (get-conn)
     ["CREATE INDEX IF NOT EXISTS idx_type ON events (type)"])
    (sqlite/execute!
     (get-conn)
     ["CREATE INDEX IF NOT EXISTS idx_valid_time ON events (valid_time)"])
    (migrate-db!)
    nil)

(defn migrate-db! []
    (try (sqlite/execute! (get-conn) ["ALTER TABLE events ADD COLUMN transaction_time TEXT"]) (catch Exception _ nil))
    (try (sqlite/execute! (get-conn) ["ALTER TABLE events ADD COLUMN valid_time TEXT"]) (catch Exception _ nil))
    (sqlite/execute! (get-conn) ["UPDATE events SET transaction_time = timestamp WHERE transaction_time IS NULL"])
    (sqlite/execute! (get-conn) ["UPDATE events SET valid_time = timestamp WHERE valid_time IS NULL"])
    nil)

(defn append-event!
    ([event-type data] (append-event! event-type data (Instant/now)))
    ([event-type data valid-time]
     (let [id               (UUID/randomUUID)
        transaction-time (Instant/now)
        transaction-str  (str transaction-time)
        valid-time-str   (str valid-time)
        record           {:event/id               id
                          :event/session          *session-id*
                          :event/type             event-type
                          :event/timestamp        transaction-str
                          :event/transaction-time transaction-str
                          :event/valid-time       valid-time-str
                          :event/data             data}]
    (sqlite/execute!
     (get-conn)
     ["INSERT INTO events (id, session, type, data, timestamp, transaction_time, valid_time) VALUES (?, ?, ?, ?, ?, ?, ?)"
      (str id)
      *session-id*
      (name event-type)
      (pr-str data)
      transaction-str
      transaction-str
      valid-time-str])
    record)))

(defn- row->event [row]
    (let [transaction-time (or (:transaction_time row) (:timestamp row))
       valid-time       (or (:valid_time row) (:timestamp row) transaction-time)]
      {:event/id               (UUID/fromString (:id row))
    :event/session          (:session row)
    :event/type             (keyword (:type row))
    :event/timestamp        transaction-time
    :event/transaction-time transaction-time
    :event/valid-time       valid-time
    :event/data             (edn/read-string (:data row))}))

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

(defn- build-query
    [{:keys [session type search limit order after]
      :or   {order :asc}}]
    (let [conditions (cond-> []
                    session (conj ["session = ?" session])
                    type    (conj ["type = ?" (event-type->db-value type)])
                    search  (conj ["LOWER(data) LIKE ? ESCAPE '\\'" (like-pattern search)])
                    after   (conj ["COALESCE(valid_time, timestamp) > ?" (str after)]))
       where-sql  (when (seq conditions)
                    (str "WHERE " (str/join " AND " (map first conditions))))
       sql-parts  (cond-> ["SELECT id, session, type, data, transaction_time, valid_time, timestamp FROM events"
                           where-sql
                           (str "ORDER BY COALESCE(valid_time, timestamp) " (if (= order :desc) "DESC" "ASC"))]
                    limit (conj "LIMIT ?"))
       params     (cond-> (mapv second conditions)
                    limit (conj limit))]
      (into [(str/join " " (remove nil? sql-parts))] params)))

(defn get-events []
    (mapv row->event
       (sqlite/query (get-conn)
                     (build-query {:session *session-id*
                                   :order   :asc}))))

(defn get-latest-summary []
    (first
     (mapv row->event
        (sqlite/query (get-conn)
                      (build-query {:session *session-id*
                                    :type    :summary
                                    :order   :desc
                                    :limit   1})))))

(defn get-events-after [timestamp]
    (mapv row->event
       (sqlite/query (get-conn)
                     (build-query {:session *session-id*
                                   :after   timestamp
                                   :order   :asc}))))

(defn get-events-by-type [event-type]
    (mapv row->event
       (sqlite/query (get-conn)
                     (build-query {:session *session-id*
                                   :type    event-type
                                   :order   :asc}))))

(defn get-recent-events-by-type
    ([event-type]
     (get-recent-events-by-type event-type 20 true))
    ([event-type limit]
     (get-recent-events-by-type event-type limit true))
    ([event-type limit cross-session?]
     (mapv row->event
        (sqlite/query (get-conn)
                      (build-query (cond-> {:type  event-type
                                            :order :desc
                                            :limit limit}
                                     (not cross-session?) (assoc :session *session-id*)))))))

(defn get-events-by-query
    ([query event-type]
     (get-events-by-query query event-type false))
    ([query event-type cross-session?]
     (mapv row->event
        (sqlite/query (get-conn)
                      (build-query (cond-> {:search query
                                            :type   event-type
                                            :order  :asc}
                                     (not cross-session?) (assoc :session *session-id*)))))))

(defn get-context-window [n]
    (if-let [summary (get-latest-summary)]
      (let [newer-events (get-events-after (or (:event/valid-time summary)
                                            (:event/timestamp summary)))]
     (into [summary] newer-events))
      (->> (sqlite/query (get-conn)
                      (build-query {:session *session-id*
                                    :order   :desc
                                    :limit   n}))
        (map row->event)
        reverse
        vec)))

(defn entity-history
    "Returns all events of a given type with full temporal metadata.
     Inspired by xt/entity-history."
    [event-type]
    (mapv (fn [row]
         {:transaction-time (or (:transaction_time row) (:timestamp row))
          :valid-time       (or (:valid_time row) (:timestamp row))
          :event            (row->event row)})
       (sqlite/query (get-conn)
                     (build-query {:session *session-id*
                                   :type    event-type
                                   :order   :asc}))))

(defn list-sessions []
    (->> (sqlite/query (get-conn)
                    ["SELECT session,
                             MIN(COALESCE(valid_time, transaction_time, timestamp)) as started,
                             MAX(COALESCE(valid_time, transaction_time, timestamp)) as last_active,
                             COUNT(*) as event_count
                      FROM events
                      GROUP BY session
                      ORDER BY last_active DESC"])
      (mapv identity)))

(defn delete-session! [session-id]
    (sqlite/execute!
     (get-conn)
     ["DELETE FROM events WHERE session = ?" session-id]))

(defn get-usage-stats
    ([] (get-usage-stats false))
    ([cross-session?]
     (let [rows (sqlite/query (get-conn)
                           (build-query (cond-> {:type  "llm-usage"
                                                 :order :asc}
                                          (not cross-session?) (assoc :session *session-id*))))
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
