# babashka-scripts — Copilot Project Context

Dies ist ein **Babashka/Clojure**-Repository. Alle Skripte und Automatisierung
verwenden Babashka (`b`) als primäre Laufzeit.

Es muss immer `b` verwendet werden, auch wenn `bb` erwähnt wird!


## Essentielle Regeln (gelten IMMER)

1. **REPL-Pflicht**: Vor jeder Code-Ausführung `clj-nrepl-eval --discover-ports`
   prüfen. Wenn ein nREPL-Port gefunden wird, **ausschließlich** `clj-nrepl-eval -p <port>`
   verwenden. `b -e` / `b script.clj` / `b -m` sind **verboten** wenn ein REPL läuft.
   → Vollständige Regeln: `interactive-programming.instructions.md`

2. **Kein REPL = Stopp**: Wenn kein nREPL-Server läuft, den User bitten einen
   zu starten (`b nrepl-server 1667`). Nicht ohne REPL weiterarbeiten.

3. **Babashka bevorzugen**: Neue Skripte als `.bb`/`.clj` mit `b`, nicht als
   `.bat`/`.sh`/PowerShell. Ausnahme: Team-shared `.ps1`-Scripts.

4. **REPL-first Development**: Code zuerst im REPL entwickeln und testen,
   dann in Dateien schreiben. Nie Dateien editieren ohne REPL-Verbindung.

5. Bevorzuge `babashka.process/sh` vor Java-Interop für Shell-Befehle.

6. Verwende EDN zur Datenserialisation

7. Halte Funktionen klein und pure, wenn möglich

## Detail-Instructions (bei Clojure-Dateien automatisch geladen)

| Instruction File | Inhalt |
|---|---|
| `clojure-repl-workflow.instructions.md` | Decision Tree, REPL-Start, Debugging-Tipps |
| `babashka-scripting.instructions.md` | Projektstruktur, Konventionen, TDD, API-Client-Pattern |
| `malli-specs.instructions.md` | Malli-Schemas für Funktionsverträge |
