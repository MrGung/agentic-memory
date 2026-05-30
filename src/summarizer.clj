(ns summarizer
  (:require [clojure.string :as str]
            [events :as events]
            [llm :as llm]))

(def ^:private summarize-threshold 50)

(defn- summarizable? [event]
  (contains? #{:user-message :assistant-message :tool-call :tool-result}
             (:event/type event)))

(defn- event->summary-line [event]
  (let [data (:event/data event)]
    (case (:event/type event)
      :user-message (str "User: " (:text data))
      :assistant-message (str "Agent: " (or (:text data) "(tool calls)"))
      :tool-call (str "Tool-Call: " (or (:name data)
                                         (get-in data [:tool-call :name])))
      :tool-result (str "Tool-Result: "
                        (let [result-str (pr-str (:result data))]
                          (subs result-str 0 (min 200 (count result-str)))))
      nil)))

(defn- build-summary-prompt [events]
  (let [lines (->> events
                   (filter summarizable?)
                   (map event->summary-line)
                   (remove nil?)
                   (str/join "\n"))]
    [{:role "system"
      :content "Du bist ein Assistent der Konversationen kompakt zusammenfasst. Fasse die folgende Konversation in maximal 5 Sätzen zusammen. Behalte wichtige Fakten, Entscheidungen und Ergebnisse."}
     {:role "user"
      :content (str "Zusammenzufassende Konversation:\n\n" lines)}]))

(defn needs-summarization? []
  (let [summary (events/get-latest-summary)
        all-events (if summary
                     (events/get-events-after (:event/timestamp summary))
                     (events/get-events))
        relevant (filter summarizable? all-events)]
    (>= (count relevant) summarize-threshold)))

(defn summarize! []
  (let [summary (events/get-latest-summary)
        all-events (if summary
                     (events/get-events-after (:event/timestamp summary))
                     (events/get-events))
        relevant (filter summarizable? all-events)]
    (when (seq relevant)
      (let [result (llm/chat (build-summary-prompt relevant))
            from-ts (:event/timestamp (first relevant))
            to-ts (:event/timestamp (last relevant))]
        (if (:ok result)
          (do
            (events/append-event! :summary
                                  {:text (:content result)
                                   :covers (count relevant)
                                   :from from-ts
                                   :to to-ts})
            (println (str "[memory] Zusammenfassung erstellt ("
                          (count relevant) " Events komprimiert).")))
          (println "[memory] Zusammenfassung fehlgeschlagen:" (:error result)))))))
