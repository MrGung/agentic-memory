(ns export-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [events :as events]
            [export :as export])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest test-export-creates-file
  (testing "Export erstellt eine EDN-Datei"
    (let [dir (str (Files/createTempDirectory "agentic-memory-export-test" (make-array FileAttribute 0)))]
      (try
        (binding [events/*db-path*    (str (fs/path dir "export.db"))
                  events/*session-id* "export-test-session"]
          (events/close-db!)
          (events/init-db!)
          (events/append-event! :user-message {:text "Test Export"})
          (let [file (str (fs/path dir "output.edn"))]
            (export/export! file)
            (is (.exists (io/file file)))))
        (finally
          (fs/delete-tree dir))))))

(deftest test-export-import-roundtrip
  (testing "Export → Import Roundtrip erhält Events"
    (let [dir (str (Files/createTempDirectory "agentic-memory-roundtrip-test" (make-array FileAttribute 0)))]
      (try
        (let [file (str (fs/path dir "roundtrip.edn"))]
          (binding [events/*db-path*    (str (fs/path dir "source.db"))
                    events/*session-id* "roundtrip-session"]
            (events/init-db!)
            (events/append-event! :user-message {:text "Hallo Roundtrip"})
            (export/export! file))
          (binding [events/*db-path*    (str (fs/path dir "target.db"))
                    events/*session-id* "roundtrip-session"]
            (events/init-db!)
            (export/import! file)
            (let [evts (events/get-events)]
              (is (= 1 (count evts)))
              (is (= "Hallo Roundtrip" (get-in (first evts) [:event/data :text]))))))
        (finally
          (fs/delete-tree dir))))))

(deftest test-import-missing-file
  (testing "Import mit nicht-existierender Datei gibt Fehlermeldung"
    (let [dir (str (Files/createTempDirectory "agentic-memory-missing-test" (make-array FileAttribute 0)))]
      (try
        (binding [events/*db-path*    (str (fs/path dir "missing.db"))
                  events/*session-id* "missing-session"]
          (events/close-db!)
          (events/init-db!)
          (is (nil? (export/import! (str (fs/path dir "does-not-exist.edn"))))))
        (finally
          (fs/delete-tree dir))))))
