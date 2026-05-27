(ns tools
  (:require [babashka.process :refer [sh]]
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

(def ^:private allowed-commands
  #{"ls" "find" "cat" "grep" "echo" "curl" "git" "gh" "bb" "pwd" "env" "which" "date"})

(defn- allowed? [command]
  (let [program (first (str/split (str/trim command) #"\s+"))]
    (contains? allowed-commands program)))

(defn- confirm-execution! [command]
  (println (str "\n⚠️  Unbekanntes Programm — der Agent möchte folgenden Befehl ausführen:\n\n  " command "\n"))
  (print "Erlauben? [j/N] ")
  (flush)
  (let [answer (str/trim (or (read-line) ""))]
    (= "j" (str/lower-case answer))))

(defn- shell-execute [command]
  (let [permitted (or (allowed? command)
                      (confirm-execution! command))]
    (if-not permitted
      {:stdout ""
       :stderr (str "Ausführung abgelehnt: " command)
       :exit 1}
      (try
        (let [result (sh {:shell true :out :string :err :string} command)]
          {:stdout (:out result) :stderr (:err result) :exit (:exit result)})
        (catch Exception e
          {:stdout "" :stderr (.getMessage e) :exit 1})))))

(defn- memory-search [{:keys [query event-type]}]
  (let [normalized-event-type (cond
                                (keyword? event-type) event-type
                                (and (string? event-type) (not (str/blank? event-type))) (keyword event-type)
                                :else nil)]
    {:matches (events/get-events-by-query (or query "") normalized-event-type)}))

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
