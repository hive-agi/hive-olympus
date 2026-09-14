(ns hive-olympus.addon
  "hive.olympus IAddon: polls the roster, renders the canonical grid as panels,
   and delivers the delta to every presenter registered on its seat.

   Config (manifest :addon/config merged with runtime config):
     :olympus/refresh-ms  poll period in ms (default 2000; <= 0 disables the loop)
     :olympus/roster-fn   0-arity fn -> seq of Agent, or a qualified symbol
                          naming one (default: the live swarm adapter)
     :olympus/linger-ms   how long the live adapter keeps an agent that left
                          the swarm, showing how it ended (default 120000; 0 off)

   Hooks (active only):
     :olympus/register-presenter!    (fn [id target]) -> id | nil
     :olympus/unregister-presenter!  (fn [id]) -> id | nil
     :olympus/state                  (fn []) -> OlympusState
     :olympus/model                  (fn []) -> GridModel
     :olympus/panels                 (fn []) -> current :ui/show-panel ops
     :olympus/presenters             (fn []) -> presenter id -> delivery status
     :olympus/refresh!               (fn []) -> presenter status map
     :olympus/focus!                 (fn [agent-id-or-nil]) -> OlympusState
     :olympus/next-tab!              (fn []) -> OlympusState
     :olympus/prev-tab!              (fn []) -> OlympusState"
  (:require [hive-addon.protocol :as addon]
            [hive-olympus.model :as model]
            [hive-olympus.presenter :as presenter]
            [hive-olympus.roster :as roster]
            [hive-olympus.view :as view])
  (:import (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)))

(def addon-id-value "hive.olympus")

(def default-refresh-ms 2000)

(defn- roster-fn-of [config on-warning]
  (let [f (:olympus/roster-fn config)]
    (cond
      (fn? f) f
      (symbol? f) (let [v (requiring-resolve f)] (fn [] (v)))
      (string? f) (let [v (requiring-resolve (symbol f))] (fn [] (v)))
      :else (roster/live-roster-fn on-warning nil nil (:olympus/linger-ms config)))))

(defn- refresh-locked!
  "Poll, render and broadcast. Caller holds the state lock."
  [state]
  (let [{:keys [roster-fn seat olympus]} @state]
    (try
      (let [agents (vec (roster-fn))
            grid (model/grid-model agents @olympus)
            current (view/panels grid)]
        (swap! state assoc :roster agents :model grid :panels current :roster-error nil)
        (presenter/broadcast! seat current))
      (catch Throwable t
        (swap! state assoc :roster-error (or (ex-message t) (str t)))
        (presenter/broadcast! seat (:panels @state))))
    (presenter/status seat)))

(defn- navigate! [state f]
  (locking state
    (when (= :active (:lifecycle @state))
      (let [{:keys [olympus roster]} @state]
        (swap! olympus f roster)
        (refresh-locked! state)
        @olympus))))

(defn- daemon-factory []
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. ^Runnable r "hive-olympus-refresh")
        (.setDaemon true)))))

(defn- start-loop [state refresh-ms]
  (when (pos? refresh-ms)
    (doto (Executors/newSingleThreadScheduledExecutor (daemon-factory))
      (.scheduleWithFixedDelay
       (fn [] (try (locking state
                     (when (= :active (:lifecycle @state))
                       (refresh-locked! state)))
                   (catch Throwable _ nil)))
       (long refresh-ms) (long refresh-ms) TimeUnit/MILLISECONDS))))

(defn- start! [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (try
        (let [config (merge seed runtime-config)
              refresh-ms (long (or (:olympus/refresh-ms config) default-refresh-ms))
              warn! (fn [msg] (swap! state assoc :roster-warning msg))
              seat (presenter/create)]
          (reset! state {:lifecycle :active
                         :refresh-ms refresh-ms
                         :roster-fn (roster-fn-of config warn!)
                         :olympus (atom model/initial-state)
                         :seat seat
                         :roster []
                         :panels (view/panels (model/grid-model [] model/initial-state))})
          (refresh-locked! state)
          (swap! state assoc :executor (start-loop state refresh-ms))
          {:success? true :metadata {:refresh-ms refresh-ms
                                     :agents (count (:roster @state))}})
        (catch Throwable t
          (reset! state {:lifecycle :failed :last-error (or (ex-message t) (str t))})
          {:success? false :errors [(or (ex-message t) (str t))]})))))

(defn- stop! [state]
  (let [^ScheduledExecutorService executor (:executor @state)]
    (when executor
      (.shutdownNow executor)
      (.awaitTermination executor 2 TimeUnit/SECONDS))
    (locking state
      (when-let [close (some-> (:roster-fn @state) meta :olympus/close)]
        (try (close) (catch Throwable _ nil)))
      (reset! state {:lifecycle :stopped})
      nil)))

(defn- register-presenter! [state id target]
  (locking state
    (when (= :active (:lifecycle @state))
      (presenter/register! (:seat @state) id target (:panels @state)))))

(defn- unregister-presenter! [state id]
  (locking state
    (when-let [seat (:seat @state)]
      (presenter/unregister! seat id))))

(defn- tab-count [state]
  (count (get-in @state [:model :grid/tabs] [nil])))

(defrecord OlympusAddon [state seed]
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:olympus :presenter-seat :health-reporting})
  (initialize! [_ runtime-config] (start! state seed runtime-config))
  (shutdown! [_] (stop! state))
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_]
    (let [{:keys [lifecycle seat model refresh-ms roster-error roster-warning last-error]} @state
          presenters (if seat (presenter/status seat) {})
          degraded? (or roster-error roster-warning
                        (some #(= :degraded (:status %)) (vals presenters)))]
      {:status (case lifecycle
                 :active (if degraded? :degraded :ok)
                 :failed :down
                 :degraded)
       :details (cond-> {:lifecycle lifecycle}
                  (= :active lifecycle)
                  (assoc :refresh-ms refresh-ms
                         :agents (get-in model [:grid/counts :total] 0)
                         :tabs (count (:grid/tabs model))
                         :layout (:grid/layout model)
                         :presenters presenters)
                  roster-error (assoc :roster-error roster-error)
                  roster-warning (assoc :roster-warning roster-warning)
                  last-error (assoc :last-error last-error))}))
  (excluded-tools [_] #{})
  (hooks [_]
    (if (= :active (:lifecycle @state))
      {:olympus/register-presenter! (fn [id target] (register-presenter! state id target))
       :olympus/unregister-presenter! (fn [id] (unregister-presenter! state id))
       :olympus/state (fn [] (some-> (:olympus @state) deref))
       :olympus/model (fn [] (:model @state))
       :olympus/panels (fn [] (:panels @state))
       :olympus/presenters (fn [] (presenter/status (:seat @state)))
       :olympus/refresh! (fn [] (locking state
                                  (when (= :active (:lifecycle @state))
                                    (refresh-locked! state))))
       :olympus/focus! (fn [agent-id]
                         (navigate! state (fn [st agents] (model/focus st agents agent-id))))
       :olympus/next-tab! (fn [] (navigate! state (fn [st _] (model/next-tab st (tab-count state)))))
       :olympus/prev-tab! (fn [] (navigate! state (fn [st _] (model/prev-tab st (tab-count state)))))}
      {})))

(defn addon-ctor
  "Pure constructor resolved from the mount manifest."
  [config]
  (->OlympusAddon (atom {:lifecycle :created}) (or config {})))