(ns config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def global-instructions-path
  (str (System/getProperty "user.home") "/.copilot/copilot-instructions.md"))

(def repo-instructions-path
  ".github/copilot-instructions.md")

(def global-skills-dir
  (str (System/getProperty "user.home") "/.copilot/skills"))

(def repo-skills-dir
  ".github/skills")

(defn load-instructions []
  (let [global (when (.exists (io/file global-instructions-path)) (slurp global-instructions-path))
        repo   (when (.exists (io/file repo-instructions-path))   (slurp repo-instructions-path))]
    (cond
      (and global repo) (str global "\n\n---\n\n" repo)
      repo              repo
      global            global
      :else             nil)))

(defn- load-skills-from-dir [dir]
  (let [d (io/file dir)]
    (if (and (.exists d) (.isDirectory d))
      (->> (.listFiles d)
           (filter #(.isDirectory %))
           (reduce (fn [acc skill-dir]
                     (let [f (io/file skill-dir "SKILL.md")]
                       (if (.exists f)
                         (assoc acc (.getName skill-dir) (slurp f))
                         acc)))
                   {}))
      {})))

(defn load-skills []
  (merge (load-skills-from-dir global-skills-dir)
         (load-skills-from-dir repo-skills-dir)))

(defn build-system-prompt []
  (let [base         "You are an agentic memory assistant. Use tools when needed and return concise final answers."
        instructions (load-instructions)
        skills       (load-skills)
        parts        (cond-> [base]
                       instructions
                       (conj (str "## Custom Instructions\n\n" instructions))
                       (seq skills)
                       (conj (str "## Available Skills\n\n"
                                  (str/join "\n\n"
                                            (map (fn [[n c]] (str "### " n "\n" c)) skills)))))]
    (str/join "\n\n---\n\n" parts)))
