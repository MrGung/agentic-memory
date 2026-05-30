(ns summarizer-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [events :as events]
            [summarizer :as summarizer]))

(use-fixtures :each
  (fn [f]
    (let [db-file (java.io.File. "memory.db")]
      (when (.exists db-file)
        (.delete db-file)))
    (binding [events/*session-id* "test-session-default"]
      (events/init-db!)
      (f))
    (when (.exists (java.io.File. "memory.db"))
      (.delete (java.io.File. "memory.db")))))

(deftest test-needs-summarization-false
  (testing "Keine Summarization bei wenigen Events"
    (is (not (summarizer/needs-summarization?)))))

(deftest test-summarizable-filter
  (testing "Nur relevante Event-Typen werden zusammengefasst"
    (let [evts [{:event/type :user-message      :event/data {:text "test"}}
                {:event/type :llm-usage         :event/data {:total-tokens 100}}
                {:event/type :assistant-message :event/data {:text "antwort"}}
                {:event/type :error             :event/data {:message "fehler"}}]]
      (is (= 2 (count (filter #'summarizer/summarizable? evts)))))))
