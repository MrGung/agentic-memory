(ns events
  (:require [babashka.sqlite3 :as sqlite]
            [clojure.edn :as edn])
  (:import [java.time Instant]
           [java.util UUID]))

(def ^:private db-path "memory.db")

(def ^:private conn (atom nil))

(defn- get-conn []
  (when (nil? @conn)
    (reset! conn (sqlite/open db-path)))
  @conn)

(defn init-db! []
  (sqlite/execute!
   (get-conn)
   ["CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, type TEXT NOT NULL, data TEXT NOT NULL, timestamp TEXT NOT NULL)"]))

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

(defn get-events []
  (mapv row->event
        (sqlite/query (get-conn) ["SELECT id, type, data, timestamp FROM events ORDER BY timestamp ASC"])))

(defn get-events-by-type [event-type]
  (mapv row->event
        (sqlite/query (get-conn) ["SELECT id, type, data, timestamp FROM events WHERE type = ? ORDER BY timestamp ASC"
                                  (name event-type)])))

(defn get-context-window [n]
  (->> (sqlite/query (get-conn) ["SELECT id, type, data, timestamp FROM events ORDER BY timestamp DESC LIMIT ?" n])
       (map row->event)
       reverse
       vec))
