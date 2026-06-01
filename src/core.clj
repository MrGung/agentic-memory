(ns core
  (:require [clojure.string :as str]
            [events :as events]
            [export :as export]
            [llm :as llm]
            [summarizer :as summarizer]
            [tools :as tools])
  (:gen-class))

(def ^:private system-prompt
  "You are an agentic memory assistant. Use tools when needed and return concise final answers.")

(defn- event->message [event]
  (let [event-type (:event/type event)
        data (:event/data event)]
    (case event-type
      :user-message {:role "user" :content (:text data)}
      :assistant-message (let [tool-calls (seq (:tool-calls data))]
                           (cond-> {:role "assistant" :content (or (:text data) "")}
                             tool-calls (assoc :tool_calls (vec tool-calls))))
      :summary {:role "system"
                :content (str "Zusammenfassung früherer Konversation: "
                             (get-in event [:event/data :text]))}
      :tool-result {:role "tool"
                    :tool_call_id (:tool-call-id data)
                    :content (pr-str (:result data))}
      nil)))

(defn- context->messages [events]
  (->> events
       (map event->message)
       (remove nil?)
       (into [{:role "system" :content system-prompt}])
       vec))

(defn- process-user-message! [input]
  (events/append-event! :user-message {:text input})
  (when (summarizer/needs-summarization?)
    (summarizer/summarize!))
  (loop [messages (-> (events/get-context-window 20)
                      context->messages)
         iteration 0]
    (if (>= iteration 8)
      (do
        (events/append-event! :error {:source :core :message "Tool loop iteration limit reached"})
        (println "[error] Iteration limit reached."))
      (let [result (llm/chat-with-tools messages tools/openai-tools)]
        (if-not (:ok result)
          (println "[error]" (:error result))
          (let [assistant-message (:message result)
                content (or (:content assistant-message) "")
                tool-calls (vec (or (:tool_calls assistant-message) []))]
            (events/append-event! :assistant-message
                                  {:text content
                                   :tool-calls tool-calls})
            (if (seq tool-calls)
              (let [tool-messages (mapv tools/dispatch-tool-call! tool-calls)
                    next-messages (into messages (concat [{:role "assistant"
                                                          :content content
                                                          :tool_calls tool-calls}]
                                                        tool-messages))]
                (recur next-messages (inc iteration)))
              (println (if (str/blank? content) "[no content]" content)))))))))

(defn- print-stats! [cross-session?]
  (let [stats (events/get-usage-stats cross-session?)
        title (if cross-session?
               "📊 Alle Sessions (gesamt)"
               (str "📊 Session: " events/*session-id*))]
    (println "────────────────────────────────────────")
    (println title)
    (println "────────────────────────────────────────")
    (println (format "Requests:         %d" (:total-requests stats)))
    (println (format "Tokens gesamt:    %d" (:total-tokens stats)))
    (println (format "  ├ Prompt:       %d" (:total-prompt stats)))
    (println (format "  └ Completion:   %d" (:total-completion stats)))
    (println (format "Kontext ⌀:        %.1f Messages" (:avg-context-size stats)))
    (println (format "Kontext max:      %d Messages" (:max-context-size stats)))
    (when cross-session?
      (println "────────────────────────────────────────")
      (println "Pro Session:")
      (doseq [{:keys [session requests tokens]} (:by-session stats)]
        (println (format "  %-16s %4d req   %d tokens" session requests tokens))))
    (println "────────────────────────────────────────")))

(defn -main-dream [& _]
  (events/init-db!)
  (summarizer/summarize!))

(defn -main-memory [& _]
  (events/init-db!)
  (let [rows (events/get-recent-events-by-type :long-term-memory 20 true)]
    (if (seq rows)
      (do
        (println "## Langzeit-Gedächtnis")
        (println)
        (doseq [row rows]
          (println (str "- " (or (get-in row [:event/data :text])
                                 (pr-str (:event/data row)))))))
      (println "Keine Langzeit-Gedächtnis-Einträge gefunden."))))

(defn -main [& [session-name]]
  (let [session-id (or session-name (str (java.util.UUID/randomUUID)))]
    (binding [events/*session-id* session-id]
      (events/init-db!)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(println "\nGraceful shutdown (Ctrl+C).")))
      (println (str "Agentic Memory gestartet. Session: " session-id))
      (println "Tippe 'exit' zum Beenden, 'sessions' zum Auflisten aller Sessions.")
      (loop []
        (print "you> ")
        (flush)
        (when-let [line (read-line)]
          (let [trimmed (str/trim line)]
            (cond
              (= "exit" trimmed)
              nil

              (= "sessions" trimmed)
              (do
                (doseq [s (events/list-sessions)]
                  (println (str "  " (:session s)
                                "  Events: " (:event_count s)
                                "  Zuletzt: " (:last_active s))))
                (recur))

              (= "stats" trimmed)
              (do
                (print-stats! false)
                (recur))

              (= "stats all" trimmed)
              (do
                (print-stats! true)
                (recur))

              (= "summarize" trimmed)
              (do
                (summarizer/summarize!)
                (recur))

              (= "export" trimmed)
              (do
                (export/export!)
                (recur))

              (= "export all" trimmed)
              (do
                (export/export-all!)
                (recur))

              (str/starts-with? trimmed "export ")
              (do
                (export/export! (subs trimmed 7))
                (recur))

              (str/starts-with? trimmed "import ")
              (do
                (export/import! (subs trimmed 7))
                (recur))

              (str/starts-with? trimmed "history ")
              (do
                (let [event-type (keyword (subs trimmed 8))
                      history    (events/entity-history event-type)]
                  (if (empty? history)
                    (println (str "No events of type :" (name event-type) " found."))
                    (do
                      (println (str "\n📜 History for :" (name event-type) " (" (count history) " entries)\n"))
                      (doseq [{:keys [valid-time event]} history]
                        (println (str "  " valid-time "  " (pr-str (:event/data event))))))))
                (recur))

              (= "help" trimmed)
              (do
                (println "Verfügbare Befehle:")
                (println "  sessions        - Alle Sessions auflisten")
                (println "  stats           - Token-Statistiken der Session")
                (println "  stats all       - Token-Statistiken aller Sessions")
                (println "  summarize       - Memory-Kompression ausführen")
                (println "  export          - Session exportieren (session-id.edn)")
                (println "  export <datei>  - Session in Datei exportieren")
                (println "  export all      - Alle Sessions exportieren (memory-backup.edn)")
                (println "  import <datei>  - Events aus Datei importieren")
                (println "  history <typ>   - Verlauf für Event-Typ anzeigen")
                (println "  exit            - Beenden")
                (recur))

              :else
              (do
                (process-user-message! trimmed)
                (recur))))))))))
