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

## GitHub Copilot CLI Integration

### Copilot CLI Plugin (native Tools)

Das Plugin exponiert Memory als native Copilot CLI Tools — bidirektional und während der gesamten Session nutzbar.

### Installation

```bash
bb run install-plugin
export MEMORY_DB="$HOME/.agentic-memory/memory.db"
```

### Verfügbare Tools

| Tool | Beschreibung |
|------|-------------|
| `memory_search` | Vergangene Events und Langzeit-Gedächtnis durchsuchen |
| `memory_add` | Fakt oder Entscheidung ins Langzeit-Gedächtnis aufnehmen |
| `memory_list` | Gesamtes Langzeit-Gedächtnis auflisten |
| `memory_session_end` | Session beenden, bereit für Dream-Konsolidierung |

### Vergleich: Plugin vs. Hooks

| | Hooks | Plugin |
|--|-------|--------|
| Memory beim Start | ✅ | ✅ |
| Memory aktiv während Session | ❌ | ✅ |
| Bidirektional | ❌ | ✅ |
| Copilot ruft Memory selbst auf | ❌ | ✅ |

### Hooks (empfohlen)

Hooks laufen automatisch bei jedem Copilot CLI Lifecycle-Event:

| Hook | Wann | Was passiert |
|------|------|--------------|
| `sessionStart` | Session-Start | Langzeit-Gedächtnis als Context injiziert |
| `postToolUse` | Nach jedem Tool | Ergebnis in SQLite gespeichert |
| `sessionEnd` | Session-Ende | Session markiert, bereit für Dream |

#### Repository-spezifisch (bereits enthalten)

Die Datei `.github/hooks/memory.json` ist bereits im Repo — sie wird automatisch von Copilot CLI geladen.

#### Global (alle Projekte)

```bash
mkdir -p ~/.copilot/hooks
sed "s|AGENTIC_MEMORY_PATH|$(pwd)|g" docs/global-hooks-example.json > ~/.copilot/hooks/memory.json
# MEMORY_DB in Shell-Config setzen:
echo 'export MEMORY_DB="$HOME/.agentic-memory/memory.db"' >> ~/.zshrc
```

#### Ablauf

```text
gh copilot suggest "deploy script schreiben"
 │
 ├─ sessionStart  → Lädt Langzeit-Gedächtnis automatisch als Context
 ├─ [Copilot arbeitet, nutzt Tools]
 ├─ postToolUse   → Tool-Ergebnisse in SQLite gespeichert
 └─ sessionEnd    → Session markiert

# Später:
bb run dream     → Konsolidierung mit Human-in-the-loop
```

### Skills (manuell)

Alternativ können Skills explizit aufgerufen werden — siehe `~/.copilot/skills/`.

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
{:event/id               #uuid "..."
 :event/session          "mein-projekt"
 :event/type             :user-message
 :event/transaction-time "2026-05-26T..."
 :event/valid-time       "2026-05-26T..."
 :event/data             {...}}
```

Unterstützte Event-Typen:

- `:user-message`
- `:assistant-message`
- `:tool-call`
- `:tool-result`
- `:summary`
- `:long-term-memory`
- `:llm-usage`
- `:error`

## Memory TTL & Purge

| Event-Typ | Standard-TTL |
|---|---|
| `user-message` | 30 Tage |
| `assistant-message` | 30 Tage |
| `tool-result` | 14 Tage |
| `tool-call` | 14 Tage |
| `session-end` | 7 Tage |
| `summary` | 90 Tage |
| `long-term-memory` | ∞ (nie) |

### Befehle
- `ttl` — TTL-Konfiguration und abgelaufene Events anzeigen
- `purge` — Abgelaufene Events löschen (mit Bestätigung)

### Konfiguration
```bash
MEMORY_TTL_USER_MESSAGE=30
AUTO_PURGE=true
```

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
| `summarize`  | Manuelle Kompression der Session    |
| `dream`      | LLM-Vorschläge für Langzeit-Gedächtnis erzeugen und auswählen |
| `conflicts`  | Widersprüche im Langzeit-Gedächtnis erkennen und auflösen |
| `promote <text>` | Text manuell ins Langzeit-Gedächtnis übernehmen |
| `memory`     | Langzeit-Gedächtnis anzeigen        |
| `ttl`        | TTL-Konfiguration und abgelaufene Events anzeigen |
| `purge`      | Abgelaufene Events löschen (mit Bestätigung) |
| `export`     | Aktuelle Session exportieren (`session-id.edn`) |
| `export <datei>` | Aktuelle Session in bestimmte Datei exportieren |
| `export all` | Alle Sessions exportieren (`memory-backup.edn`) |
| `import <datei>` | Events aus EDN-Datei in aktuelle Session laden |
| `history <event-type>` | Verlauf eines Event-Typs anzeigen |
| `help`       | Alle Befehle anzeigen               |

## Time Model

Jedes Event trägt zwei Zeitstempel, angelehnt an XTDBs bitemporales Modell:

| Field               | Meaning                                              |
|---------------------|------------------------------------------------------|
| `:transaction-time` | When the event was written to the DB                 |
| `:valid-time`       | When the event was factually valid (defaults to transaction-time) |

### Command: `history <event-type>`

```text
you> history user-message
📜 History for :user-message (12 entries)
  2026-05-30T10:00:00Z  {:text "Hello"}
  2026-05-30T10:01:05Z  {:text "How are you?"}
```

## Export & Import

Sessions können als EDN-Dateien gesichert und wiederhergestellt werden.

### Befehle

| Befehl              | Beschreibung                                    |
|---------------------|-------------------------------------------------|
| `export`            | Aktuelle Session exportieren (`session-id.edn`) |
| `export <datei>`    | Aktuelle Session in bestimmte Datei exportieren |
| `export all`        | Alle Sessions exportieren (`memory-backup.edn`) |
| `import <datei>`    | Events aus EDN-Datei in aktuelle Session laden  |
| `help`              | Alle Befehle anzeigen                           |

### Beispiel

```bash
you> export mein-backup.edn
[export] 42 Events exportiert nach: mein-backup.edn

you> import mein-backup.edn
[import] 42 von 42 Events importiert aus: mein-backup.edn
```

## Memory-Kompression

Ab 50 relevanten Events wird die Konversation automatisch komprimiert.
Die Originaldaten bleiben erhalten (Soft-Kompression).

## Dream Consolidation

Zusätzlich zur Summary-Kompression gibt es ein kuratiertes Langzeit-Gedächtnis.
Dabei analysiert `dream` den Session-Verlauf per LLM und schlägt nummerierte, langlebige Fakten vor.
Erst nach deiner Auswahl werden Vorschläge als `:long-term-memory` gespeichert.

### Befehle

- `dream` — erstellt Vorschläge und fragt interaktiv, welche Einträge gespeichert werden sollen
- `conflicts` — erkennt widersprüchliche Einträge und führt durch die Auflösung
- `promote <text>` — übernimmt einen Text manuell ins Langzeit-Gedächtnis (`:source :manual`)
- `memory` — zeigt alle gespeicherten Langzeit-Einträge (cross-session)

### Beispielablauf

```text
you> dream
[dream] Vorschläge:
  1. Für dieses Repo werden Tests mit `bb test` ausgeführt.
  2. Cross-Session Suche ist über memory_search mit cross-session=true möglich.
Speichern? Nummern (z. B. 1,3), 'all' oder Enter für none:
dream> 1
[dream] 1 Einträge gespeichert.

you> promote Nutze zuerst `help` bei unbekannten Befehlen
[memory] Eintrag gespeichert.

you> memory
[memory] Langzeit-Gedächtnis:
  1. [dream] Für dieses Repo werden Tests mit `bb test` ausgeführt.
  2. [manual] Nutze zuerst `help` bei unbekannten Befehlen
```

## Konflikt-Erkennung

`conflicts` analysiert alle Long-Term-Memory-Einträge auf Widersprüche und führt interaktiv durch die Auflösung (`a`, `b`, `both`, `merge`, `skip`).

### Konflikt-Erkennung während des Träumens

Beim `dream`-Befehl prüft das System vor jeder Übernahme eines Vorschlags,
ob er mit bestehenden Einträgen im Widerspruch steht:

```text
Dream-Vorschlag 3/5:
"Wir nutzen GitHub Actions für CI/CD"

⚠️  Konflikt mit bestehendem Eintrag:
Bestehend: "Wir nutzen CircleCI für CI/CD"
Neu:       "Wir nutzen GitHub Actions für CI/CD"
⚡ Widersprüchliche CI/CD-Tools

[n] Neu  [a] Alt behalten  [m] Merge  [b] Beide  [s] Skip
Auswahl> m
✅ Zusammengeführt: Wir haben von CircleCI auf GitHub Actions migriert.
```

```text
you> conflicts
⚠️  1 Widerspruch gefunden:

--- Konflikt 1 ---
A: Wir nutzen CircleCI für CI/CD
B: Wir nutzen GitHub Actions für CI/CD
⚡ Widersprüchliche CI/CD-Tools

[a] A behalten  [b] B behalten  [both] beide  [merge] zusammenführen  [skip]
Auswahl> merge
✅ Zusammengeführt: Wir haben von CircleCI auf GitHub Actions migriert.
```

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
  - `transaction_time` (TEXT, ISO-8601)
  - `valid_time` (TEXT, ISO-8601)
  - `timestamp` (TEXT, Legacy/Fallback)

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
- `src/config.clj` — Instructions & Skills laden (GitHub Copilot CLI Konvention)
- `src/events.clj` — SQLite Event Store
- `src/llm.clj` — GitHub Models API Client
- `src/dream.clj` — Dream Consolidation + Long-Term-Memory Funktionen
- `src/tools.clj` — Tool-Definitionen + Dispatcher
- `src/copilot_cli.clj` — GitHub Copilot CLI Integration
- `plugin/memory_plugin.clj` — JSON-RPC Plugin für native Copilot CLI Memory-Tools
- `plugin/manifest.json` — Plugin-Manifest für Copilot CLI
- `scripts/install_plugin.sh` — Installiert das Plugin nach `~/.copilot/plugins/agentic-memory`
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
| `skill_*`      | Dynamisch registrierte Skill-Tools (aus SKILL.md mit `run:`)    |

## Instructions & Skills

Folgt der GitHub Copilot CLI Konvention.

### Instructions

| Pfad | Scope |
|------|-------|
| `~/.copilot/copilot-instructions.md` | Global |
| `.github/copilot-instructions.md` | Repository |

Beide Ebenen werden kombiniert. Repository-Instructions werden zuletzt hinzugefügt.

### Skills

| Pfad | Scope |
|------|-------|
| `~/.copilot/skills/<name>/SKILL.md` | Global |
| `.github/skills/<name>/SKILL.md` | Repository |

SKILL.md Beispiel:

```
Run the test suite

run: bb test
```

Skills mit einer `run:` Zeile werden automatisch als Tools für den Agenten registriert. Repository-Skills überschreiben globale Skills bei gleichem Namen.

### Befehle

| Befehl         | Beschreibung                    |
|----------------|---------------------------------|
| `instructions` | Aktuelle Instructions anzeigen  |
| `skills`       | Geladene Skills auflisten       |
