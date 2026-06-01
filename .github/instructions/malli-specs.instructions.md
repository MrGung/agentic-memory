---
applyTo: '**/*.{clj,cljs,cljc,bb}'
description: 'Malli specs replace markdown tables in docstrings as the sole validatable documentation for input/output types and data structures.'
---

# Malli Specs for Function Contracts

Malli schemas replace prose type docs (markdown tables, `## Rückgabe` sections). Machine-readable, validatable, single source of truth for data shapes.

## Rules

### 1. Every public function gets a schema

Every `defn` (non-private) in `core.clj` → schema in `specs.clj`. Private helpers: schema only if complex struct consumed in multiple places.

### 2. `specs.clj` in same directory

```
src/<domain>/<service>/core.clj
src/<domain>/<service>/specs.clj   ← HERE
src/<domain>/<service>/repl.clj
test/<domain>/<service>/core_test.clj
```

```clojure
(ns github.api.specs
  (:require [malli.core :as m]))
```

### 3. Schema forms

`:=>` for single-arity, `:function` for multi-arity:

```clojure
;; Single-arity
(def get-repo-schema
  [:=> [:cat string? string?] :map])

;; Multi-arity
(def generate-payload-schema
  [:function
   [:=> [:cat :map keyword?] :map]
   [:=> [:cat :map keyword? :map] :map]
   [:=> [:cat :map keyword? :map :map] :map]])
```

### 4. Named map schemas for complex structures

```clojure
(def WorktreeEntry
  [:map
   [:repo string?]
   [:branch string?]
   [:worktree-path string?]
   [:created-at {:optional true} string?]])

(def GhResult
  [:map
   [:exit int?]
   [:out string?]
   [:err string?]])
```

### 5. Docstrings: one-liner only

No type tables, no `## Parameter`/`## Rückgabe`/`## Optionen` for types. Domain explanations (algorithm, side effects, context) still OK in docstring.

```clojure
;; ❌ Old: type table in docstring
(defn run-gh!
  "Executes a `gh` CLI command and returns the result.

  | Key     | Meaning                |
  |---------|------------------------|
  | `:exit` | Exit code              |
  | `:out`  | stdout as string       |
  | `:err`  | stderr as string       |"
  [args] ...)

;; ✅ New: one-liner + schema in specs.clj
(defn run-gh!
  "Executes a `gh` CLI command."
  [args] ...)
```

### 6. No `malli.instrument` — declarative only

Specs serve as: documentation (API.md via quickdoc), test validation (`m/validate`, `m/explain`), test data gen (`mg/generate`), contract between namespaces.

### 7. Migration: Tidy First

- New namespaces: specs mandatory from day one
- Existing namespaces: specs created when namespace touched
- No big-bang: existing docstring tables stay until code modified → then migrate

### 8. Specs as test foundation

```clojure
(deftest run-gh-result-matches-spec
  (let [result (api/run-gh! ["--version"])]
    (is (m/validate specs/GhResult result))))
```

## Best Practices

### Shared schemas → `common/schemas.clj`

```clojure
(ns common.schemas)

(def NonBlankString [:string {:min 1}])
(def UUID-String [:and string? [:re #"^[0-9a-f]{8}-[0-9a-f]{4}-.*"]])
```

### Enums inline

```clojure
(def WorkflowStatus [:enum "queued" "in_progress" "completed"])
```

### Optional keys explicit

```clojure
(def WorkflowRunFilter
  [:map
   [:limit {:optional true} pos-int?]
   [:status {:optional true} WorkflowStatus]
   [:branch {:optional true} string?]])
```

### Generation for REPL

```clojure
(require '[malli.generator :as mg])
(mg/generate specs/WorktreeEntry)
;; => {:repo "a8k" :branch "F" :worktree-path "x2" :created-at "hQ"}
```

### Spec namespace docstring for quickdoc

```clojure
(ns github.api.specs
  "Malli schemas for `github.api` — input/output contracts for all public functions."
  (:require [malli.core :as m]))
```

## Before → After Example

**Before** (`state.clj`):
```clojure
(defn add-worktree!
  "Adds a new worktree to the state.

  | Key              | Description                    |
  |------------------|--------------------------------|
  | `:repo`          | Path to the git repository     |
  | `:branch`        | Branch name                    |
  | `:worktree-path` | Path to the worktree directory |
  | `:created-at`    | Creation timestamp             |"
  [{:keys [repo branch worktree-path] :as worktree}]
  ...)
```

**After** `state.clj`:
```clojure
(defn add-worktree!
  "Adds a new worktree to the state (idempotent)."
  [{:keys [repo branch worktree-path] :as worktree}]
  ...)
```

**After** `specs.clj`:
```clojure
(ns tooling.worktree.specs
  "Malli schemas for `tooling.worktree.state`."
  (:require [malli.core :as m]))

(def WorktreeEntry
  [:map
   [:repo string?]
   [:branch string?]
   [:worktree-path string?]
   [:created-at {:optional true} string?]])

(def add-worktree-schema
  [:=> [:cat WorktreeEntry] WorktreeEntry])
```

## References

- [Malli GitHub](https://github.com/metosin/malli)
- [Malli Function Schemas](https://github.com/metosin/malli#function-schemas)
- `babashka-scripting.instructions.md` — API/Service Client Pattern
- `quickdoc-links.instructions.md` — docstring formatting
