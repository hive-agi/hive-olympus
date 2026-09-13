(ns hive-olympus.roster
  "The roster port: a 0-arity fn returning a seq of Agent.

   `slave->agent` promotes a swarm slave map to an Agent. `live-roster-fn`
   is the default adapter: it resolves the host swarm's
   `hive-mcp.swarm.datascript.queries/get-all-slaves` lazily at call time,
   keeps lings (:slave/depth 1) that are not in :error, and degrades to []
   while the host is absent, reporting through ON-WARNING. No compile-time
   dependency on the host."
  (:require [clojure.string :as str]
            [hive-olympus.schema :as s]
            [malli.core :as m]))

(def slave-status->status
  "Swarm slave status -> Agent status. Unknown statuses read as :idle."
  {:idle :idle
   :working :working
   :blocked :blocked
   :error :error
   :spawning :spawning
   :starting :spawning
   :initializing :spawning
   :zombie :error
   :terminated :error})

(def live-source
  "Qualified symbol of the host query the live adapter resolves."
  'hive-mcp.swarm.datascript.queries/get-all-slaves)

(defn- task-text [task]
  (cond
    (string? task) (when-not (str/blank? task) task)
    (map? task) (some (fn [k] (let [v (get task k)] (when (and (string? v) (not (str/blank? v))) v)))
                      [:task/title :task/description :task/name :title :description])
    :else nil))

(defn slave->agent
  "Agent for the swarm SLAVE map."
  [slave]
  (let [id (str (:slave/id slave))
        nm (:slave/name slave)
        task (task-text (:slave/current-task slave))]
    (cond-> {:agent/id id
             :agent/name (if (and (string? nm) (not (str/blank? nm))) nm id)
             :agent/status (get slave-status->status (:slave/status slave) :idle)}
      task (assoc :agent/task task))))

(defn lings
  "Agents for the lings among SLAVES: depth 1, not in :error, with an id."
  [slaves]
  (->> slaves
       (filter #(= 1 (:slave/depth %)))
       (remove #(= :error (:slave/status %)))
       (filter #(some? (:slave/id %)))
       (mapv slave->agent)))

(defn live-roster-fn
  "Roster fn over the live swarm. RESOLVE is (fn [sym] -> fn or nil), default
   `requiring-resolve` guarded; ON-WARNING receives a message string (or nil
   once the source resolves again)."
  ([on-warning] (live-roster-fn on-warning nil))
  ([on-warning resolve]
   (let [resolve (or resolve (fn [sym] (try (requiring-resolve sym) (catch Throwable _ nil))))]
     (fn []
       (if-let [get-all-slaves (resolve live-source)]
         (let [agents (lings (get-all-slaves))]
           (on-warning nil)
           agents)
         (do (on-warning (str "roster source " live-source " unavailable; showing no agents"))
             []))))))

(m/=> slave->agent [:=> [:cat [:map [:slave/id :any]]] s/Agent])
(m/=> lings [:=> [:cat [:sequential :map]] s/Roster])
