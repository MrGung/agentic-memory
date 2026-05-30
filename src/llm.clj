(ns llm
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [events :as events]))

(def ^:private api-url "https://models.inference.ai.azure.com/chat/completions")

(defn- model-name []
  (or (System/getenv "GITHUB_MODELS_MODEL") "gpt-4o-mini"))

(defn- token []
  (System/getenv "GITHUB_TOKEN"))

(defn- request! [body]
  (if-let [github-token (token)]
    (http/post api-url
               {:headers {"Authorization" (str "Bearer " github-token)
                          "Content-Type" "application/json"}
                :body (json/generate-string body)
                :as :json
                :coerce :always
                :throw-exceptions true})
    (throw (ex-info "Missing GITHUB_TOKEN environment variable" {:type :missing-token}))))

(defn- log-error! [message details]
  (try
    (events/append-event! :error {:source :llm :message message :details details})
    (catch Exception _ nil)))

(defn- log-usage! [response request-type messages]
  (try
    (let [prompt-tokens (or (get-in response [:body :usage :prompt_tokens]) 0)
         completion-tokens (or (get-in response [:body :usage :completion_tokens]) 0)
         total-tokens (or (get-in response [:body :usage :total_tokens]) 0)]
     (events/append-event! :llm-usage {:model (or (get-in response [:body :model]) (model-name))
                                       :prompt-tokens prompt-tokens
                                       :completion-tokens completion-tokens
                                       :total-tokens total-tokens
                                       :context-messages (count messages)
                                       :request-type request-type}))
    (catch Exception _ nil)))

(defn chat
  ([messages] (chat messages {}))
  ([messages {:keys [model]}]
   (try
     (let [selected-model (or model (model-name))
          response (request! {:model selected-model
                              :messages messages})
           message (get-in response [:body :choices 0 :message :content])]
      (log-usage! response :chat messages)
      {:ok true :content message :raw response})
     (catch Exception e
       (let [details (or (ex-data e) {:message (.getMessage e)})]
         (log-error! "LLM chat request failed" details)
         {:ok false :error (.getMessage e) :details details})))))

(defn chat-with-tools
  ([messages tools] (chat-with-tools messages tools {}))
  ([messages tools {:keys [model]}]
   (try
     (let [selected-model (or model (model-name))
          response (request! {:model selected-model
                              :messages messages
                              :tools tools})
           message (get-in response [:body :choices 0 :message])]
      (log-usage! response :chat-with-tools messages)
      {:ok true :message message :raw response})
     (catch Exception e
       (let [details (or (ex-data e) {:message (.getMessage e)})]
         (log-error! "LLM tool chat request failed" details)
         {:ok false :error (.getMessage e) :details details})))))
