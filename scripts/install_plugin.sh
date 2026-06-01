#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$HOME/.copilot/plugins/agentic-memory"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "🔌 Installiere agentic-memory Copilot CLI Plugin..."
mkdir -p "$PLUGIN_DIR"

cat > "$PLUGIN_DIR/manifest.json" <<EOF
{
  "name": "agentic-memory",
  "version": "1.0.0",
  "description": "Persistent cross-session memory for GitHub Copilot CLI",
  "runtime": "process",
  "command": "bb",
  "args": ["$REPO_DIR/plugin/memory_plugin.clj"],
  "tools": ["memory_search", "memory_add", "memory_list", "memory_session_end"]
}
EOF

echo "✅ Plugin installiert: $PLUGIN_DIR/manifest.json"
if [ -z "${MEMORY_DB:-}" ]; then
  echo ""
  echo "⚠️  MEMORY_DB nicht gesetzt. Empfehlung:"
  echo "   export MEMORY_DB=\"\$HOME/.agentic-memory/memory.db\""
fi
