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
  (let [raw (some-> message
                    (str/replace #"^bb-ling\s+" "")
                    (str/replace shout-separator ": "))
        ;; A failure shout embeds the error map; its :message is the readable part.
        inner (some->> raw (re-seq #":message \"([^\"]+)\"") last second)
        msg (clip (if (and inner (str/includes? raw "{"))
                    (str (first (str/split raw #"\{" 2)) inner)
                    raw))
        event (some-> event-type name)]
    (cond
      (and msg event (not= "progress" event) (not (str/includes? (str/lower-case msg) event)))
      (clip (str event ": " msg))
      msg msg
      event event
      :else nil)))

(def max-activity-lines
  "How many recent shouts an Agent carries for the focus zoom. The grid cells
   show only the latest; the log is what an observer opens on purpose."
  20)

(defn activity-line
  "One SHOUT as a log line, \"4m ago  progress: turn 13\", or nil when it says
   nothing. NOW is epoch ms and may be nil, which drops the age."
  [now {:keys [timestamp] :as shout}]
  (when-let [body (activity-text shout)]
    (if (and now timestamp (pos? timestamp))
      (str (seen-text (- now timestamp)) "  " body)
      body)))

(defn activity-log
  "The recent SHOUTS as log lines, newest first, at most LIMIT of them
   (default `max-activity-lines`). Shouts that say nothing are dropped."
  ([shouts now] (activity-log shouts now max-activity-lines))
  ([shouts now limit]
   (->> shouts
        (sort-by #(or (:timestamp %) 0) >)
        (take limit)
        (keep #(activity-line now %))
        vec)))

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
   NOW epoch ms; both optional. Every agent carries its recent shout log."
  ([slaves] (agents slaves (constantly nil) nil))
  ([slaves shouts-of now]
   (let [live (filter observable? slaves)
         ->agent (fn [slave]
                   (let [shouts (shouts-of (str (:slave/id slave)))
                         agent (slave->agent slave (latest-shout shouts) now)
                         log (activity-log shouts now)]
                     (cond-> agent (seq log) (assoc :agent/recent log))))
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

(def default-linger-ms
  "How long an agent that left the swarm stays visible with its final event."
  120000)

(def ^:private final-event-rank
  "Which shout best says how an agent ended: a failure over an error report
   over a completion over anything else."
  {:failed 3 :error 2 :completed 1})

(defn final-shout
  "The shout of SHOUTS that best says how the agent ended, or nil: the latest
   failure, else the latest error report, else the latest completion, else the
   latest shout."
  [shouts]
  (when (seq shouts)
    (let [rank #(get final-event-rank (:event-type %) 0)
          best (apply max (map rank shouts))]
      (latest-shout (filter #(= best (rank %)) shouts)))))

(defn exited-agent
  "AGENT as last observed, marked exited, with status and activity from its
   final SHOUT (a failure reads as :error, anything else as :idle)."
  [agent shout now]
  (let [failed? (contains? #{:failed :error} (:event-type shout))
        activity (activity-text shout)]
    (cond-> (-> agent
                (assoc :agent/exited? true :agent/status (if failed? :error :idle))
                (dissoc :agent/drones))
      activity (assoc :agent/activity activity)
      (and now (:timestamp shout)) (assoc :agent/seen (seen-text (- now (:timestamp shout)))))))

(defn settle
  "One roster tick with linger. STATE is {:previous agents :ghosts {id ghost}}
   from the last tick; CURRENT the agents observed now. An agent present last
   tick and absent now becomes a ghost built from its final shout; a ghost
   leaves after LINGER-MS or when its id is observed again. Returns the next
   state with :roster, the current agents followed by the ghosts, oldest
   departure first."
  [{:keys [previous ghosts]} current shouts-of now linger-ms]
  (let [cur-ids (into #{} (map :agent/id) current)
        departed (for [a previous
                       :let [id (:agent/id a)]
                       :when (and (not (:agent/exited? a))
                                  (not (contains? cur-ids id))
                                  (not (contains? ghosts id)))]
                   [id {:agent a :since now}])
        ghosts (->> (into (or ghosts {}) departed)
                    (remove (fn [[id {:keys [since]}]]
                              (or (contains? cur-ids id) (> (- now since) linger-ms))))
                    (into {}))
        shown (->> ghosts
                   (sort-by (fn [[id {:keys [since]}]] [since id]))
                   (mapv (fn [[id {:keys [agent]}]]
                           (exited-agent agent (final-shout (shouts-of id)) now))))]
    {:previous current
     :ghosts ghosts
     :roster (into (vec current) shown)}))

(def registry-source
  "Qualified symbol of the host's shout registry. The host drops an agent's
   shouts the moment the agent exits, so the adapter watches this to keep
   them for the linger window."
  'hive-mcp.hivemind.state/agent-registry)

(defn- messages-of [entry]
  (or (:messages entry) (get-in entry [:data :messages])))

(defn departures
  "{agent-id messages} for the agents in registry map OLD that are absent from
   NEW and had messages."
  [old new]
  (into {}
        (keep (fn [[id entry]]
                (when (and (not (contains? new id)) (seq (messages-of entry)))
                  [(str id) (vec (messages-of entry))])))
        old))

(defn- watchable
  "The IRef behind a resolved registry: a var of an atom, an atom, or a bounded
   atom map holding one under :atom."
  [x]
  (let [x (if (var? x) @x x)]
    (cond
      (instance? clojure.lang.IRef x) x
      (and (map? x) (instance? clojure.lang.IRef (:atom x))) (:atom x)
      :else nil)))

(defn live-roster-fn
  "Roster fn over the live swarm. RESOLVE is (fn [sym] -> fn or nil), default
   `requiring-resolve` guarded; ON-WARNING receives a message string (or nil
   once the source resolves again). CLOCK is a 0-arity epoch-ms fn. An agent
   that leaves the swarm lingers LINGER-MS (default `default-linger-ms`, 0
   disables) showing how it ended; its final shouts are kept by a watch on
   the host shout registry, which the fn's :olympus/close metadata removes."
  ([on-warning] (live-roster-fn on-warning nil))
  ([on-warning resolve] (live-roster-fn on-warning resolve nil))
  ([on-warning resolve clock] (live-roster-fn on-warning resolve clock nil))
  ([on-warning resolve clock linger-ms]
   (let [resolve (or resolve (fn [sym] (try (requiring-resolve sym) (catch Throwable _ nil))))
         clock (or clock #(System/currentTimeMillis))
         linger-ms (long (or linger-ms default-linger-ms))
         state (atom {:previous [] :ghosts {}})
         departed (atom {})
         watched (atom nil)
         watch-key (keyword "hive-olympus.roster" (str "departures-" (System/identityHashCode state)))
         watch! (fn []
                  (when (and (pos? linger-ms) (nil? @watched))
                    (when-let [ref (some-> (resolve registry-source) watchable)]
                      (add-watch ref watch-key
                                 (fn [_ _ old new]
                                   (let [gone (departures old new)]
                                     (when (seq gone)
                                       (let [at (clock)]
                                         (swap! departed into (map (fn [[id ms]] [id {:messages ms :at at}])) gone))))))
                      (reset! watched ref))))
         close (fn [] (when-let [ref @watched] (remove-watch ref watch-key) (reset! watched nil)))]
     (with-meta
       (fn []
         (if-let [get-all-slaves (resolve live-source)]
           (let [messages (resolve activity-source)
                 _ (watch!)
                 now (clock)
                 _ (swap! departed (fn [m] (into {} (remove (fn [[_ {:keys [at]}]] (> (- now at) (* 2 linger-ms)))) m)))
                 shouts-of (fn [id]
                             (or (seq (when messages (try (messages id) (catch Throwable _ nil))))
                                 (get-in @departed [id :messages])))
                 current (agents (get-all-slaves) shouts-of now)
                 result (if (pos? linger-ms)
                          (:roster (swap! state settle current shouts-of now linger-ms))
                          current)]
             (on-warning nil)
             result)
           (do (on-warning (str "roster source " live-source " unavailable; showing no agents"))
               [])))
       {:olympus/close close}))))

(m/=> slave->agent [:function
                    [:=> [:cat [:map [:slave/id :any]]] s/Agent]
                    [:=> [:cat [:map [:slave/id :any]] [:maybe :map] [:maybe :int]] s/Agent]])
(m/=> agents [:function
              [:=> [:cat [:sequential :map]] s/Roster]
              [:=> [:cat [:sequential :map] ifn? [:maybe :int]] s/Roster]])
