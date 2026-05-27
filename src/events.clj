(ns events
  (:require [babashka.sqlite3 :as sqlite]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import [java.time Instant]
           [java.util UUID]))

(def ^:dynamic *db-path* "memory.db")

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
   ["CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, type TEXT NOT NULL, data TEXT NOT NULL, timestamp TEXT NOT NULL)"])
  nil)

(defn append-event! [event-type data]
  (let [id (UUID/randomUUID)
        timestamp (.toString (Instant/now))
        record {:event/id id
                :event/type event-type
                :event/timestamp timestamp
                :event/data data}]
    (sqlite/execute!
     (get-conn)
     ["INSERT INTO events (id, type, data, timestamp) VALUES (?, ?, ?, ?)"
      (str id)
      (name event-type)
      (pr-str data)
      timestamp])
    record))

(defn- row->event [row]
  {:event/id (UUID/fromString (:id row))
   :event/type (keyword (:type row))
   :event/timestamp (:timestamp row)
   :event/data (edn/read-string (:data row))})

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
        (sqlite/query (get-conn) ["SELECT id, type, data, timestamp FROM events ORDER BY timestamp ASC"])))

(defn get-events-by-type [event-type]
  (mapv row->event
        (sqlite/query (get-conn) ["SELECT id, type, data, timestamp FROM events WHERE type = ? ORDER BY timestamp ASC"
                                  (event-type->db-value event-type)])))

(defn get-events-by-query [query event-type]
  (let [event-type-value (event-type->db-value event-type)
        sql (str "SELECT id, type, data, timestamp FROM events "
                 "WHERE LOWER(data) LIKE ? ESCAPE '\\' "
                 (when event-type-value "AND type = ? ")
                 "ORDER BY timestamp ASC")
        params (cond-> [(like-pattern query)]
                 event-type-value (conj event-type-value))]
    (mapv row->event
          (sqlite/query (get-conn) (into [sql] params)))))

(defn get-context-window [n]
  (->> (sqlite/query (get-conn) ["SELECT id, type, data, timestamp FROM events ORDER BY timestamp DESC LIMIT ?" n])
       (map row->event)
       reverse
       vec))
