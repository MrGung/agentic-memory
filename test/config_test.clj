(ns config-test
  (:require [clojure.test :refer [deftest is testing]]
            [config :as config])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- make-temp-dir []
  (str (Files/createTempDirectory "agentic-memory-config-test"
                                  (make-array FileAttribute 0))))

(deftest test-build-system-prompt-no-files
  (testing "Ohne Dateien liefert build-system-prompt den Basis-Prompt"
    (with-redefs [config/load-instructions (constantly nil)
                  config/load-skills       (constantly {})]
      (let [prompt (config/build-system-prompt)]
        (is (clojure.string/includes? prompt "agentic memory assistant"))))))

(deftest test-load-instructions-missing
  (testing "Fehlende Dateien → load-instructions liefert nil"
    (with-redefs [config/global-instructions-path "/nonexistent/path/copilot-instructions.md"
                  config/repo-instructions-path   "/nonexistent/path/repo-instructions.md"]
      (is (nil? (config/load-instructions))))))

(deftest test-load-skills-missing-dir
  (testing "Nicht-existierende Verzeichnisse → load-skills liefert {}"
    (with-redefs [config/global-skills-dir "/nonexistent/global/skills"
                  config/repo-skills-dir   "/nonexistent/repo/skills"]
      (is (= {} (config/load-skills))))))

(deftest test-load-instructions-from-temp-file
  (testing "Instructions aus Temp-Datei tauchen im System-Prompt auf"
    (let [tmp-dir  (make-temp-dir)
          tmp-file (str tmp-dir "/copilot-instructions.md")]
      (try
        (spit tmp-file "# Custom Instructions\n\nDo things the right way.")
        (with-redefs [config/global-instructions-path "/nonexistent/global-instructions.md"
                      config/repo-instructions-path   tmp-file]
          (let [prompt (config/build-system-prompt)]
            (is (clojure.string/includes? prompt "Custom Instructions"))
            (is (clojure.string/includes? prompt "Do things the right way."))))
        (finally
          (clojure.java.io/delete-file tmp-file true)
          (clojure.java.io/delete-file tmp-dir true))))))

(deftest test-load-instructions-combines-global-and-repo
  (testing "Global + Repo Instructions werden kombiniert, Repo kommt zuletzt"
    (let [tmp-dir        (make-temp-dir)
          global-file    (str tmp-dir "/global-instructions.md")
          repo-file      (str tmp-dir "/repo-instructions.md")]
      (try
        (spit global-file "Global content")
        (spit repo-file   "Repo content")
        (with-redefs [config/global-instructions-path global-file
                      config/repo-instructions-path   repo-file]
          (let [combined (config/load-instructions)]
            (is (clojure.string/includes? combined "Global content"))
            (is (clojure.string/includes? combined "Repo content"))
            (is (< (.indexOf combined "Global content")
                   (.indexOf combined "Repo content")))))
        (finally
          (clojure.java.io/delete-file global-file true)
          (clojure.java.io/delete-file repo-file true)
          (clojure.java.io/delete-file tmp-dir true))))))

(deftest test-load-skills-from-dir
  (testing "Skills werden aus Verzeichnisstruktur geladen"
    (let [tmp-dir   (make-temp-dir)
          skill-dir (str tmp-dir "/my-skill")]
      (try
        (.mkdirs (java.io.File. skill-dir))
        (spit (str skill-dir "/SKILL.md") "Do something\n\nrun: echo hello")
        (with-redefs [config/global-skills-dir "/nonexistent/global/skills"
                      config/repo-skills-dir   tmp-dir]
          (let [skills (config/load-skills)]
            (is (contains? skills "my-skill"))
            (is (clojure.string/includes? (get skills "my-skill") "run: echo hello"))))
        (finally
          (clojure.java.io/delete-file (str skill-dir "/SKILL.md") true)
          (clojure.java.io/delete-file skill-dir true)
          (clojure.java.io/delete-file tmp-dir true))))))

(deftest test-repo-skills-override-global
  (testing "Repo-Skills überschreiben Global-Skills bei gleichem Namen"
    (let [tmp-dir        (make-temp-dir)
          global-dir     (str tmp-dir "/global")
          repo-dir       (str tmp-dir "/repo")
          global-skill   (str global-dir "/my-skill")
          repo-skill     (str repo-dir "/my-skill")]
      (try
        (.mkdirs (java.io.File. global-skill))
        (.mkdirs (java.io.File. repo-skill))
        (spit (str global-skill "/SKILL.md") "Global skill\n\nrun: echo global")
        (spit (str repo-skill   "/SKILL.md") "Repo skill\n\nrun: echo repo")
        (with-redefs [config/global-skills-dir global-dir
                      config/repo-skills-dir   repo-dir]
          (let [skills (config/load-skills)]
            (is (= 1 (count skills)))
            (is (clojure.string/includes? (get skills "my-skill") "echo repo"))))
        (finally
          (clojure.java.io/delete-file (str global-skill "/SKILL.md") true)
          (clojure.java.io/delete-file (str repo-skill "/SKILL.md") true)
          (clojure.java.io/delete-file global-skill true)
          (clojure.java.io/delete-file repo-skill true)
          (clojure.java.io/delete-file global-dir true)
          (clojure.java.io/delete-file repo-dir true)
          (clojure.java.io/delete-file tmp-dir true))))))
