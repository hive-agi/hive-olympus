(ns hive-olympus.demo
  "A demonstration roster, pure: what a live multi-provider swarm looks like
   to Olympus, for vessel demos and end-to-end checks that must not depend on
   a running swarm. Every harness brick's real-host check reads this one
   roster, so a field the core learns to show is exercised in every vessel."
  (:require [hive-olympus.schema :as s]
            [malli.core :as m]
            [hive-olympus.roster :as roster]))

(def routes
  "provider/model pairs demo agents cycle through."
  [["venice" "deepseek-v4-flash"] ["axon" "glm-5.3-flash"] ["openrouter" "moonshotai/kimi-k2.6"]])

(def statuses
  "Statuses demo agents cycle through."
  [:working :blocked :error :idle :spawning])

(defn roster
  "N demo agents named PREFIX-1 .. PREFIX-N (PREFIX default \"demo\"). They
   cycle through every status (the first one working) and three provider
   routes, and carry what a live roster carries: mode, project, a task or the
   latest activity, and a seen age. Every fifth agent has exited, showing how
   it ended."
  ([n] (roster n "demo"))
  ([n prefix]
   (mapv (fn [i]
           (let [[provider model] (nth routes (mod (dec i) (count routes)))
                 exited? (zero? (mod i 5))]
             (cond-> {:agent/id (str prefix "-" i)
                      :agent/name (str prefix "-" i)
                      :agent/status (if exited? :error (nth statuses (mod (dec i) (count statuses))))
                      :agent/kind :ling
                      :agent/provider provider
                      :agent/model model
                      :agent/mode "hive-agent"
                      :agent/project "hive-olympus"
                      :agent/seen (roster/seen-text (* 60000 (mod i 4)))}
               (even? i) (assoc :agent/task (str "task " i))
               (odd? i) (assoc :agent/activity (str "turn " i ": tool_calls=[read_file]"))
               exited? (assoc :agent/exited? true :agent/activity (str provider " API error: 402")))))
         (range 1 (inc (max 0 n))))))

(m/=> roster [:function
              [:=> [:cat :int] s/Roster]
              [:=> [:cat :int :string] s/Roster]])
