(ns dream-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dream :as dream]
            [events :as events])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-test-db [f]
  (let [dir (str (Files/createTempDirectory "agentic-memory-dream-test"
                                            (make-array FileAttribute 0)))
        db-path (str (fs/path dir "test-memory.db"))]
    (binding [events/*db-path* db-path
              events/*session-id* "dream-test-session-default"]
      (try
        (events/close-db!)
        (f)
        (finally
          (events/close-db!)
          (fs/delete-tree dir))))))

(use-fixtures :each with-test-db)

(deftest get-long-term-memory-empty
  (testing "Leeres Langzeit-Gedächtnis liefert leere Liste"
    (events/init-db!)
    (is (= [] (dream/get-long-term-memory)))))

(deftest promote-roundtrip
  (testing "promote! + get-long-term-memory speichert und lädt Eintrag"
    (events/init-db!)
    (dream/promote! "Nutze bb test für die Test-Suite" :manual)
    (let [memory (dream/get-long-term-memory)]
      (is (= 1 (count memory)))
      (is (= "Nutze bb test für die Test-Suite" (:text (first memory))))
      (is (= :manual (:source (first memory)))))))

(deftest multiple-sources
  (testing "Mehrere Einträge mit verschiedenen Sources werden geladen"
    (events/init-db!)
    (binding [events/*session-id* "session-a"]
      (dream/promote! "A manuell" :manual))
    (binding [events/*session-id* "session-b"]
      (dream/promote! "B geträumt" :dream))
    (let [memory (dream/get-long-term-memory)]
      (is (= 2 (count memory)))
      (is (= #{{:text "A manuell" :source :manual}
               {:text "B geträumt" :source :dream}}
             (set (map #(select-keys % [:text :source]) memory)))))))
