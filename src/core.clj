(ns core
  (:require [clojure.string :as str]
            [dream :as dream]
            [events :as events]
            [export :as export]
            [llm :as llm]
            [summarizer :as summarizer]
            [tools :as tools])
  (:gen-class))

(def ^:private system-prompt
  "You are an agentic memory assistant. Use tools when needed and return concise final answers.")

(defn- long-term-memory->message []
  (let [memory (dream/get-long-term-memory)
        content (if (seq memory)
                  (str "Langzeit-Gedächtnis (cross-session):\n"
                       (->> memory
                            (map-indexed (fn [idx {:keys [text source]}]
                                           (str (inc idx) ". [" (name source) "] " text)))
                            (str/join "\n")))
                  "Langzeit-Gedächtnis (cross-session): (leer)")]
    {:role "system" :content content}))

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
      :long-term-memory nil
      :tool-result {:role "tool"
                    :tool_call_id (:tool-call-id data)
                    :content (pr-str (:result data))}
      nil)))

(defn- context->messages [events]
  (->> events
       (map event->message)
       (remove nil?)
       (into [{:role "system" :content system-prompt}
              (long-term-memory->message)])
       vec))

(defn- parse-dream-selection [selection max-items]
  (let [trimmed (str/trim (or selection ""))]
    (cond
      (or (str/blank? trimmed)
          (= "none" (str/lower-case trimmed)))
      []

      (= "all" (str/lower-case trimmed))
      (vec (range 1 (inc max-items)))

      :else
      (->> (str/split trimmed #"[,\s]+")
           (keep parse-long)
           (filter #(<= 1 % max-items))
           distinct
           vec))))

(defn- run-dream! []
  (let [suggestions (dream/dream!)]
    (if-not (seq suggestions)
      (println "[dream] Keine Vorschläge.")
      (do
        (println "[dream] Vorschläge:")
        (doseq [[idx suggestion] (map-indexed vector suggestions)]
          (println (str "  " (inc idx) ". " suggestion)))
        (println "Speichern? Nummern (z. B. 1,3), 'all' oder Enter für none:")
        (print "dream> ")
        (flush)
        (let [selection (read-line)
              chosen (parse-dream-selection selection (count suggestions))]
          (if-not (seq chosen)
            (println "[dream] Nichts gespeichert.")
            (do
              (doseq [idx chosen]
                (dream/promote! (nth suggestions (dec idx)) :dream))
              (println (str "[dream] " (count chosen) " Einträge gespeichert.")))))))))

(defn- print-long-term-memory! []
  (let [memory (dream/get-long-term-memory)]
    (if-not (seq memory)
      (println "[memory] Langzeit-Gedächtnis ist leer.")
      (do
        (println "[memory] Langzeit-Gedächtnis:")
        (doseq [[idx {:keys [text source session timestamp]}] (map-indexed vector memory)]
          (println (str "  " (inc idx) ". [" (name source) "] "
                        text
                        "  (session: " session ", ts: " timestamp ")")))))))

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

              (= "dream" trimmed)
              (do
                (run-dream!)
                (recur))

              (= "memory" trimmed)
              (do
                (print-long-term-memory!)
                (recur))

              (= "promote" trimmed)
              (do
                (println "[memory] Bitte Text angeben: promote <text>")
                (recur))

              (str/starts-with? trimmed "promote ")
              (do
                (let [text (str/trim (subs trimmed 8))]
                  (if (str/blank? text)
                    (println "[memory] Bitte Text angeben: promote <text>")
                    (do
                      (dream/promote! text :manual)
                      (println "[memory] Eintrag gespeichert."))))
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

              (= "help" trimmed)
              (do
                (println "Verfügbare Befehle:")
                (println "  sessions        - Alle Sessions auflisten")
                (println "  stats           - Token-Statistiken der Session")
                (println "  stats all       - Token-Statistiken aller Sessions")
                (println "  summarize       - Memory-Kompression ausführen")
                (println "  dream           - Dream-Konsolidierung vorschlagen")
                (println "  promote <text>  - Text manuell ins Langzeit-Gedächtnis übernehmen")
                (println "  memory          - Langzeit-Gedächtnis anzeigen")
                (println "  export          - Session exportieren (session-id.edn)")
                (println "  export <datei>  - Session in Datei exportieren")
                (println "  export all      - Alle Sessions exportieren (memory-backup.edn)")
                (println "  import <datei>  - Events aus Datei importieren")
                (println "  exit            - Beenden")
                (recur))

              :else
              (do
                (process-user-message! trimmed)
                (recur))))))))))
