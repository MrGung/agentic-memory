(ns copilot-cli
  (:require [babashka.process :as p]))

(defn- run-gh! [args]
  (try
    (let [result (apply p/shell {:out :string :err :string :continue true} "gh" args)]
      {:stdout (:out result)
       :stderr (:err result)
       :exit (:exit result)})
    (catch Exception e
      {:stdout ""
       :stderr (.getMessage e)
       :exit 1})))

(defn suggest-shell-command [task]
  (run-gh! ["copilot" "suggest" "-t" "shell" task]))

(defn explain-command [cmd]
  (let [target (cond
                 (.startsWith cmd "gh ") "gh"
                 (.startsWith cmd "git ") "git"
                 :else "shell")]
    (run-gh! ["copilot" "explain" "-t" target cmd])))
