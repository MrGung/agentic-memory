(ns core
  (:require [clojure.string :as str]
            [events :as events]
            [llm :as llm]
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

(defn -main [& _]
  (events/init-db!)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(println "\nGraceful shutdown (Ctrl+C).")))
  (println "Agentic Memory gestartet. Tippe 'exit' zum Beenden.")
  (loop []
    (print "you> ")
    (flush)
    (when-let [line (read-line)]
      (when-not (= "exit" (str/trim line))
        (process-user-message! line)
        (recur))))
  (println "Bye!"))
