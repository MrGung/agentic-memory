(ns events-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [events :as events])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-test-db [f]
  (let [dir (str (Files/createTempDirectory "agentic-memory-events-test"
                                            (make-array FileAttribute 0)))
        db-path (str (fs/path dir "test-memory.db"))]
    (binding [events/*db-path* db-path]
      (try
        (events/close-db!)
        (f)
        (finally
          (events/close-db!)
          (fs/delete-tree dir))))))

(use-fixtures :each with-test-db)

(deftest test-init-db
  (testing "Datenbank wird korrekt initialisiert"
    (is (nil? (events/init-db!)))))

(deftest test-append-and-get-events
  (testing "Event wird gespeichert und wieder abgerufen"
    (events/init-db!)
    (let [event (events/append-event! :user-message {:text "Hallo Welt"})
          stored-events (events/get-events)]
      (is (= :user-message (:event/type event)))
      (is (= "Hallo Welt" (get-in event [:event/data :text])))
      (is (uuid? (:event/id event)))
      (is (= [event] stored-events)))))

(deftest test-get-events-by-type
  (testing "Events nach Typ filtern"
    (events/init-db!)
    (events/append-event! :user-message {:text "test"})
    (events/append-event! :assistant-message {:text "antwort"})
    (let [user-events (events/get-events-by-type :user-message)]
      (is (= 1 (count user-events)))
      (is (every? #(= :user-message (:event/type %)) user-events)))))

(deftest test-get-events-by-query
  (testing "Query-basierte Suche liefert korrekte Ergebnisse"
    (events/init-db!)
    (events/append-event! :user-message {:text "Testnachricht"})
    (events/append-event! :user-message {:text "Eine weitere Nachricht"})
    (events/append-event! :assistant-message {:text "Testantwort"})
    (events/append-event! :assistant-message {:text "100% Treffer"})

    (let [results (events/get-events-by-query "Test" nil)]
      (is (= 2 (count results)))
      (is (every? #(str/includes? (pr-str (:event/data %)) "Test") results)))

    (let [user-results (events/get-events-by-query "Test" :user-message)]
      (is (= 1 (count user-results)))
      (is (= :user-message (:event/type (first user-results)))))

    (let [all-results (events/get-events-by-query nil nil)
          percent-results (events/get-events-by-query "%" nil)]
      (is (= 4 (count all-results)))
      (is (= ["100% Treffer"]
             (mapv #(get-in % [:event/data :text]) percent-results))))))

(deftest test-get-context-window
  (testing "Kontextfenster gibt maximal N Events zurück"
    (events/init-db!)
    (dotimes [i 5]
      (events/append-event! :user-message {:text (str "msg-" i)}))
    (let [window (events/get-context-window 3)]
      (is (= 3 (count window)))
      (is (= ["msg-2" "msg-3" "msg-4"]
             (mapv #(get-in % [:event/data :text]) window))))))
