(ns conflicts-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dream :as dream]
            [events :as events]
            [llm :as llm])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-test-db [f]
  (let [dir (str (Files/createTempDirectory "agentic-memory-conflicts-test"
                                            (make-array FileAttribute 0)))
        db-path (str (fs/path dir "test-memory.db"))]
    (binding [events/*db-path* db-path
              events/*session-id* "conflicts-test-session"]
      (try
        (events/close-db!)
        (f)
        (finally
          (events/close-db!)
          (fs/delete-tree dir))))))

(use-fixtures :each with-test-db)

(deftest detect-conflicts-with-too-few-entries
  (testing "Weniger als 2 Einträge liefert keine Konflikte"
    (events/init-db!)
    (dream/promote! "Nur ein Eintrag" :manual)
    (is (= [] (dream/detect-conflicts!)))))

(deftest detect-conflicts-llm-error
  (testing "LLM-Fehler liefert leere Liste ohne Crash"
    (events/init-db!)
    (dream/promote! "Wir nutzen CircleCI" :manual)
    (dream/promote! "Wir nutzen GitHub Actions" :manual)
    (with-redefs [llm/chat (fn [_] {:ok false :error "boom"})]
      (is (= [] (dream/detect-conflicts!))))))

(deftest detect-conflicts-invalid-json
  (testing "Ungültiges JSON liefert leere Liste ohne Crash"
    (events/init-db!)
    (dream/promote! "Wir nutzen CircleCI" :manual)
    (dream/promote! "Wir nutzen GitHub Actions" :manual)
    (with-redefs [llm/chat (fn [_] {:ok true :content "kein json"})]
      (is (= [] (dream/detect-conflicts!))))))

(deftest delete-long-term-memory-by-text-removes-entry
  (testing "delete-long-term-memory-by-text! löscht den korrekten Eintrag"
    (events/init-db!)
    (dream/promote! "Wir nutzen CircleCI für CI/CD" :manual)
    (dream/promote! "Wir nutzen GitHub Actions für CI/CD" :manual)
    (let [deleted (events/delete-long-term-memory-by-text! "CircleCI")
          memory (dream/get-long-term-memory)
          texts (set (map :text memory))]
      (is (= 1 deleted))
      (is (not (contains? texts "Wir nutzen CircleCI für CI/CD")))
      (is (contains? texts "Wir nutzen GitHub Actions für CI/CD")))))
