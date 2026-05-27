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

(deftest test-load-allowed-commands-from-env
  (testing "Allowlist wird aus SHELL_ALLOWED_COMMANDS geladen"
    (with-redefs [tools/get-env (fn [k] (when (= k "SHELL_ALLOWED_COMMANDS") "bb,clj,node"))]
      (let [cmds (#'tools/load-allowed-commands)]
        (is (contains? cmds "bb"))
        (is (contains? cmds "clj"))
        (is (contains? cmds "node"))
        (is (not (contains? cmds "ls"))))))
  (testing "Default-Allowlist wird verwendet wenn keine Env-Variable und keine Datei gesetzt"
    (with-redefs [tools/get-env (constantly nil)]
      (let [cmds (#'tools/load-allowed-commands)]
        (is (contains? cmds "ls"))
        (is (contains? cmds "git"))))))

(deftest test-load-allowed-commands-from-file
  (testing "Allowlist wird aus Datei geladen wenn SHELL_ALLOWED_COMMANDS_FILE gesetzt"
    (let [tmp-file (java.io.File/createTempFile "shell_allowed" ".txt")]
      (try
        (spit (.getPath tmp-file) "bb\nclj\nnode\n")
        (with-redefs [tools/get-env (fn [k]
                                      (when (= k "SHELL_ALLOWED_COMMANDS_FILE")
                                        (.getPath tmp-file)))]
          (let [cmds (#'tools/load-allowed-commands)]
            (is (contains? cmds "bb"))
            (is (contains? cmds "clj"))
            (is (contains? cmds "node"))
            (is (not (contains? cmds "ls")))))
        (finally
          (.delete tmp-file)))))
  (testing "SHELL_ALLOWED_COMMANDS hat Vorrang vor Datei"
    (let [tmp-file (java.io.File/createTempFile "shell_allowed" ".txt")]
      (try
        (spit (.getPath tmp-file) "only-in-file\n")
        (with-redefs [tools/get-env (fn [k]
                                      (case k
                                        "SHELL_ALLOWED_COMMANDS" "from-env"
                                        "SHELL_ALLOWED_COMMANDS_FILE" (.getPath tmp-file)
                                        nil))]
          (let [cmds (#'tools/load-allowed-commands)]
            (is (contains? cmds "from-env"))
            (is (not (contains? cmds "only-in-file")))))
        (finally
          (.delete tmp-file))))))

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
