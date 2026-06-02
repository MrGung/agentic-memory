(ns tasks.plugin-server
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [events :as events]
            [memory-config :as mcfg]))

(def ^:private plugin-session-id "copilot-cli")

;; Tracks how many memory saves (long-term + repo) happened since server start.
;; Used for enforcement-level checks in memory_session_end.
(def ^:private memories-saved-count (atom 0))

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
  (swap! memories-saved-count inc)
  {:saved true :text text})

(defn- detect-repo-id
  "Tries to detect the current git repo-scope session-id via git remote origin.
  Returns nil when not in a git repo or origin is not set."
  []
  (try
    (let [result (process/sh "git" "remote" "get-url" "origin")]
      (when (zero? (:exit result))
        (events/normalize-repo-url (:out result))))
    (catch Exception _ nil)))

(defn- resolve-repo-id
  "Returns a repo-scope session-id from the given URL string (explicit)
  or falls back to auto-detection via git."
  [repository]
  (if (str/blank? repository)
    (detect-repo-id)
    (events/normalize-repo-url repository)))

(defn- memory-add-repo [{:keys [text source repository]}]
  (let [repo-id (resolve-repo-id repository)]
    (if (nil? repo-id)
      {:saved false :error "Could not determine repository. Pass 'repository' param with the git remote URL."}
      (do
        (binding [events/*session-id* repo-id]
          (events/append-event! :repository-memory
                                {:text        (or text "")
                                 :source      (keyword (or source "copilot-cli"))
                                 :promoted-at (str (java.time.Instant/now))
                                 :repository  repo-id}))
        (swap! memories-saved-count inc)
        {:saved true :text text :repository repo-id}))))

(defn- memory-list-repo [{:keys [repository]}]
  (let [repo-id (resolve-repo-id repository)]
    (if (nil? repo-id)
      {:entries [] :count 0 :error "Could not determine repository."}
      (let [results (events/get-repository-memory repo-id)]
        {:entries    (mapv (fn [evt]
                             {:timestamp  (or (:event/valid-time evt) (:event/timestamp evt))
                              :repository (get-in evt [:event/data :repository])
                              :data       (:event/data evt)})
                           results)
         :count      (count results)
         :repository repo-id}))))

(defn- memory-list []
  (let [results (events/get-recent-events-by-type :long-term-memory 50 true)]
    {:entries (mapv (fn [evt]
                      {:timestamp (or (:event/valid-time evt) (:event/timestamp evt))
                       :data      (:event/data evt)})
                    results)
     :count   (count results)}))

(defn- session-end! []
  (events/append-event! :session-end {:source :copilot-cli})
  (let [level  (mcfg/enforcement-level)
        n      @memories-saved-count
        saved? (pos? n)]
    (case level
      :passive  {:done true :hint "Run 'bb run dream' for memory consolidation"}
      :advisory (if saved?
                  {:done    true
                   :saved   n
                   :hint    "Run 'bb run dream' for memory consolidation"}
                  {:done    true
                   :warning "No memories saved this session. Consider using memory_add or memory_add_repo."
                   :hint    "Run 'bb run dream' for memory consolidation"})
      :strict   (if saved?
                  {:done  true
                   :saved n
                   :hint  "Run 'bb run dream' for memory consolidation"}
                  {:done    false
                   :blocked true
                   :message "STRICT enforcement: save at least one memory with memory_add or memory_add_repo before ending the session."}))))

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
    :inputSchema {:type "object" :properties {} :required []}}
   {:name        "memory_add_repo"
    :description "Save a repository-scoped fact that applies to all clones and worktrees of the same repo."
    :inputSchema {:type       "object"
                  :properties {:text       {:type "string" :description "The fact to remember for this repo"}
                               :source     {:type "string" :description "Source identifier"}
                               :repository {:type "string" :description "Git remote URL (auto-detected if omitted)"}}
                  :required   ["text"]}}
   {:name        "memory_list_repo"
    :description "List all repository-scoped memory entries for the current (or specified) git repository."
    :inputSchema {:type       "object"
                  :properties {:repository {:type "string" :description "Git remote URL (auto-detected if omitted)"}}
                  :required   []}}])

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
                                  "memory_add_repo"    (memory-add-repo args)
                                  "memory_list_repo"   (memory-list-repo args)
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
