(ns tools-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [events :as events]
            [tools :as tools])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-test-db [f]
  (let [dir (str (Files/createTempDirectory "agentic-memory-tools-test"
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

(deftest test-allowlist-permitted
  (testing "Erlaubte Befehle werden erkannt"
    (is (#'tools/allowed? "ls -la"))
    (is (#'tools/allowed? "git status"))
    (is (#'tools/allowed? "gh repo list"))
    (is (#'tools/allowed? "cat README.md"))
    (is (#'tools/allowed? "echo hello"))))

(deftest test-allowlist-blocked
  (testing "Nicht erlaubte Befehle werden abgelehnt"
    (is (not (#'tools/allowed? "rm -rf /")))
    (is (not (#'tools/allowed? "sudo apt install")))
    (is (not (#'tools/allowed? "chmod 777 .")))
    (is (not (#'tools/allowed? "python3 script.py")))))

(deftest test-allowlist-edge-cases
  (testing "Edge Cases: leerer Befehl, Whitespace"
    (is (not (#'tools/allowed? "")))
    (is (not (#'tools/allowed? "   ")))))

(deftest test-dispatch-tool-call
  (testing "Dispatcher protokolliert Tool-Aufruf und Ergebnis"
    (events/init-db!)
    (events/append-event! :user-message {:text "Hallo Welt"})
    (let [response (tools/dispatch-tool-call!
                    {:id "call-1"
                     :function {:name "memory_search"
                                :arguments (json/generate-string {:query "hallo"})}})
          result (json/parse-string (:content response) true)
          event-types (mapv :event/type (events/get-events))]
      (is (= "tool" (:role response)))
      (is (= "call-1" (:tool_call_id response)))
      (is (= 1 (count (:matches result))))
      (is (= :user-message (get-in result [:matches 0 :event/type])))
      (is (= [:user-message :tool-call :tool-result] event-types)))))

(deftest test-dispatch-tool-call-with-event-type-filter
  (testing "memory_search unterstützt optionalen event-type Filter"
    (events/init-db!)
    (events/append-event! :user-message {:text "Testfrage"})
    (events/append-event! :assistant-message {:text "Testantwort"})
    (let [response (tools/dispatch-tool-call!
                    {:id "call-2"
                     :function {:name "memory_search"
                                :arguments (json/generate-string {:event-type "assistant-message"})}})
          result (json/parse-string (:content response) true)]
      (is (= 1 (count (:matches result))))
      (is (= :assistant-message (get-in result [:matches 0 :event/type]))))))

(deftest test-safe-path
  (testing "Pfade innerhalb des Working Directory sind erlaubt"
    (is (#'tools/safe-path? "README.md"))
    (is (#'tools/safe-path? "src/tools.clj")))
  (testing "Pfad-Traversal wird blockiert"
    (is (not (#'tools/safe-path? "../../etc/passwd")))
    (is (not (#'tools/safe-path? "/etc/passwd")))))

(deftest test-file-read-write
  (let [tmp "test-tmp-file.txt"]
    (testing "Datei schreiben und lesen"
      ;; file-write mit auto-confirm mocken
      (with-redefs [tools/confirm-write! (constantly true)]
        (let [write-result (#'tools/file-write tmp "Hallo Test")]
          (is (= 0 (:exit write-result)))
          (is (:written write-result))))
      (let [read-result (#'tools/file-read tmp)]
        (is (= 0 (:exit read-result)))
        (is (= "Hallo Test" (:content read-result))))
      ;; Aufräumen
      (clojure.java.io/delete-file tmp true))))
