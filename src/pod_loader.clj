(ns pod-loader
  (:require [babashka.pods :as pods]
            [clojure.string :as str]))

(defn- local-pod-path []
  (let [home    (System/getProperty "user.home")
        exe-sfx (if (str/includes? (System/getProperty "os.name") "Windows") ".exe" "")]
    (str home "/.babashka/pods/repository/org.babashka/go-sqlite3/0.3.13/pod-babashka-go-sqlite3" exe-sfx)))

(defn load-sqlite-pod! []
  (try
    (pods/load-pod 'pod.babashka.go-sqlite3 {:version "0.3.13"})
    (catch Exception _
      (pods/load-pod [(local-pod-path)]))))
