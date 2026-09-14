# hive-olympus

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-olympus.svg)](https://clojars.org/io.github.hive-agi/hive-olympus)
[![release](https://github.com/hive-agi/hive-olympus/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-olympus/actions/workflows/release.yml)

```clojure
io.github.hive-agi/hive-olympus {:mvn/version "RELEASE"}
```

Pin the version from the Clojars badge.

Olympus for every vessel. One vessel-agnostic core (`hive.olympus`) owns the
agent grid; every harness (dsh, Vim, VS Code, Emacs) shows it through a
manifest-only brick.

## The model

```
roster port (0-arity fn -> [Agent])
  -> hive-olympus.layout    canonical grid: 1, 1x2, 2x2 (one blank), then tabs of 4
  -> hive-olympus.model     GridModel: tabs, cells, focus, status counts
  -> hive-olympus.view      one :ui/show-panel per tab ("olympus/tab-N"), standard doc blocks
  -> presenter seat         per presenter: only the delta since its last successful delivery
  -> harness brick          host vessel's :vessel/dispatch! or :vessel/target
```

Layout, model and view are pure and schema-contracted (`hive-olympus.schema`).
Each tab is a panel, so a vessel's own panel switcher is the tab switcher. The
default roster is the live hive swarm (lings at depth 1), resolved lazily with
no compile-time dependency on the host; inject `:olympus/roster-fn` to replace it.

### Hooks of `hive.olympus`

`:olympus/register-presenter!` `(fn [id target])`, `:olympus/unregister-presenter!`
`(fn [id])`, `:olympus/state`, `:olympus/model`, `:olympus/panels`,
`:olympus/presenters`, `:olympus/refresh!`, `:olympus/focus!` `(fn [agent-id])`,
`:olympus/next-tab!`, `:olympus/prev-tab!`. A target is `(fn [ops])`; a throw
marks that presenter degraded and it is retried on the next refresh.

## A harness brick is a manifest

```clojure
{:addon/id "hive.olympus.deepseek"
 :addon/type :native
 :addon/init-ns "hive-olympus.harness"
 :addon/init-fn "addon-ctor"
 :addon/config {:olympus/host "hive.deepseek"}
 :addon/dependencies #{"hive.olympus" "hive.deepseek"}
 :addon/capabilities #{:olympus-presenter :health-reporting}
 :addon/trust-class :foss}
```

At each delivery the brick picks the first route that resolves:

1. `:olympus/target-resolver` (a qualified symbol, `(fn [config] -> hive-vessel Target)`),
   lowered through hive-vessel's standard registry. For hosts with no vessel hooks;
   the built-in `hive-olympus.harness/eval-port-target` covers an eval-port host
   from config alone (`:olympus/eval-fn`, `:olympus/dialect`).
2. The host's `:vessel/dispatch!` hook.
3. The host's `:vessel/target` hook, lowered through hive-vessel's standard registry.

The core loads hive-vessel lazily and does not depend on it. A host that exposes a
vessel hook already has hive-vessel on the classpath. A host reached through
`:olympus/target-resolver` may not (hive.emacs does not), so that brick must declare
`io.github.hive-agi/hive-vessel` in its own deps.edn, or it mounts with no route.

| Brick | Host hooks | Route |
|---|---|---|
| hive-olympus-deepseek | `:vessel/dispatch!` + `:vessel/target` | host dispatch |
| hive-olympus-vim | `:vessel/dispatch!` + `:vessel/target` | host dispatch |
| hive-olympus-vscode | `:vessel/target` | host target |
| hive-olympus-emacs | none | `eval-port-target` over `hive-emacs.client/eval-elisp!` |

## Develop

```
clojure -M:test                  # cold suite
clojure -M:dev                   # REPL classpath (dev/ + test/)
```

## Releases

Every push to `main` that changes `src/`, `resources/`, `test/`, `deps.edn`,
`version.edn` or the workflow runs the suite. When it passes, CI bumps the
patch version, regenerates `CHANGELOG.md`, tags `vX.Y.Z` and deploys to
Clojars through [hive-build](https://github.com/hive-agi/hive-build). A red
suite mints nothing. CI owns `VERSION`: do not bump it by hand.

```
clojure -T:build install         # jar into ~/.m2, no network
clojure -T:build changelog       # what the next release notes will say
```

MIT licensed.
