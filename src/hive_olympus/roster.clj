(ns hive-olympus.roster
  "The roster port: a 0-arity fn returning a seq of Agent.

   `slave->agent` promotes a swarm slave map to an Agent, enriched with the
   latest hivemind shout of that agent when one is known. `agents` keeps the
   observable members of a swarm: lings and drones that are still alive, each
   ling followed by its own drones. A failed agent stays visible, because an
   observer has to see failure; only the dead (terminated, zombie, or marked
   not alive) are dropped.

   `live-roster-fn` is the default adapter. It resolves the host swarm's
   `hive-mcp.swarm.datascript.queries/get-all-slaves`, and optionally
   `hive-mcp.hivemind.status/get-agent-messages`, lazily at call time, and
   degrades to [] while the host is absent, reporting through ON-WARNING. No
   compile-time dependency on the host."
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

(def dead-statuses
  "Slave statuses that mean the agent is gone, not failing."
  #{:terminated :zombie})

(def live-source
  "Qualified symbol of the host query the live adapter resolves."
  'hive-mcp.swarm.datascript.queries/get-all-slaves)

(def activity-source
  "Qualified symbol of the optional host query for an agent's recent shouts."
  'hive-mcp.hivemind.status/get-agent-messages)

(def max-text
  "Longest task or activity text an Agent carries."
  160)

(defn- clip [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [s (str/trim (str/replace s #"\s+" " "))]
      (if (> (count s) max-text)
        (str (subs s 0 (dec max-text)) "…")
        s))))

(defn- text [v]
  (cond
    (string? v) (clip v)
    (keyword? v) (name v)
    (some? v) (str v)
    :else nil))

(defn- task-text [task]
  (cond
    (string? task) (clip task)
    (map? task) (some (fn [k] (clip (get task k)))
                      [:task/title :task/description :task/name :title :description])
    :else nil))

(defn- parent-id [parent]
  (cond
    (string? parent) parent
    (map? parent) (some-> (:slave/id parent) str)
    :else nil))

(defn seen-text
  "Coarse age of AGE-MS, e.g. \"<1m ago\", \"4m ago\", \"2h ago\". Minute
   resolution on purpose: a panel is re-sent only when its text changes."
  [age-ms]
  (let [minutes (quot (max 0 (long age-ms)) 60000)]
    (cond
      (< minutes 1) "<1m ago"
      (< minutes 60) (str minutes "m ago")
      :else (str (quot minutes 60) "h ago"))))

(defn latest-shout
  "The most recent of SHOUTS by :timestamp, or nil."
  [shouts]
  (when (seq shouts)
    (apply max-key #(or (:timestamp %) 0) shouts)))

(def ^:private shout-separator
  "bb-ling separates the parts of a shout with a spaced U+2014."
  (re-pattern (str "\\s+" (char 0x2014) "\\s+")))

(defn- activity-text [{:keys [event-type message]}]
  (let [msg (some-> message
                    (str/replace #"^bb-ling\s+" "")
                    (str/replace shout-separator ": ")
                    clip)
        event (some-> event-type name)]
    (cond
      (and msg event (not= "progress" event) (not (str/includes? (str/lower-case msg) event)))
      (clip (str event ": " msg))
      msg msg
      event event
      :else nil)))

(defn slave->agent
  "Agent for the swarm SLAVE map. SHOUT is its latest hivemind shout or nil;
   NOW is epoch ms, used only to age the last activity."
  ([slave] (slave->agent slave nil nil))
  ([slave shout now]
   (let [id (str (:slave/id slave))
         nm (:slave/name slave)
         depth (:slave/depth slave)
         task (or (task-text (:slave/current-task slave)) (clip (:task shout)))
         last-ms (max (or (:slave/last-active-at slave) 0) (or (:timestamp shout) 0))
         done (:slave/tasks-completed slave)]
     (cond-> {:agent/id id
              :agent/name (if (and (string? nm) (not (str/blank? nm))) nm id)
              :agent/status (get slave-status->status (:slave/status slave) :idle)}
       task (assoc :agent/task task)
       (= 1 depth) (assoc :agent/kind :ling)
       (= 2 depth) (assoc :agent/kind :drone)
       (parent-id (:slave/parent slave)) (assoc :agent/parent (parent-id (:slave/parent slave)))
       (text (or (:ling/model slave) (:slave/model slave))) (assoc :agent/model (text (or (:ling/model slave) (:slave/model slave))))
       (text (:ling/provider slave)) (assoc :agent/provider (text (:ling/provider slave)))
       (text (:ling/spawn-mode slave)) (assoc :agent/mode (text (:ling/spawn-mode slave)))
       (text (:slave/project-id slave)) (assoc :agent/project (text (:slave/project-id slave)))
       (activity-text shout) (assoc :agent/activity (activity-text shout))
       (and now (pos? last-ms)) (assoc :agent/seen (seen-text (- now last-ms)))
       (nat-int? done) (assoc :agent/done done)))))

(defn observable?
  "True for a live ling or drone with an id."
  [slave]
  (and (#{1 2} (:slave/depth slave))
       (some? (:slave/id slave))
       (not (false? (:slave/alive? slave)))
       (not (contains? dead-statuses (:slave/status slave)))))

(defn agents
  "Agents for the observable members of SLAVES, each ling followed by its
   drones, a ling carrying its live drone count. Drones whose ling is not
   observable follow the lings. SHOUTS-OF is (fn [agent-id] -> shouts) and
   NOW epoch ms; both optional."
  ([slaves] (agents slaves (constantly nil) nil))
  ([slaves shouts-of now]
   (let [live (filter observable? slaves)
         ->agent #(slave->agent % (latest-shout (shouts-of (str (:slave/id %)))) now)
         lings (filterv #(= 1 (:slave/depth %)) live)
         drones (mapv ->agent (filter #(= 2 (:slave/depth %)) live))
         by-parent (group-by :agent/parent drones)
         ling-ids (into #{} (map #(str (:slave/id %))) lings)]
     (-> []
         (into (mapcat (fn [slave]
                         (let [agent (->agent slave)
                               own (get by-parent (:agent/id agent))]
                           (cons (cond-> agent (seq own) (assoc :agent/drones (count own)))
                                 own))))
               lings)
         (into (remove #(contains? ling-ids (:agent/parent %))) drones)))))

(defn live-roster-fn
  "Roster fn over the live swarm. RESOLVE is (fn [sym] -> fn or nil), default
   `requiring-resolve` guarded; ON-WARNING receives a message string (or nil
   once the source resolves again). CLOCK is a 0-arity epoch-ms fn."
  ([on-warning] (live-roster-fn on-warning nil))
  ([on-warning resolve] (live-roster-fn on-warning resolve nil))
  ([on-warning resolve clock]
   (let [resolve (or resolve (fn [sym] (try (requiring-resolve sym) (catch Throwable _ nil))))
         clock (or clock #(System/currentTimeMillis))]
     (fn []
       (if-let [get-all-slaves (resolve live-source)]
         (let [messages (resolve activity-source)
               shouts-of (if messages
                           (fn [id] (try (messages id) (catch Throwable _ nil)))
                           (constantly nil))
               result (agents (get-all-slaves) shouts-of (clock))]
           (on-warning nil)
           result)
         (do (on-warning (str "roster source " live-source " unavailable; showing no agents"))
             []))))))

(m/=> slave->agent [:function
                    [:=> [:cat [:map [:slave/id :any]]] s/Agent]
                    [:=> [:cat [:map [:slave/id :any]] [:maybe :map] [:maybe :int]] s/Agent]])
(m/=> agents [:function
              [:=> [:cat [:sequential :map]] s/Roster]
              [:=> [:cat [:sequential :map] ifn? [:maybe :int]] s/Roster]])
