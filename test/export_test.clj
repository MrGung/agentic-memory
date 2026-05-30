(ns export-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [events :as events]
            [export :as export]))

(deftest test-export-creates-file
  (testing "Export erstellt eine EDN-Datei"
    (binding [events/*db-path*    "/tmp/test-export.db"
              events/*session-id* "export-test-session"]
      (events/close-db!)
      (events/init-db!)
      (events/append-event! :user-message {:text "Test Export"})
      (let [file "/tmp/test-export-output.edn"]
        (export/export! file)
        (is (.exists (io/file file)))
        (io/delete-file file true)))))

(deftest test-export-import-roundtrip
  (testing "Export → Import Roundtrip erhält Events"
    (binding [events/*db-path*    "/tmp/test-roundtrip.db"
              events/*session-id* "roundtrip-session"]
      (events/close-db!)
      (events/init-db!)
      (events/append-event! :user-message {:text "Hallo Roundtrip"})
      (let [file "/tmp/test-roundtrip.edn"]
        (export/export! file)
        (events/close-db!)
        (events/init-db!)
        (export/import! file)
        (let [evts (events/get-events)]
          (is (= 1 (count evts)))
          (is (= "Hallo Roundtrip" (get-in (first evts) [:event/data :text]))))
        (io/delete-file file true)))))

(deftest test-import-missing-file
  (testing "Import mit nicht-existierender Datei gibt Fehlermeldung"
    (binding [events/*db-path*    "/tmp/test-missing.db"
              events/*session-id* "missing-session"]
      (events/close-db!)
      (events/init-db!)
      (is (nil? (export/import! "/tmp/does-not-exist.edn"))))))
