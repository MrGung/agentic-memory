---
applyTo: '**/*.{clj,cljs,cljc,bb}'
description: 'Konventionen für Markdown-Formatierung und Links in Clojure/Babashka-Docstrings, die von quickdoc zu API.md verarbeitet werden.'
---

# Quickdoc: Docstring-Formatierung

## Zweck

`quickdoc` erzeugt aus Docstrings eine `API.md` im Repo-Root. Docstring wird **unverändert als Markdown** übernommen — kein Umformatieren. Falsches Markdown → falsches `API.md`.

## Markdown-Links

> Links **relativ zu `API.md`** = relativ zu Repo-Root.

```clojure
;; ✅ Korrekt: Pfad relativ zum Repo-Root
"Siehe [tools/outlook-com/README.md](tools/outlook-com/README.md)"

;; ❌ Falsch: Pfad relativ zur Quelldatei
"Siehe [README.md](../../tools/outlook-com/README.md)"

;; ✅ Externe URLs immer OK
"Siehe [GitHub CLI](https://cli.github.com/)"
```

## Formatierungsregeln

### Listen — Leerzeile vorher Pflicht

```clojure
;; ❌ Falsch: kein Absatz → items als Fließtext
"Wird aufgerufen von:
- Skript A
- Skript B"

;; ✅ Korrekt
"Wird aufgerufen von:

- Skript A
- Skript B"
```

### Code — fenced blocks, keine Einrückung

2-Leerzeichen-Einrückung = kein Code-Block in GFM.

```clojure
;; ❌ Falsch
"Beispiel:
  [:and cond1 cond2]"

;; ✅ Korrekt
"Beispiel:

\`\`\`clojure
[:and cond1 cond2]
\`\`\`"
```

Backticks in Clojure-Strings nicht escapen — nur `\"` und `\\` Pflicht.

### Tabellen

Strukturierte Daten als Markdown-Tabelle, nicht eingerückte Key-Value-Paare.

> Typ-/Strukturdoku von Funktions-Ein-/Ausgaben gehört **nicht** in Docstring-Tabellen — in Malli-Specs (`specs.clj`). Tabellen sinnvoll für fachliche Erklärungen (DSL-Syntax, Config-Keys, Enum-Bedeutungen).

```clojure
;; ❌ Falsch
"  :key1  - Bedeutung A
  :key2  - Bedeutung B"

;; ✅ Korrekt (Leerzeile davor nicht vergessen)
"| Schlüssel | Bedeutung |
|---|---|
| `:key1` | Bedeutung A |
| `:key2` | Bedeutung B |"
```

### Überschriften

quickdoc rendert Namespace als `##`. Sub-Abschnitte → `###`.

## Vollständiges Beispiel

```clojure
(ns outlook.mail-rules
  "Einzeilige Zusammenfassung (erscheint im Inhaltsverzeichnis).

Ausführlichere Beschreibung.

Wird aufgerufen von:

- [Invoke-OutlookMailRules.ps1](tools/outlook-com/Invoke-OutlookMailRules.ps1)
- [MailRulesModule.bas](tools/outlook-vba/MailRulesModule.bas)

## DSL-Beispiel

\`\`\`clojure
[:and cond1 cond2]
\`\`\`

## Optionen

| Schlüssel | Pflicht | Bedeutung |
|---|:---:|---|
| `:foo` | ✓ | Beschreibung |
| `:bar` | | Optional |"
  (:require ...))
```

## Quickdoc-Konfiguration

```edn
quickdoc-config {:git/branch  "master"
                 :github/repo "https://github.com/datevscm/t06057a-babashka-scripts"
                 :toc         true
                 :source-paths [...]}
```

Aufruf: `bb quickdoc`

## Verwandte Dateien

- `API.md` — generierte Ausgabe (nicht manuell bearbeiten)
- `bb.edn` — `quickdoc`-Task und `quickdoc-config`
