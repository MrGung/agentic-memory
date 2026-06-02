(ns dream-conflicts-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dream :as dream]
            [events :as events]
            [llm :as llm])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-test-db [f]
  (let [dir (str (Files/createTempDirectory "agentic-memory-dream-conflicts-test"
                                            (make-array FileAttribute 0)))
        db-path (str (fs/path dir "test-memory.db"))]
    (binding [events/*db-path* db-path
              events/*session-id* "dream-conflicts-test-session"]
      (try
        (events/close-db!)
        (f)
        (finally
          (events/close-db!)
          (fs/delete-tree dir))))))

(use-fixtures :each with-test-db)

(deftest conflicts-with-existing-empty-memory
  (testing "Leeres Gedächtnis ergibt keinen Konflikt"
    (is (nil? (dream/conflicts-with-existing? "Neuer Vorschlag" [])))))

(deftest conflicts-with-existing-no-conflict
  (testing "Kein Konflikt liefert nil"
    (with-redefs [llm/chat (fn [_]
                             {:ok true
                              :content "{\"conflict\": false}"})]
      (is (nil? (dream/conflicts-with-existing?
                 "Wir nutzen GitHub Actions"
                 [{:text "Wir nutzen CI/CD"}]))))))

(deftest conflicts-with-existing-llm-error
  (testing "LLM-Fehler liefert nil ohne Crash"
    (with-redefs [llm/chat (fn [_] {:ok false :error "boom"})]
      (is (nil? (dream/conflicts-with-existing?
                 "Wir nutzen GitHub Actions"
                 [{:text "Wir nutzen CircleCI"}]))))))

(deftest conflicts-with-existing-detects-conflict
  (testing "Konflikt liefert Eintrag und Grund"
    (let [existing [{:text "Wir nutzen CircleCI für CI/CD"}
                    {:text "Wir nutzen Jira"}]]
      (with-redefs [llm/chat (fn [_]
                               {:ok true
                                :content "{\"conflict\": true, \"index\": 1, \"reason\": \"Widersprüchliche CI/CD-Tools\"}"})]
        (is (= {:entry (first existing)
                :reason "Widersprüchliche CI/CD-Tools"}
               (dream/conflicts-with-existing? "Wir nutzen GitHub Actions für CI/CD"
                                               existing)))))))
