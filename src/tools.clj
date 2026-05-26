(ns tools
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [copilot-cli :as copilot]
            [events :as events]))

(def tool-definitions
  [{:name "shell_suggest"
    :description "Suggest a shell command for a given task using GitHub Copilot CLI"
    :parameters {:type "object"
                 :properties {:task {:type "string"
                                     :description "The task to accomplish"}}
                 :required ["task"]}}
   {:name "shell_execute"
    :description "Execute a shell command"
    :parameters {:type "object"
                 :properties {:command {:type "string"
                                        :description "The shell command to execute"}}
                 :required ["command"]}}
   {:name "memory_search"
    :description "Search past events in memory"
    :parameters {:type "object"
                 :properties {:query {:type "string"}
                              :event-type {:type "string"}}
                 :required ["query"]}}])

(def openai-tools
  (mapv (fn [tool] {:type "function" :function tool}) tool-definitions))

(defn- shell-execute [command]
  (try
    (let [result (p/shell {:out :string :err :string :continue true} command)]
      {:stdout (:out result) :stderr (:err result) :exit (:exit result)})
    (catch Exception e
      {:stdout "" :stderr (.getMessage e) :exit 1})))

(defn- memory-search [{:keys [query event-type]}]
  (let [events (if event-type
                 (events/get-events-by-type (keyword event-type))
                 (events/get-events))
        q (str/lower-case (or query ""))]
    {:matches
     (->> events
          (filter (fn [event]
                    (str/includes?
                     (str/lower-case (pr-str event))
                     q)))
          vec)}))

(defn- execute-tool [{:keys [name arguments]}]
  (case name
    "shell_suggest" (copilot/suggest-shell-command (or (:task arguments) ""))
    "shell_execute" (shell-execute (or (:command arguments) ""))
    "memory_search" (memory-search arguments)
    {:error (str "Unknown tool: " name)}))

(defn dispatch-tool-call! [tool-call]
  (let [tool-id (:id tool-call)
        name (get-in tool-call [:function :name])
        raw-args (or (get-in tool-call [:function :arguments]) "{}")
        arguments (try
                    (json/parse-string raw-args true)
                    (catch Exception _ {}))]
    (events/append-event! :tool-call {:tool-call-id tool-id
                                      :name name
                                      :arguments arguments})
    (let [result (execute-tool {:name name :arguments arguments})]
      (events/append-event! :tool-result {:tool-call-id tool-id
                                          :name name
                                          :result result})
      {:role "tool"
       :tool_call_id tool-id
       :content (json/generate-string result)})))
