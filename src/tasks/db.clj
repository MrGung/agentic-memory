(ns tasks.db
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [pod-loader]))

(defn- db-path []
  (or (System/getenv "MEMORY_DB")
      (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(defn- parse-args [args]
  (let [n-arg    (first (filter #(re-matches #"\d+" %) args))
        sess-arg (first (filter #(str/starts-with? % "session=") args))]
    {:n       (if n-arg (Integer/parseInt n-arg) 20)
     :session (when sess-arg (subs sess-arg 8))}))

(defn- ensure-pod! []
  (pod-loader/load-sqlite-pod!)
  (require '[pod.babashka.go-sqlite3]))

(defn- query! [path sql]
  ((resolve 'pod.babashka.go-sqlite3/query) path sql))

(defn show!
  "Zeigt die letzten N Events aus der DB an. Optionale Filter: n, session=id."
  [args]
  (let [{:keys [n session]} (parse-args args)
        path (db-path)]
    (if-not (fs/exists? path)
      (println (str "DB nicht gefunden: " path))
      (do
        (ensure-pod!)
        (let [base  (-> (h/select :id :session :type [[:coalesce :valid_time :timestamp] :ts] :data)
                        (h/from :events)
                        (h/order-by [[:coalesce :valid_time :timestamp] :desc])
                        (h/limit n))
              q    (cond-> base session (h/where [:= :session session]))
              rows (query! path (sql/format q))]
          (println (format "%-36s %-16s %-20s %-30s %s" "ID" "SESSION" "TYPE" "TIMESTAMP" "DATA"))
          (println (apply str (repeat 120 "-")))
          (doseq [row rows]
            (println (format "%-36s %-16s %-20s %-30s %s"
                             (str (:id row))
                             (subs (str (:session row) "                ") 0 16)
                             (str (:type row))
                             (str (:ts row))
                             (let [d (str (:data row))]
                               (if (> (count d) 60) (str (subs d 0 57) "...") d)))))
          (println (str "\n" (count rows) " Events (DB: " path ")")))))))

(defn- start-input-watcher!
  "Starts a background future that sets refresh-flag on Enter (\\r/\\n) or F5 (ESC [15~).
  Falls back to line-buffered stdin (Enter only) if JLine3 is unavailable."
  [refresh-flag]
  (future
    (try
      (let [tb-cls   (Class/forName "org.jline.terminal.TerminalBuilder")
            builder  (.invoke (.getMethod tb-cls "builder" (make-array Class 0))
                               nil (object-array 0))
            _        (.system builder true)
            _        (.dumb builder false)
            terminal (.build builder)
            _        (.enterRawMode terminal)
            reader   (.reader terminal)]
        (loop []
          (let [c (.read reader)]
            (when (not= c -1)
              (cond
                (or (= c 10) (= c 13))
                (reset! refresh-flag true)
                ;; ESC — accumulate escape sequence until ~ or letter (max 10 chars)
                (= c 27)
                (let [sb (StringBuilder.)]
                  (loop [i 0]
                    (when (< i 10)
                      (let [nc (.read reader)]
                        (when (not= nc -1)
                          (.append sb (char nc))
                          (when-not (or (= nc (int \~))
                                        (Character/isLetter (char nc)))
                            (recur (inc i)))))))
                  (when (= (str sb) "[15~")
                    (reset! refresh-flag true))))
              (recur)))))
      (catch Exception _
        ;; JLine3 not available — fall back to line-buffered stdin (Enter only)
        (let [rdr (java.io.BufferedReader. (java.io.InputStreamReader. System/in))]
          (loop []
            (when (.readLine rdr)
              (reset! refresh-flag true)
              (recur))))))))

(defn- interruptible-sleep!
  "Sleeps up to ms milliseconds, waking early when refresh-flag is true."
  [ms refresh-flag]
  (loop [elapsed 0]
    (when (and (< elapsed ms) (not (deref refresh-flag)))
      (Thread/sleep 100)
      (recur (+ elapsed 100)))))

(defn watch!
  "Beobachtet die DB live (wie tail -f). Optionaler Filter: session=id.
  Enter oder F5 triggern Sofort-Aktualisierung."
  [args]
  (let [{:keys [session]} (parse-args args)
        path         (db-path)
        refresh-flag (atom false)]
    (if-not (fs/exists? path)
      (println (str "DB nicht gefunden: " path))
      (do
        (ensure-pod!)
        (start-input-watcher! refresh-flag)
        (println (str "Beobachte DB: " path (when session (str " [session=" session "]"))))
        (println "Ctrl+C zum Beenden. Enter oder F5 zum Sofort-Aktualisieren.\n")
        (loop [last-ts ""]
          (reset! refresh-flag false)
          (let [base  (-> (h/select :id :session :type [[:coalesce :valid_time :timestamp] :ts] :data)
                          (h/from :events)
                          (h/where [:> [:coalesce :valid_time :timestamp] last-ts])
                          (h/order-by [[:coalesce :valid_time :timestamp] :asc]))
                q    (cond-> base session (h/where [:= :session session]))
                rows (query! path (sql/format q))
                new-ts (if (seq rows) (:ts (last rows)) last-ts)]
            (doseq [row rows]
              (println (format "[%s] %-20s %-16s %s"
                               (str (:ts row))
                               (str (:type row))
                               (subs (str (:session row) "                ") 0 16)
                               (let [d (str (:data row))]
                                 (if (> (count d) 70) (str (subs d 0 67) "...") d)))))
            (interruptible-sleep! 2000 refresh-flag)
            (recur new-ts)))))))

(defn -main [& _] (show! *command-line-args*))

(defn -main-watch [& _] (watch! *command-line-args*))
