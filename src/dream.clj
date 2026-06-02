(ns dream
  (:require [cheshire.core :as json]
            [clojure.string :as str]
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

(defn- parse-json-array [text]
  (let [content (str/trim (or text ""))]
    (try
      (cond
        (str/blank? content) []
        :else
        (let [direct (json/parse-string content true)]
          (if (vector? direct)
            direct
            [])))
      (catch Exception _
        (try
          (if-let [arr (re-find #"(?s)\[.*\]" content)]
            (let [parsed (json/parse-string arr true)]
              (if (vector? parsed) parsed []))
            [])
          (catch Exception _
            []))))))

(defn- parse-json-object [text]
  (let [content (str/trim (or text ""))]
    (try
      (let [parsed (json/parse-string content true)]
        (if (map? parsed) parsed nil))
      (catch Exception _
        (try
          (when-let [obj (re-find #"(?s)\{.*\}" content)]
           (let [parsed (json/parse-string obj true)]
             (when (map? parsed) parsed)))
          (catch Exception _
           nil))))))

(defn conflicts-with-existing? [new-text existing-memory]
  (let [candidate (str/trim (or new-text ""))]
    (when (and (not (str/blank? candidate))
              (seq existing-memory))
      (let [numbered (->> existing-memory
                         (map-indexed (fn [idx {:keys [text]}]
                                        (str (inc idx) ". " text)))
                         (str/join "\n"))
           result (llm/chat [{:role "system"
                              :content (str "Prüfe, ob ein neuer Langzeit-Gedächtnis-Eintrag im "
                                            "Widerspruch zu bestehenden Einträgen steht. "
                                            "Antworte ausschließlich als JSON-Objekt: "
                                            "{\"conflict\": true/false, \"index\": <nr>, \"reason\": \"...\"}. "
                                            "Wenn kein Widerspruch besteht: {\"conflict\": false}.")}
                             {:role "user"
                              :content (str "Neuer Eintrag: " candidate "\n\n"
                                            "Bestehende Einträge:\n" numbered)}])]
        (when (:ok result)
          (let [{:keys [conflict index reason]} (parse-json-object (:content result))]
           (when (and (true? conflict) (number? index))
             (let [idx (dec (long index))
                   entry (when (and (>= idx 0) (< idx (count existing-memory)))
                           (nth existing-memory idx))
                   reason-text (str/trim (or reason ""))]
               (when entry
                 {:entry entry
                  :reason (if (str/blank? reason-text)
                            "Widersprüchliche Aussage"
                            reason-text)})))))))))

(defn detect-conflicts! []
  (let [entries (get-long-term-memory)]
    (if (< (count entries) 2)
      []
      (let [numbered (->> entries
                          (map-indexed (fn [idx {:keys [text]}]
                                         (str (inc idx) ". " text)))
                          (str/join "\n"))
            result (llm/chat [{:role "system"
                               :content (str "Analysiere Langzeit-Gedächtnis-Einträge auf Widersprüche. "
                                             "Antworte ausschließlich als JSON-Array im Format "
                                             "[{\"a\": <nr>, \"b\": <nr>, \"reason\": \"...\"}]. "
                                             "Wenn es keine Widersprüche gibt: []")}
                              {:role "user"
                               :content (str "Einträge:\n" numbered)}])]
        (if-not (:ok result)
          []
          (->> (parse-json-array (:content result))
               (keep (fn [{:keys [a b reason]}]
                       (let [idx-a (when (number? a) (dec (long a)))
                             idx-b (when (number? b) (dec (long b)))
                             entry-a (when (and (some? idx-a) (>= idx-a 0) (< idx-a (count entries)))
                                       (nth entries idx-a))
                             entry-b (when (and (some? idx-b) (>= idx-b 0) (< idx-b (count entries)))
                                       (nth entries idx-b))
                             reason-text (str/trim (or reason ""))]
                         (when (and entry-a entry-b (not= idx-a idx-b))
                           {:entry-a {:index (inc idx-a)
                                      :text (:text entry-a)}
                            :entry-b {:index (inc idx-b)
                                      :text (:text entry-b)}
                            :reason (if (str/blank? reason-text)
                                      "Widersprüchliche Aussage"
                                      reason-text)}))))
               vec))))))

(defn resolve-conflict-merge! [{:keys [entry-a entry-b]}]
  (let [text-a (str/trim (or (:text entry-a) ""))
        text-b (str/trim (or (:text entry-b) ""))]
    (when (and (not (str/blank? text-a))
               (not (str/blank? text-b)))
      (let [result (llm/chat [{:role "system"
                               :content (str "Führe zwei widersprüchliche Langzeit-Gedächtnis-Einträge "
                                             "zu einem präzisen, korrekten einzelnen Eintrag zusammen. "
                                             "Antworte nur mit dem finalen Satz.")}
                              {:role "user"
                               :content (str "Eintrag A: " text-a "\n"
                                             "Eintrag B: " text-b)}])
            merged-text (str/trim (or (:content result) ""))]
        (when (and (:ok result) (not (str/blank? merged-text)))
          (events/delete-long-term-memory-by-text! text-a)
          (events/delete-long-term-memory-by-text! text-b)
          (promote! merged-text :dream-merge)
          merged-text)))))
