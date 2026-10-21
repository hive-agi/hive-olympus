(ns hive-olympus.changes
  "Push source for roster changes, so the grid repaints when the swarm
   changes instead of on a fixed poll.

   The host's swarm store announces slave-row changes through a subscribe
   fn resolved by symbol, never required: when it is absent the caller keeps
   polling at its configured period.")

(def subscribe-source
  "Qualified symbol of the host's (fn [key on-change]) slave-change subscription."
  'hive-datascript.swarm.slave-index/subscribe!)

(def unsubscribe-source
  "Qualified symbol of the matching (fn [key]) unsubscribe."
  'hive-datascript.swarm.slave-index/unsubscribe!)

(def default-idle-refresh-ms
  "Tick period while change pushes drive the repaints. It only keeps
   time-based text current: ages, lingering exits, staleness."
  30000)

(def debounce-ms
  "How long a pushed change waits before the repaint, so a burst of swarm
   transactions costs one refresh."
  200)

(defn- guarded-resolve [sym]
  (try (requiring-resolve sym)
       (catch Throwable _host-without-push-source-means-poll-mode nil)))

(defn subscribe!
  "Subscribe ON-CHANGE (no args) under KEY to the host's slave changes.
   RESOLVE is (fn [sym] -> fn or nil), default a guarded `requiring-resolve`.
   Returns a 0-arity close fn, or nil when the host offers no push source."
  ([key on-change] (subscribe! key on-change guarded-resolve))
  ([key on-change resolve]
   (let [sub (resolve subscribe-source)
         unsub (resolve unsubscribe-source)]
     (when (and sub unsub)
       (sub key on-change)
       (fn [] (unsub key))))))

(defn tick-ms
  "Refresh period from CONFIG: the idle period when PUSHED?, else the poll
   period POLL-MS."
  [config pushed? poll-ms]
  (if pushed?
    (long (or (:olympus/idle-refresh-ms config) default-idle-refresh-ms))
    poll-ms))
