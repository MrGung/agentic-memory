(ns events-test
  (:require [babashka.fs :as fs]
            [babashka.sqlite3 :as sqlite]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [events :as events])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- with-test-db [f]
  (let [dir (str (Files/createTempDirectory "agentic-memory-events-test"
                                            (make-array FileAttribute 0)))
        db-path (str (fs/path dir "test-memory.db"))]
    (binding [events/*db-path* db-path
              events/*session-id* "test-session-default"]
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

(deftest test-get-recent-events-by-type
  (testing "Neueste Events nach Typ werden absteigend geliefert"
    (events/init-db!)
    (events/append-event! :long-term-memory {:text "älter"} (Instant/parse "2026-05-30T10:00:00Z"))
    (events/append-event! :long-term-memory {:text "neuer"} (Instant/parse "2026-05-30T11:00:00Z"))
    (let [rows (events/get-recent-events-by-type :long-term-memory 1 true)]
      (is (= 1 (count rows)))
      (is (= "neuer" (get-in (first rows) [:event/data :text])))))
  (testing "Session-Filter kann aktiviert werden"
    (events/init-db!)
    (binding [events/*session-id* "session-a"]
      (events/append-event! :long-term-memory {:text "aus-a"}))
    (binding [events/*session-id* "session-b"]
      (events/append-event! :long-term-memory {:text "aus-b"}))
    (binding [events/*session-id* "session-b"]
      (let [rows (events/get-recent-events-by-type :long-term-memory 10 false)]
        (is (= ["aus-b"] (mapv #(get-in % [:event/data :text]) rows)))))))

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

(deftest test-build-query
  (testing "build-query erzeugt SQL und Parameter korrekt"
    (let [[sql & params] (#'events/build-query {:session "s-1"
                                               :type :user-message
                                               :search "Te_st%"
                                               :after "2026-05-30T10:00:00Z"
                                               :order :desc
                                               :limit 5})]
      (is (str/includes? sql "WHERE session = ? AND type = ? AND LOWER(data) LIKE ? ESCAPE '\\' AND COALESCE(valid_time, timestamp) > ?"))
      (is (str/includes? sql "ORDER BY COALESCE(valid_time, timestamp) DESC"))
      (is (str/includes? sql "LIMIT ?"))
      (is (= ["s-1" "user-message" "%te\\_st\\%%" "2026-05-30T10:00:00Z" 5]
            params))))
  (testing "ohne optionale Filter wird nur ORDER BY gesetzt"
    (let [[sql & params] (#'events/build-query {:order :asc})]
      (is (not (str/includes? sql "WHERE")))
      (is (str/includes? sql "ORDER BY COALESCE(valid_time, timestamp) ASC"))
      (is (empty? params)))))

(deftest test-append-event-with-valid-time
  (testing "append-event! akzeptiert explizite valid-time"
    (events/init-db!)
    (let [valid-time (Instant/parse "2026-05-30T10:00:00Z")
         event (events/append-event! :user-message {:text "mit valid-time"} valid-time)
         stored (first (events/get-events))]
      (is (= "2026-05-30T10:00:00Z" (:event/valid-time event)))
      (is (= (:event/valid-time event) (:event/valid-time stored)))
      (is (= (:event/transaction-time event) (:event/timestamp event)))
      (is (= (:event/transaction-time stored) (:event/timestamp stored))))))

(deftest test-entity-history
  (testing "entity-history liefert vollständige Zeitmetadaten in aufsteigender Reihenfolge"
    (events/init-db!)
    (events/append-event! :user-message {:text "alt"} (Instant/parse "2026-05-30T10:00:00Z"))
    (events/append-event! :assistant-message {:text "ignorieren"} (Instant/parse "2026-05-30T10:00:30Z"))
    (events/append-event! :user-message {:text "neu"} (Instant/parse "2026-05-30T10:01:00Z"))
    (let [history (events/entity-history :user-message)]
      (is (= 2 (count history)))
      (is (= ["alt" "neu"] (mapv #(get-in % [:event :event/data :text]) history)))
      (is (= ["2026-05-30T10:00:00Z" "2026-05-30T10:01:00Z"]
            (mapv :valid-time history)))
      (is (every? :transaction-time history)))))

(deftest test-migrate-db
  (testing "migrate-db! ergänzt fehlende Spalten und übernimmt timestamp-Werte"
    (let [conn (sqlite/open events/*db-path*)]
      (sqlite/execute! conn ["CREATE TABLE events (
                               id TEXT PRIMARY KEY,
                               session TEXT NOT NULL,
                               type TEXT NOT NULL,
                               data TEXT NOT NULL,
                               timestamp TEXT NOT NULL
                             )"])
      (sqlite/execute! conn ["INSERT INTO events (id, session, type, data, timestamp) VALUES (?, ?, ?, ?, ?)"
                            "00000000-0000-0000-0000-000000000001"
                            "legacy-session"
                            "user-message"
                            "{:text \"legacy\"}"
                            "2026-05-30T09:00:00Z"])
      (sqlite/close conn))
    (events/init-db!)
    (let [conn (#'events/get-conn)
         columns (set (map :name (sqlite/query conn ["PRAGMA table_info(events)"])))
         row (first (sqlite/query conn ["SELECT transaction_time, valid_time FROM events WHERE id = ?"
                                        "00000000-0000-0000-0000-000000000001"]))]
      (is (contains? columns "transaction_time"))
      (is (contains? columns "valid_time"))
      (is (= "2026-05-30T09:00:00Z" (:transaction_time row)))
      (is (= "2026-05-30T09:00:00Z" (:valid_time row))))))

(deftest test-get-context-window
  (testing "Kontextfenster gibt maximal N Events zurück"
    (events/init-db!)
    (dotimes [i 5]
      (events/append-event! :user-message {:text (str "msg-" i)}))
    (let [window (events/get-context-window 3)]
      (is (= 3 (count window)))
      (is (= ["msg-2" "msg-3" "msg-4"]
             (mapv #(get-in % [:event/data :text]) window))))))

(deftest test-get-context-window-with-summary
  (testing "Kontextfenster nutzt neueste Summary plus neuere Events"
    (events/init-db!)
    (events/append-event! :user-message {:text "alt-1"})
    (events/append-event! :assistant-message {:text "alt-2"})
    (events/append-event! :summary {:text "Zusammenfassung"
                                    :covers 2
                                    :from "2026-05-27T10:00:00Z"
                                    :to "2026-05-27T11:30:00Z"})
    (events/append-event! :user-message {:text "neu-1"})
    (let [window (events/get-context-window 2)]
      (is (= [:summary :user-message]
             (mapv :event/type window)))
      (is (= "Zusammenfassung"
             (get-in (first window) [:event/data :text])))
      (is (= "neu-1"
             (get-in (second window) [:event/data :text]))))))

(deftest test-session-isolation
  (testing "Events verschiedener Sessions sind isoliert"
    (binding [events/*session-id* "session-A"]
      (events/init-db!)
      (events/append-event! :user-message {:text "Nachricht von A"}))
    (binding [events/*session-id* "session-B"]
      (events/append-event! :user-message {:text "Nachricht von B"}))
    (binding [events/*session-id* "session-A"]
      (let [evts (events/get-events)]
        (is (= 1 (count evts)))
        (is (= "Nachricht von A" (get-in (first evts) [:event/data :text])))))))

(deftest test-cross-session-search
  (testing "Cross-session Suche findet Events aus allen Sessions"
    (events/init-db!)
    (binding [events/*session-id* "session-X"]
      (events/append-event! :user-message {:text "Wichtige Info aus X"}))
    (binding [events/*session-id* "session-Y"]
      (events/append-event! :user-message {:text "Wichtige Info aus Y"}))
    (binding [events/*session-id* "session-Y"]
      (let [results (events/get-events-by-query "Wichtige Info" nil true)]
        (is (= 2 (count results)))))))

(deftest test-list-sessions
  (testing "list-sessions gibt alle Sessions zurück"
    (events/init-db!)
    (binding [events/*session-id* "session-1"]
      (events/append-event! :user-message {:text "test"}))
    (binding [events/*session-id* "session-2"]
      (events/append-event! :user-message {:text "test"}))
    (let [sessions (events/list-sessions)]
      (is (>= (count sessions) 2)))))

(deftest test-usage-stats
  (testing "Usage-Statistiken aggregieren korrekt innerhalb einer Session"
    (events/init-db!)
    (events/append-event! :llm-usage {:model "gpt-4o-mini"
                                      :prompt-tokens 100
                                      :completion-tokens 40
                                      :total-tokens 140
                                      :context-messages 10
                                      :request-type :chat})
    (events/append-event! :llm-usage {:model "gpt-4o-mini"
                                      :prompt-tokens 50
                                      :completion-tokens 20
                                      :total-tokens 70
                                      :context-messages 20
                                      :request-type :chat-with-tools})
    (let [stats (events/get-usage-stats)]
      (is (= 2 (:total-requests stats)))
      (is (= 210 (:total-tokens stats)))
      (is (= 150 (:total-prompt stats)))
      (is (= 60 (:total-completion stats)))
      (is (= 15.0 (:avg-context-size stats)))
      (is (= 20 (:max-context-size stats)))
      (is (= [{:session "test-session-default"
               :requests 2
               :tokens 210}]
             (:by-session stats))))))

(deftest test-usage-stats-cross-session
  (testing "Cross-session Usage-Statistiken aggregieren über alle Sessions"
    (events/init-db!)
    (binding [events/*session-id* "session-A"]
      (events/append-event! :llm-usage {:model "gpt-4o-mini"
                                        :prompt-tokens 30
                                        :completion-tokens 10
                                        :total-tokens 40
                                        :context-messages 6
                                        :request-type :chat}))
    (binding [events/*session-id* "session-B"]
      (events/append-event! :llm-usage {:model "gpt-4o-mini"
                                        :prompt-tokens 20
                                        :completion-tokens 5
                                        :total-tokens 25
                                        :context-messages 4
                                        :request-type :chat-with-tools}))
    (binding [events/*session-id* "session-B"]
      (let [stats (events/get-usage-stats true)]
        (is (= 2 (:total-requests stats)))
        (is (= 65 (:total-tokens stats)))
        (is (= 50 (:total-prompt stats)))
        (is (= 15 (:total-completion stats)))
        (is (= 5.0 (:avg-context-size stats)))
        (is (= 6 (:max-context-size stats)))
        (is (= #{{:session "session-A" :requests 1 :tokens 40}
                 {:session "session-B" :requests 1 :tokens 25}}
               (set (:by-session stats))))))))
