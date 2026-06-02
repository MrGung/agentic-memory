(ns ttl
  (:require [clojure.string :as str]
            [events :as events])
  (:import [java.time Instant]))

(def default-ttl-days
  {:user-message 30
   :assistant-message 30
   :tool-result 14
   :tool-call 14
   :session-end 7
   :summary 90
   :long-term-memory nil
   :repository-memory nil})

(defn get-env [k]
  (System/getenv k))

(defn- normalize-event-type [event-type]
  (cond
    (keyword? event-type) event-type
    (string? event-type) (keyword event-type)
    :else nil))

(defn- ttl-env-key [event-type]
  (str "MEMORY_TTL_"
       (-> event-type
           name
           (str/replace "-" "_")
           str/upper-case)))

(defn- parse-ttl-days [raw-value default-value]
  (let [trimmed (some-> raw-value str/trim)]
    (cond
      (nil? trimmed) default-value
      (str/blank? trimmed) nil
      :else (or (parse-long trimmed) default-value))))

(defn ttl-days [event-type]
  (let [normalized (normalize-event-type event-type)
        default-value (get default-ttl-days normalized)
        env-value (when normalized
                    (get-env (ttl-env-key normalized)))]
    (parse-ttl-days env-value default-value)))

(defn- event-timestamp [event]
  (some-> (or (:event/valid-time event)
              (:event/transaction-time event)
              (:event/timestamp event))
          Instant/parse))

(defn- cutoff [now days]
  (.minusSeconds now (* 86400 (long days))))

(defn expired?
  ([event]
   (expired? event (Instant/now)))
  ([event now]
   (let [event-type (normalize-event-type (:event/type event))
         days (ttl-days event-type)]
     (if (nil? days)
       false
       (if-let [event-time (event-timestamp event)]
         (not (.isAfter event-time (cutoff now days)))
         false)))))

(defn- configured-ttl-days []
  (into {}
        (map (fn [event-type]
               [event-type (ttl-days event-type)])
             (keys default-ttl-days))))

(defn purge-dry-run!
  ([] (purge-dry-run! (Instant/now)))
  ([now]
   (let [ttl-map (configured-ttl-days)
         expired-by-type (into {}
                               (for [[event-type days] ttl-map
                                     :when (some? days)]
                                 (let [count (events/count-events-before event-type (str (cutoff now days)))]
                                   [event-type count])))
         total-expired (reduce + 0 (vals expired-by-type))]
     {:ttl-days ttl-map
      :expired-by-type expired-by-type
      :total-expired total-expired})))

(defn purge!
  ([] (purge! (Instant/now)))
  ([now]
   (reduce-kv (fn [deleted event-type days]
                (if (nil? days)
                  deleted
                  (+ deleted
                     (events/delete-events-before! event-type (str (cutoff now days))))))
              0
              (configured-ttl-days))))

(defn ttl-status []
  (purge-dry-run!))
