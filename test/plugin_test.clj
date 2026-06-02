(ns plugin-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private bb-bin
  (or (System/getenv "BB_BIN") "b"))

(def ^:private bb-config
  (str (fs/cwd) "/bb.edn"))

(def ^:dynamic *tmp-dir* nil)

(defn- with-temp-dir [f]
  (let [dir (str (Files/createTempDirectory "agentic-memory-plugin-test"
                                            (make-array FileAttribute 0)))]
    (binding [*tmp-dir* dir]
      (try
        (f)
        (finally
          (fs/delete-tree dir))))))

(use-fixtures :each with-temp-dir)

(defn- with-env [env-map]
  (merge (into {} (System/getenv)) env-map))

(defn- call-plugin [request env-map]
  (let [proc (p/process [bb-bin "--config" bb-config "-x" "tasks.plugin-server/serve"]
                        {:in  (str (json/generate-string request) "\n")
                         :out :string
                         :err :string
                         :env (with-env env-map)})]
    (-> proc
        p/check
        :out
        str/trim
        (json/parse-string true))))

(defn- call-plugin-seq [requests env-map]
  (let [input (str (str/join "\n" (map json/generate-string requests)) "\n")
        proc  (p/process [bb-bin "--config" bb-config "-x" "tasks.plugin-server/serve"]
                         {:in  input
                          :out :string
                          :err :string
                          :env (with-env env-map)})]
    (->> (-> proc p/check :out str/split-lines)
         (remove str/blank?)
         (mapv #(json/parse-string % true)))))

(deftest test-initialize
  (testing "Plugin antwortet auf initialize"
    (let [db-path (str *tmp-dir* "/test-plugin-init.db")
          result  (call-plugin {:jsonrpc "2.0" :id 1 :method "initialize" :params {}}
                               {"MEMORY_DB" db-path})]
      (is (= "agentic-memory" (get-in result [:result :serverInfo :name]))))))

(deftest test-tools-list
  (testing "Plugin gibt 6 Tools zurück"
    (let [db-path (str *tmp-dir* "/test-plugin-tools.db")
          result  (call-plugin {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}}
                               {"MEMORY_DB" db-path})
          tools   (get-in result [:result :tools])]
      (is (= 6 (count tools)))
      (is (some #(= "memory_search" (:name %)) tools))
      (is (some #(= "memory_add" (:name %)) tools))
      (is (some #(= "memory_list" (:name %)) tools))
      (is (some #(= "memory_session_end" (:name %)) tools))
      (is (some #(= "memory_add_repo" (:name %)) tools))
      (is (some #(= "memory_list_repo" (:name %)) tools)))))

(deftest test-memory-roundtrip-in-one-process
  (testing "memory_add, memory_search, memory_list und memory_session_end funktionieren in einem Prozess"
    (let [db-path  (str *tmp-dir* "/test-plugin-roundtrip.db")
          results  (call-plugin-seq
                    [{:jsonrpc "2.0" :id 1 :method "tools/call"
                      :params {:name "memory_add"
                               :arguments {:text "Babashka ist das bevorzugte Tool"}}}
                     {:jsonrpc "2.0" :id 2 :method "tools/call"
                      :params {:name "memory_search"
                               :arguments {:query "Babashka"
                                           :cross_session true
                                           :event_type "long-term-memory"}}}
                     {:jsonrpc "2.0" :id 3 :method "tools/call"
                      :params {:name "memory_list"
                               :arguments {}}}
                     {:jsonrpc "2.0" :id 4 :method "tools/call"
                      :params {:name "memory_session_end"
                               :arguments {}}}]
                    {"MEMORY_DB" db-path})
          add-res  (nth results 0)
          find-res (nth results 1)
          list-res (nth results 2)
          end-res  (nth results 3)]
      (is (= true (get-in add-res [:result :saved])))
      (is (pos? (get-in find-res [:result :count])))
      (is (= "Babashka ist das bevorzugte Tool"
             (get-in find-res [:result :matches 0 :data :text])))
      (is (= 1 (get-in list-res [:result :count])))
      (is (= "Babashka ist das bevorzugte Tool"
             (get-in list-res [:result :entries 0 :data :text])))
      (is (= true (get-in end-res [:result :done]))))))

(deftest test-memory-repo-roundtrip
  (testing "memory_add_repo und memory_list_repo funktionieren im selben Prozess"
    (let [db-path  (str *tmp-dir* "/test-plugin-repo.db")
          repo-url "https://github.com/test-org/test-repo.git"
          results  (call-plugin-seq
                    [{:jsonrpc "2.0" :id 1 :method "tools/call"
                      :params {:name "memory_add_repo"
                               :arguments {:text       "Immer HoneySQL für SQL verwenden"
                                           :repository repo-url}}}
                     {:jsonrpc "2.0" :id 2 :method "tools/call"
                      :params {:name "memory_list_repo"
                               :arguments {:repository repo-url}}}]
                    {"MEMORY_DB" db-path})
          add-res  (nth results 0)
          list-res (nth results 1)]
      (is (= true (get-in add-res [:result :saved])))
      (is (= "repo:github.com/test-org/test-repo" (get-in add-res [:result :repository])))
      (is (= 1 (get-in list-res [:result :count])))
      (is (= "Immer HoneySQL für SQL verwenden"
             (get-in list-res [:result :entries 0 :data :text]))))))

(defn- write-config! [path level]
  (spit path (pr-str {:enforcement-level level})))

(deftest test-enforcement-passive
  (testing "Stufe :passive — memory_session_end liefert :done true ohne Warning"
    (let [db-path  (str *tmp-dir* "/test-enforce-passive.db")
          cfg-path (str *tmp-dir* "/config-passive.edn")]
      (write-config! cfg-path :passive)
      (let [result (call-plugin {:jsonrpc "2.0" :id 1 :method "tools/call"
                                  :params {:name "memory_session_end" :arguments {}}}
                                 {"MEMORY_DB"      db-path
                                  "MEMORY_CONFIG"  cfg-path})]
        (is (= true (get-in result [:result :done])))
        (is (nil? (get-in result [:result :warning])))
        (is (nil? (get-in result [:result :blocked])))))))

(deftest test-enforcement-advisory-no-memories
  (testing "Stufe :advisory — ohne Erinnerungen: :done true mit :warning"
    (let [db-path  (str *tmp-dir* "/test-enforce-advisory.db")
          cfg-path (str *tmp-dir* "/config-advisory.edn")]
      (write-config! cfg-path :advisory)
      (let [result (call-plugin {:jsonrpc "2.0" :id 1 :method "tools/call"
                                  :params {:name "memory_session_end" :arguments {}}}
                                 {"MEMORY_DB"      db-path
                                  "MEMORY_CONFIG"  cfg-path})]
        (is (= true (get-in result [:result :done])))
        (is (string? (get-in result [:result :warning])))))))

(deftest test-enforcement-advisory-with-memories
  (testing "Stufe :advisory — nach memory_add: :done true ohne :warning"
    (let [db-path  (str *tmp-dir* "/test-enforce-advisory-saved.db")
          cfg-path (str *tmp-dir* "/config-advisory-saved.edn")]
      (write-config! cfg-path :advisory)
      (let [results (call-plugin-seq
                     [{:jsonrpc "2.0" :id 1 :method "tools/call"
                       :params {:name "memory_add" :arguments {:text "Wichtige Erkenntnis"}}}
                      {:jsonrpc "2.0" :id 2 :method "tools/call"
                       :params {:name "memory_session_end" :arguments {}}}]
                     {"MEMORY_DB"     db-path
                      "MEMORY_CONFIG" cfg-path})
            end-res (nth results 1)]
        (is (= true (get-in end-res [:result :done])))
        (is (nil? (get-in end-res [:result :warning])))
        (is (pos? (get-in end-res [:result :saved])))))))

(deftest test-enforcement-strict-no-memories
  (testing "Stufe :strict — ohne Erinnerungen: :done false, :blocked true"
    (let [db-path  (str *tmp-dir* "/test-enforce-strict.db")
          cfg-path (str *tmp-dir* "/config-strict.edn")]
      (write-config! cfg-path :strict)
      (let [result (call-plugin {:jsonrpc "2.0" :id 1 :method "tools/call"
                                  :params {:name "memory_session_end" :arguments {}}}
                                 {"MEMORY_DB"      db-path
                                  "MEMORY_CONFIG"  cfg-path})]
        (is (= false (get-in result [:result :done])))
        (is (= true  (get-in result [:result :blocked])))))))
