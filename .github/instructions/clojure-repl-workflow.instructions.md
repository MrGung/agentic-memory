---
applyTo: '**/*.{clj,cljs,cljc,bb,edn}'
description: 'REPL setup for babashka-scripts. Start commands and connection details are in babashka-scripting.instructions.md. Full workflow in the global interactive-programming.instructions.md.'
---

# REPL Workflow — babashka-scripts

> Full rules: `interactive-programming.instructions.md` (global). Project quick-ref only.

## Decision Tree — Before Any Code Execution

```
Run: clj-nrepl-eval --discover-ports
 ├─ Port found  →  use clj-nrepl-eval -p <port> for ALL evals. Never use bb.
 └─ No port     →  STOP. Tell user to start:  bb nrepl-server 1667
                   Do NOT use `bb` as fallback.
```

## Start nREPL

```shell
bb nrepl-server 1667                  # plain Babashka
start_bb_nREPL_for_cursive.bat        # Cursive / IntelliJ
```

```shell
clj-nrepl-eval --discover-ports       # verify
clj-nrepl-eval -p 1667 "(+ 1 1)"     # smoke-test → 2
```

## References

- Global `interactive-programming.instructions.md` — full rules, `bb` anti-patterns, incremental eval
- Global `clojure-repl-eval.instructions.md` — nREPL tool reference
- Global `clojure-parens-repair.instructions.md` — paren repair
- `babashka-scripting.instructions.md` — API pattern, TDD, conventions

## Debugging

### Cheshire Reload

`:reload`/`:reload-all` of namespaces requiring `cheshire.core` can fail with `Unable to resolve classname: com.fasterxml.jackson.core.JsonGenerator`. Babashka has built-in cheshire; re-eval sometimes tries JVM version.

**Fix:** Define vars directly in REPL session or restart REPL.

### Multiline via clj-nrepl-eval

```powershell
$code = @'
(in-ns 'my.namespace)
(def x (complex-thing))
(println x)
'@
echo $code | clj-nrepl-eval -p <port>
```
