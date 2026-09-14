(ns hive-olympus.live
  "REPL helpers for a live hive that has hive.olympus mounted: read the core's
   hooks, and run a stub-roster demo through the real hive.deepseek bridge,
   restoring the live panels afterwards."
  (:require [hive-addon.protocol :as addon]
            [hive-olympus.addon :as olympus]
            [hive-olympus.harness :as harness]
            [hive-olympus.view :as view]
            [hive-olympus.demo :as demo]))

(defn registered
  "The live addon instance registered under ID."
  [id]
  ((requiring-resolve 'hive-mcp.addons.core/get-addon) id))

(defn hooks
  "Hooks of the live addon ID."
  [id]
  (addon/hooks (registered id)))

(defn stub-roster
  "The demo roster of N agents (hive-olympus.demo/roster)."
  [n]
  (demo/roster n))

(defn stub-demo!
  "Mount a throwaway core over a stub roster of N agents plus a harness onto
   the live hive.deepseek, deliver once, shut both down, then restore the live
   core's panels on the bridge. Returns what the demo delivered."
  [n]
  (let [deepseek (registered "hive.deepseek")
        core (olympus/addon-ctor {:olympus/refresh-ms 0 :olympus/roster-fn (fn [] (stub-roster n))})
        _ (addon/initialize! core {})
        cfg {:olympus/host "hive.deepseek"
             :olympus/presenter-id "olympus-demo"
             :mount/dependencies {"hive.olympus" core "hive.deepseek" deepseek}}
        brick (harness/addon-ctor cfg)
        init (addon/initialize! brick cfg)
        demo-panels ((:olympus/panels (addon/hooks core)))
        status ((:olympus/presenters (addon/hooks core)))]
    (addon/shutdown! brick)
    (addon/shutdown! core)
    (let [live-panels ((:olympus/panels (hooks "hive.olympus")))
          restore (view/delta demo-panels live-panels)]
      ((:vessel/dispatch! (addon/hooks deepseek)) restore)
      {:init init
       :presenters status
       :demo-panel-ids (mapv :panel/id demo-panels)
       :restored (mapv (juxt :op :panel/id) restore)})))

(comment
  ((:olympus/model (hooks "hive.olympus")))
  (addon/health (registered "hive.olympus"))
  (addon/health (registered "hive.olympus.deepseek"))
  (stub-demo! 6))
