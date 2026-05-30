(ns export
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [events :as events]))

(defn export!
  "Exportiert alle Events der aktuellen Session in eine EDN-Datei."
  ([] (export! (str events/*session-id* ".edn")))
  ([file-path]
   (let [all-events  (events/get-events)
         export-data {:version  1
                      :session  events/*session-id*
                      :exported (.toString (java.time.Instant/now))
                      :events   all-events}]
     (with-open [w (io/writer file-path)]
       (pprint/pprint export-data w))
     (println (str "[export] " (count all-events) " Events exportiert nach: " file-path))
     file-path)))

(defn export-all!
  "Exportiert alle Sessions in eine EDN-Datei."
  ([] (export-all! "memory-backup.edn"))
  ([file-path]
   (let [all-events  (events/get-events-by-query "" nil true)
         export-data {:version  1
                      :session  :all
                      :exported (.toString (java.time.Instant/now))
                      :events   all-events}]
     (with-open [w (io/writer file-path)]
       (pprint/pprint export-data w))
     (println (str "[export] " (count all-events) " Events exportiert nach: " file-path))
     file-path)))

(defn import!
  "Importiert Events aus einer EDN-Datei in die aktuelle Session."
  [file-path]
  (if-not (.exists (io/file file-path))
    (println (str "[import] Datei nicht gefunden: " file-path))
    (let [data     (edn/read-string (slurp file-path))
          version  (:version data)
          evts     (:events data)
          imported (atom 0)]
      (when (not= version 1)
        (println (str "[import] Warnung: Unbekannte Version " version)))
      (doseq [event evts]
        (try
          (events/append-event! (:event/type event) (:event/data event))
          (swap! imported inc)
          (catch Exception e
            (println (str "[import] Fehler bei Event " (:event/id event) ": " (.getMessage e))))))
      (println (str "[import] " @imported " von " (count evts) " Events importiert aus: " file-path)))))
