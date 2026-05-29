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
    :description "Search past events in memory. Use cross-session to search across all sessions."
    :parameters {:type "object"
                 :properties {:query         {:type "string"
                                              :description "The search query"}
                              :event-type    {:type "string"
                                              :description "Optional event type filter"}
                              :cross-session {:type "boolean"
                                              :description "If true, search across all sessions (global memory)"}}
                 :required ["query"]}}
   {:name "file_read"
    :description "Read the contents of a file at the given path"
    :parameters {:type "object"
                 :properties {:path {:type "string"
                                     :description "The file path to read"}}
                 :required ["path"]}}
   {:name "file_write"
    :description "Write content to a file at the given path"
    :parameters {:type "object"
                 :properties {:path    {:type "string"
                                        :description "The file path to write to"}
                              :content {:type "string"
                                        :description "The content to write"}}
                 :required ["path" "content"]}}])

(def openai-tools
  (mapv (fn [tool] {:type "function" :function tool}) tool-definitions))

(def ^:private default-allowed-commands
  #{"ls" "find" "cat" "grep" "echo" "curl" "git" "gh" "bb" "pwd" "env" "which" "date"})

(defn get-env
  "Wrapper around System/getenv to allow test-time substitution."
  [k]
  (System/getenv k))

(defn- load-from-file [path]
  (when (.exists (java.io.File. path))
    (let [cmds (set (remove str/blank? (map str/trim (str/split-lines (slurp path)))))]
      (when (seq cmds) cmds))))

(defn- load-allowed-commands []
  (or
    (when-let [env (get-env "SHELL_ALLOWED_COMMANDS")]
      (set (map str/trim (str/split env #","))))
    (load-from-file (or (get-env "SHELL_ALLOWED_COMMANDS_FILE") ".shell_allowed_commands"))
    default-allowed-commands))

(def ^:private allowed-commands
  (load-allowed-commands))

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

(defn- safe-path? [path]
  (let [canonical (-> (java.io.File. path) .getCanonicalPath)
        cwd       (-> (java.io.File. ".") .getCanonicalPath)]
    (str/starts-with? canonical cwd)))

(defn- file-read [path]
  (if-not (safe-path? path)
    {:content nil :error (str "Zugriff verweigert: " path) :exit 1}
    (try
      {:content (slurp path) :error nil :exit 0}
      (catch Exception e
        {:content nil :error (.getMessage e) :exit 1}))))

(defn- confirm-write! [path]
  (println (str "\n⚠️  Der Agent möchte in folgende Datei schreiben:\n\n  " path "\n"))
  (print "Erlauben? [j/N] ")
  (flush)
  (let [answer (str/trim (or (read-line) ""))]
    (= "j" (str/lower-case answer))))

(defn- file-write [path content]
  (cond
    (not (safe-path? path))
    {:written false :error (str "Zugriff verweigert: " path) :exit 1}

    (not (confirm-write! path))
    {:written false :error (str "Schreiben abgelehnt: " path) :exit 1}

    :else
    (try
      (spit path content)
      {:written true :error nil :exit 0}
      (catch Exception e
        {:written false :error (.getMessage e) :exit 1}))))

(defn- memory-search [{:keys [query event-type cross-session]}]
  (let [normalized-event-type (cond
                                (keyword? event-type) event-type
                                (and (string? event-type) (not (str/blank? event-type))) (keyword event-type)
                                :else nil)]
    {:matches (events/get-events-by-query (or query "") normalized-event-type (boolean cross-session))}))

(defn- execute-tool [{:keys [name arguments]}]
  (case name
    "shell_suggest" (copilot/suggest-shell-command (or (:task arguments) ""))
    "shell_execute" (shell-execute (or (:command arguments) ""))
    "memory_search" (memory-search arguments)
    "file_read"  (file-read  (or (:path arguments) ""))
    "file_write" (file-write (or (:path arguments) "") (or (:content arguments) ""))
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
