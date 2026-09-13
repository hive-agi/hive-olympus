# hive-olympus

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
   lowered through hive-vessel's standard registry. For hosts with no vessel hooks.
2. The host's `:vessel/dispatch!` hook.
3. The host's `:vessel/target` hook, lowered through hive-vessel's standard registry.

## Develop

```
clojure -M:test                  # cold suite
clojure -M:dev                   # REPL classpath (dev/ + test/)
```

MIT licensed.
