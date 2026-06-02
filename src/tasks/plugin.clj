(ns tasks.plugin
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]))

(defn install!
  "Installiert das Copilot CLI Plugin via 'copilot plugin install'."
  []
  (let [repo-dir    (str (fs/canonicalize "."))
        bb-edn-path (str/replace (str repo-dir "/bb.edn") "\\" "/")
        plugin-dir  (str repo-dir "/plugin")
        plugin-json (str plugin-dir "/plugin.json")
        content     (format (str "{\n"
                                 "  \"name\": \"agentic-memory\",\n"
                                 "  \"version\": \"1.0.0\",\n"
                                 "  \"description\": \"Persistent cross-session memory for GitHub Copilot CLI\",\n"
                                 "  \"mcpServers\": {\n"
                                 "    \"agentic-memory\": {\n"
                                 "      \"type\": \"stdio\",\n"
                                 "      \"command\": \"bb\",\n"
                                 "      \"args\": [\"--config\", \"%s\", \"-x\", \"tasks.plugin-server/serve\"],\n"
                                 "      \"tools\": [\"memory_search\", \"memory_add\", \"memory_list\", \"memory_session_end\"]\n"
                                 "    }\n"
                                 "  }\n"
                                 "}")
                            bb-edn-path)]
    (spit plugin-json content)
    (println "📝 plugin.json geschrieben:" plugin-json)
    (shell "copilot" "plugin" "install" plugin-dir)
    (println "✅ Plugin installiert via: copilot plugin install" plugin-dir)
    (when-not (System/getenv "MEMORY_DB")
      (println "")
      (println "⚠️  MEMORY_DB nicht gesetzt. Empfehlung:")
      (println "   export MEMORY_DB=\"$HOME/.agentic-memory/memory.db\""))))

(defn -main [& _] (install!))
