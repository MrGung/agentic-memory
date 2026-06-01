(ns ttl-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [events :as events]
            [ttl :as ttl])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- with-test-db [f]
  (let [dir (str (Files/createTempDirectory "agentic-memory-ttl-test"
                                            (make-array FileAttribute 0)))
        db-path (str (fs/path dir "test-memory.db"))]
    (binding [events/*db-path* db-path
              events/*session-id* "ttl-test-session"]
      (try
        (events/close-db!)
        (f)
        (finally
          (events/close-db!)
          (fs/delete-tree dir))))))

(use-fixtures :each with-test-db)

(deftest test-default-ttl-values
  (with-redefs [ttl/get-env (constantly nil)]
    (is (= 30 (ttl/ttl-days :user-message)))
    (is (= 30 (ttl/ttl-days :assistant-message)))
    (is (= 14 (ttl/ttl-days :tool-result)))
    (is (= 14 (ttl/ttl-days :tool-call)))
    (is (= 7 (ttl/ttl-days :session-end)))
    (is (= 90 (ttl/ttl-days :summary)))
    (is (nil? (ttl/ttl-days :long-term-memory)))))

(deftest test-expired-check
  (with-redefs [ttl/get-env (constantly nil)]
    (let [now (Instant/parse "2026-06-01T00:00:00Z")]
      (testing "Altes Event ist abgelaufen"
        (is (true? (ttl/expired? {:event/type :user-message
                                  :event/valid-time "2026-04-01T00:00:00Z"}
                                 now))))
      (testing "Neues Event ist nicht abgelaufen"
        (is (false? (ttl/expired? {:event/type :user-message
                                   :event/valid-time "2026-05-20T00:00:00Z"}
                                  now))))
      (testing "Long-term-memory läuft nie ab"
        (is (false? (ttl/expired? {:event/type :long-term-memory
                                   :event/valid-time "2020-01-01T00:00:00Z"}
                                  now)))))))

(deftest test-purge-dry-run
  (with-redefs [ttl/get-env (constantly nil)]
    (events/init-db!)
    (events/append-event! :user-message {:text "alt"} (Instant/parse "2026-04-01T00:00:00Z"))
    (events/append-event! :user-message {:text "neu"} (Instant/parse "2026-05-31T00:00:00Z"))
    (events/append-event! :tool-call {:name "x"} (Instant/parse "2026-05-01T00:00:00Z"))
    (let [status (ttl/purge-dry-run! (Instant/parse "2026-06-01T00:00:00Z"))]
      (is (map? status))
      (is (map? (:expired-by-type status)))
      (is (contains? (:expired-by-type status) :user-message))
      (is (contains? (:expired-by-type status) :tool-call)))))

(deftest test-purge-deletes-expired-events
  (with-redefs [ttl/get-env (constantly nil)]
    (events/init-db!)
    (events/append-event! :user-message {:text "alt"} (Instant/parse "2026-04-01T00:00:00Z"))
    (events/append-event! :user-message {:text "neu"} (Instant/parse "2026-05-31T00:00:00Z"))
    (let [deleted (ttl/purge! (Instant/parse "2026-06-01T00:00:00Z"))
          remaining (events/get-events-by-type :user-message)]
      (is (= 1 deleted))
      (is (= 1 (count remaining)))
      (is (= "neu" (get-in (first remaining) [:event/data :text]))))))
