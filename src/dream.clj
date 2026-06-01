(ns dream
  (:require [clojure.string :as str]
            [events :as events]
            [llm :as llm]))

(defn get-long-term-memory []
  (->> (events/get-events-by-query nil :long-term-memory true)
       (mapv (fn [event]
               {:text (get-in event [:event/data :text])
                :source (get-in event [:event/data :source])
                :session (:event/session event)
                :timestamp (:event/timestamp event)}))))

(defn- event->dream-line [event]
  (let [data (:event/data event)]
    (case (:event/type event)
      :user-message (str "User: " (:text data))
      :assistant-message (str "Assistant: " (or (:text data) "(tool-calls)"))
      :summary (str "Summary: " (:text data))
      :tool-call (str "Tool-Call: " (or (:name data)
                                        (get-in data [:tool-call :name])))
      :tool-result (str "Tool-Result: "
                        (subs (pr-str (:result data))
                              0
                              (min 200 (count (pr-str (:result data))))))
      nil)))

(defn- parse-numbered-suggestions [text]
  (let [lines (str/split-lines (or text ""))]
    (->> lines
         (map str/trim)
         (keep (fn [line]
                 (when-let [[_ item] (re-matches #"(?i)^(?:\d+[\.\)]|[-*])\s+(.+)$" line)]
                   (str/trim item))))
         (remove str/blank?)
         vec)))

(defn dream! []
  (let [events (events/get-events)
        transcript (->> events
                        (map event->dream-line)
                        (remove nil?)
                        (str/join "\n"))]
    (if (str/blank? transcript)
      []
      (let [result (llm/chat [{:role "system"
                               :content (str "Du analysierst eine Session und extrahierst nur langlebige, "
                                             "wiederverwendbare Fakten als Vorschläge fürs Langzeit-Gedächtnis. "
                                             "Antworte ausschließlich als nummerierte Liste.")}
                              {:role "user"
                               :content (str "Session-Verlauf:\n\n"
                                             transcript
                                             "\n\nGib maximal 7 präzise Vorschläge.")}])]
        (if-not (:ok result)
          []
          (let [suggestions (parse-numbered-suggestions (:content result))]
            (if (seq suggestions)
              suggestions
              (let [fallback (str/trim (or (:content result) ""))]
                (if (str/blank? fallback) [] [fallback])))))))))

(defn promote! [text source]
  (events/append-event! :long-term-memory {:text text
                                           :source source}))
