(ns memory-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [memory-config :as mcfg])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- make-temp-dir []
  (str (Files/createTempDirectory "agentic-memory-mcfg-test"
                                  (make-array FileAttribute 0))))

(deftest test-default-config
  (testing "Ohne Config-Datei wird default-config zurückgegeben"
    (let [cfg (mcfg/load-config "/nonexistent/path/config.edn")]
      (is (= :passive (:enforcement-level cfg)))))
  (testing "Ungültige EDN-Datei → default-config"
    (let [tmp (str (make-temp-dir) "/bad.edn")]
      (spit tmp "{{invalid")
      (let [cfg (mcfg/load-config tmp)]
        (is (= :passive (:enforcement-level cfg)))))))

(deftest test-load-config-levels
  (testing "Jeder Enforcement-Level wird korrekt geladen"
    (doseq [level [:passive :advisory :strict]]
      (let [tmp (str (make-temp-dir) "/config.edn")]
        (spit tmp (pr-str {:enforcement-level level}))
        (let [cfg (mcfg/load-config tmp)]
          (is (= level (:enforcement-level cfg))
              (str "Expected " level " enforcement level")))))))

(deftest test-load-config-merges-defaults
  (testing "Fehlende Schlüssel werden mit Default-Werten aufgefüllt"
    (let [tmp (str (make-temp-dir) "/config.edn")]
      (spit tmp (pr-str {:some-other-key "hello"}))
      (let [cfg (mcfg/load-config tmp)]
        (is (= :passive (:enforcement-level cfg)))
        (is (= "hello" (:some-other-key cfg)))))))

(deftest test-enforcement-level-default
  (testing "enforcement-level liefert :passive wenn keine Datei vorhanden"
    (with-redefs [mcfg/config-path (constantly "/nonexistent/path/config.edn")]
      (is (= :passive (mcfg/enforcement-level))))))
