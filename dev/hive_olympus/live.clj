(ns hive-olympus.live
  "REPL helpers for a live hive that has hive.olympus mounted: read the core's
   hooks, and run a stub-roster demo through the real hive.deepseek bridge,
   restoring the live panels afterwards."
  (:require [hive-addon.protocol :as addon]
            [hive-olympus.addon :as olympus]
            [hive-olympus.harness :as harness]
            [hive-olympus.view :as view]))

(defn registered
  "The live addon instance registered under ID."
  [id]
  ((requiring-resolve 'hive-mcp.addons.core/get-addon) id))

(defn hooks
  "Hooks of the live addon ID."
  [id]
  (addon/hooks (registered id)))

(def stub-routes
  "provider/model pairs a stub agent cycles through."
  [["venice" "deepseek-v4-flash"] ["axon" "glm-5.3-flash"] ["openrouter" "moonshotai/kimi-k2.6"]])

(defn stub-roster
  "N agents cycling through every status and three provider routes, with the
   facts a live roster carries (mode, project, activity, seen). Every fifth is
   an exited agent showing how it ended."
  [n]
  (mapv (fn [i]
          (let [[provider model] (nth stub-routes (mod i (count stub-routes)))
                exited? (zero? (mod i 5))]
            (cond-> {:agent/id (str "demo-" i)
                     :agent/name (str "demo-" i)
                     :agent/status (if exited? :error (nth [:working :blocked :error :idle :spawning] (mod i 5)))
                     :agent/kind :ling
                     :agent/provider provider
                     :agent/model model
                     :agent/mode "hive-agent"
                     :agent/project "hive-olympus"
                     :agent/seen (str (mod i 4) "m ago")}
              (even? i) (assoc :agent/task (str "task " i))
              (odd? i) (assoc :agent/activity (str "turn " i ": tool_calls=[read_file]"))
              exited? (assoc :agent/exited? true :agent/activity (str provider " API error: 402")))))
        (range 1 (inc n))))

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
