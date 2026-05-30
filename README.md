# agentic-memory

Ein MVP für ein agentisches Memory-System in **Clojure/Babashka** mit:

- **GitHub Models** als LLM-Backend
- **GitHub Copilot CLI** für Shell-Tooling
- **SQLite + EDN** als Event Store

## Voraussetzungen

- [Babashka](https://babashka.org/) (`bb`)
- [GitHub CLI](https://cli.github.com/) (`gh`) mit Copilot-Erweiterung
- Ein gültiger `GITHUB_TOKEN` mit Zugriff auf GitHub Models

Optional:

- `GITHUB_MODELS_MODEL` (Default: `gpt-4o-mini`)

## Installation & Setup

1. Repository klonen
2. Umgebungsvariablen setzen:

```bash
cp .env.example .env
# dann .env befüllen oder env vars direkt exportieren
export GITHUB_TOKEN=your_github_token_here
export GITHUB_MODELS_MODEL=gpt-4o-mini
```

3. Babashka-Tasks anzeigen:

```bash
bb tasks
```

## Verwendung

### Interaktiver Agent-Loop starten

```bash
bb run
```

Dann Eingaben machen, z. B.:

```text
you> finde den richtigen curl command für github api
```

Beenden mit:

- `exit`
- oder `Ctrl+C` (graceful shutdown)

### Weitere Tasks

```bash
bb repl
bb test
```

## Event-Schema

Jedes Event wird als EDN-Datenstruktur gespeichert:

```clojure
{:event/id        #uuid "..."
 :event/session   "mein-projekt"
 :event/type      :user-message
 :event/timestamp "2026-05-26T..."
 :event/data      {...}}
```

Unterstützte Event-Typen:

- `:user-message`
- `:assistant-message`
- `:tool-call`
- `:tool-result`
- `:error`

## Sessions

Jeder Start von `bb run` erstellt automatisch eine neue Session:

```bash
bb run              # Neue zufällige Session-ID
bb run mein-projekt # Benannte Session (wiederverwendbar)
```

### Session-Befehle

| Befehl       | Beschreibung                        |
|--------------|-------------------------------------|
| `sessions`   | Alle bisherigen Sessions auflisten  |
| `stats`      | Verbrauchsstatistiken der aktuellen Session |
| `stats all`  | Verbrauchsstatistiken über alle Sessions |
| `exit`       | Session beenden                     |

### Cross-Session Gedächtnis

Das Tool `memory_search` kann mit `cross-session: true` über alle Sessions suchen — so hat der Agent Zugriff auf sein gesamtes Langzeitgedächtnis.

## Analyse & Statistiken

Der Agent speichert für jeden erfolgreichen LLM-Request ein `:llm-usage` Event mit:

- Modellname
- Prompt-, Completion- und Gesamt-Token
- Größe des Kontextfensters (`context-messages`)
- Request-Typ (`:chat` oder `:chat-with-tools`)

Beispielausgabe für `stats`:

```text
────────────────────────────────────────
📊 Session: mein-projekt
────────────────────────────────────────
Requests:         12
Tokens gesamt:    4200
  ├ Prompt:       3100
  └ Completion:   1100
Kontext ⌀:        18.3 Messages
Kontext max:      25 Messages
────────────────────────────────────────
```

## Event Store

- Datei: `memory.db`
- Tabelle: `events`
- Spalten:
  - `id` (TEXT)
  - `session` (TEXT)
  - `type` (TEXT)
  - `data` (EDN als TEXT)
  - `timestamp` (TEXT, ISO-8601)

Die DB wird automatisch bei Start initialisiert.

## Architektur-Übersicht

```text
User Input
   |
   v
core.clj (Agent Loop)
   |-- schreibt :user-message in events
   |-- lädt Kontextfenster (letzte 20 Events)
   |-- ruft llm/chat-with-tools auf
   |
   +--> tools.clj (Dispatcher)
          |-- shell_suggest -> copilot_cli.clj -> gh copilot suggest -t shell
          |-- shell_execute -> lokaler Shell-Call
          |-- memory_search -> Suche in gespeicherten Events
          '-- alle Tool-Calls/-Results als Events persistiert

llm.clj
   '-- GitHub Models API (OpenAI-kompatibel)
```

## Dateien

- `bb.edn` — Dependencies + Tasks (`run`, `repl`, `test`)
- `src/core.clj` — Haupt-Loop (CLI)
- `src/events.clj` — SQLite Event Store
- `src/llm.clj` — GitHub Models API Client
- `src/tools.clj` — Tool-Definitionen + Dispatcher
- `src/copilot_cli.clj` — GitHub Copilot CLI Integration
- `.env.example` — Beispielvariablen

## Konfiguration

| Umgebungsvariable            | Beschreibung                                                             | Default              |
|------------------------------|--------------------------------------------------------------------------|----------------------|
| `GITHUB_TOKEN`               | GitHub Personal Access Token                                             | —                    |
| `GITHUB_MODELS_MODEL`        | GitHub Models Modell                                                     | `gpt-4o-mini`        |
| `SHELL_ALLOWED_COMMANDS`     | Komma-separierte Allowlist für `shell_execute` (überschreibt Datei)     | `ls,find,cat,...`    |
| `SHELL_ALLOWED_COMMANDS_FILE`| Pfad zu Datei mit Allowlist (ein Programm pro Zeile)                    | `.shell_allowed_commands` |

Ladereihenfolge: `SHELL_ALLOWED_COMMANDS` → `SHELL_ALLOWED_COMMANDS_FILE` → `.shell_allowed_commands` → Default

## Sicherheitshinweis

Das Tool `shell_execute` ist mit zwei Sicherheitsstufen abgesichert:

1. **Allowlist**: Erlaubte Programme werden in folgender Reihenfolge geladen:
   - Umgebungsvariable `SHELL_ALLOWED_COMMANDS` (Komma-separiert)
   - Datei `SHELL_ALLOWED_COMMANDS_FILE` oder `.shell_allowed_commands` (ein Programm pro Zeile)
   - Default: `ls`, `find`, `cat`, `grep`, `echo`, `curl`, `git`, `gh`, `bb`, `pwd`, `env`, `which`, `date`

2. **Human-in-the-Loop**: Alle anderen Programme lösen eine interaktive Bestätigungsaufforderung aus.
   Der Agent kann keinen unbekannten Befehl ohne explizite Zustimmung des Benutzers ausführen.

## Verfügbare Tools

| Tool           | Beschreibung                                                    |
|----------------|-----------------------------------------------------------------|
| `shell_suggest` | Schlägt einen Shell-Befehl per GitHub Copilot CLI vor          |
| `shell_execute` | Führt einen Shell-Befehl aus (Allowlist + Human-in-the-Loop)   |
| `memory_search` | Durchsucht vergangene Events im Speicher                       |
| `file_read`    | Liest den Inhalt einer Datei (nur im Working Directory)         |
| `file_write`   | Schreibt Inhalt in eine Datei (mit Bestätigungsprompt)          |
