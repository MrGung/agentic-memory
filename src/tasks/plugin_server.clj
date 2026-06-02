(ns tasks.plugin-server
  (:require [cheshire.core :as json]
            [events :as events]))

(def ^:private plugin-session-id "copilot-cli")

(defn- plugin-db-path []
  (or (System/getenv "MEMORY_DB")
      (str (System/getProperty "user.home") "/.agentic-memory/memory.db")))

(defn- ensure-db-parent! []
  (when-let [parent (.getParentFile (java.io.File. (plugin-db-path)))]
    (.mkdirs parent)))

(defn- memory-search [{:keys [query cross_session event_type]}]
  (let [results (events/get-events-by-query query
                                            (when event_type (keyword event_type))
                                            (boolean cross_session))
        recent  (take 15 (reverse results))]
    {:matches (mapv (fn [evt]
                      {:type      (name (:event/type evt))
                       :timestamp (or (:event/valid-time evt) (:event/timestamp evt))
                       :data      (:event/data evt)})
                    recent)
     :count   (count recent)}))

(defn- memory-add [{:keys [text source]}]
  (events/append-event! :long-term-memory
                        {:text        (or text "")
                         :source      (keyword (or source "copilot-cli"))
                         :promoted-at (str (java.time.Instant/now))})
  {:saved true :text text})

(defn- memory-list []
  (let [results (events/get-recent-events-by-type :long-term-memory 50 true)]
    {:entries (mapv (fn [evt]
                      {:timestamp (or (:event/valid-time evt) (:event/timestamp evt))
                       :data      (:event/data evt)})
                    results)
     :count   (count results)}))

(defn- session-end! []
  (events/append-event! :session-end {:source :copilot-cli})
  {:done true :hint "Run 'bb run dream' for memory consolidation"})

(def ^:private tool-definitions
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
    "initialize"              {:protocolVersion "2024-11-05"
                               :capabilities    {:tools {}}
                               :serverInfo      {:name "agentic-memory" :version "1.0.0"}}
    "tools/list"              {:tools tool-definitions}
    "tools/call"              (let [tool-name (:name params)
                                    args      (or (:arguments params) {})]
                                (case tool-name
                                  "memory_search"      (memory-search args)
                                  "memory_add"         (memory-add args)
                                  "memory_list"        (memory-list)
                                  "memory_session_end" (session-end!)
                                  {:error (str "Unknown tool: " tool-name)}))
    "notifications/initialized" nil
    {:error (str "Unknown method: " method)}))

(defn serve
  "MCP stdio server — reads JSON-RPC from stdin, writes responses to stdout."
  [& _]
  (ensure-db-parent!)
  (binding [events/*db-path*    (plugin-db-path)
            events/*session-id* plugin-session-id]
    (events/init-db!)
    (loop []
      (when-let [line (read-line)]
        (when-let [request (try
                             (json/parse-string line true)
                             (catch Exception _ nil))]
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
        (recur)))))
