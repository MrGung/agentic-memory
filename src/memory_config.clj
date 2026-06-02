(ns memory-config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-config
  {:enforcement-level :passive})

(defn config-path
  "Returns the path to the memory config file.
  Can be overridden by the MEMORY_CONFIG environment variable."
  []
  (or (System/getenv "MEMORY_CONFIG")
      (str (System/getProperty "user.home") "/.agentic-memory/config.edn")))

(defn load-config
  "Loads memory configuration. Accepts an optional explicit path (for testing).
  Enforcement levels:
    :passive  — memory tools available, nothing enforced (default)
    :advisory — reminder at sessionStart, warning at sessionEnd if nothing saved
    :strict   — reminder at sessionStart, sessionEnd exits with code 1 if nothing saved"
  ([]
   (load-config (config-path)))
  ([path]
   (try
     (if (.exists (io/file path))
       (merge default-config (edn/read-string (slurp path)))
       default-config)
     (catch Exception _ default-config))))

(defn enforcement-level
  "Returns the configured :enforcement-level keyword."
  []
  (:enforcement-level (load-config)))
