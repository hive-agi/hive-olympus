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
            [hive-olympus.view :as view]
            [hive-olympus.operator :as operator]
            [hive-spi.vessel :as render-port]
            [hive-spi.notify :as notify]
            [hive-vessel.renderer :as renderer]
            [hive-olympus.lens :as lens])
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

(defn- refresh-renderers! [state]
  (let [{:keys [renderer-source registered-renderers seat panels]} @state
        current (if renderer-source @renderer-source {})]
    (doseq [id (keys registered-renderers) :when (not (contains? current id))]
      (presenter/unregister! seat id))
    (doseq [[id target] current
            :when (not (identical? target (get registered-renderers id)))]
      (presenter/register! seat id
        (fn [ops]
          (let [result (render-port/render! target ops)]
            (when (:error result)
              (throw (ex-info "Vessel rendering failed" (:error result))))
            result))
        panels))
    (swap! state assoc :registered-renderers current)))

(defn- refresh-locked!
  "Refresh independent observations, render and broadcast. Caller holds the state lock."
  [state]
  (let [{:keys [roster-fn seat olympus operator-room lenses]} @state]
    (try
      (let [agents (vec (roster-fn))]
        (swap! state assoc :roster agents :roster-error nil))
      (catch Throwable t
        (swap! state assoc :roster-error (or (ex-message t) (str t)))))
    (when operator-room
      (try
        (swap! state assoc :operator-snapshot ((:snapshot operator-room)) :operator-error nil)
        (catch Throwable t
          (swap! state assoc :operator-error (or (ex-message t) (str t))))))
    (let [{:keys [roster operator-snapshot operator-error]} @state
          grid (model/grid-model roster @olympus)
          sections (when-let [cell (model/focused-cell grid)]
                     (lens/observe (if lenses @lenses {}) (:cell/agent cell)))
          current (cond-> (view/panels grid)
                    sections
                    (conj (view/focus-panel grid sections))
                    operator-room
                    (conj (operator/panel
                           (cond-> (or operator-snapshot {:requests [] :events []})
                             operator-error (update :unavailable (fnil conj []) :snapshot)))))]
      (swap! state assoc :model grid :panels current :lens-sections sections)
      (refresh-renderers! state)
      (presenter/broadcast! seat current))
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
  (let [executor (Executors/newSingleThreadScheduledExecutor (daemon-factory))]
    (when (pos? refresh-ms)
      (.scheduleWithFixedDelay
       executor
       (fn [] (try (locking state
                     (when (= :active (:lifecycle @state))
                       (refresh-locked! state)))
                   (catch Throwable _ nil)))
       (long refresh-ms) (long refresh-ms) TimeUnit/MILLISECONDS))
    executor))

(defn- request-refresh! [state]
  (let [{:keys [executor refresh-pending]} @state]
    (when (and executor refresh-pending (compare-and-set! refresh-pending false true))
      (try
        (.execute ^ScheduledExecutorService executor
                  (fn []
                    (reset! refresh-pending false)
                    (locking state
                      (when (and (= :active (:lifecycle @state))
                                 (identical? executor (:executor @state)))
                        (refresh-locked! state)))))
        (catch java.util.concurrent.RejectedExecutionException _
          (reset! refresh-pending false))))))

(defn- start! [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (try
        (let [config (merge seed runtime-config)
              refresh-ms (long (or (:olympus/refresh-ms config) default-refresh-ms))
              warn! (fn [msg] (swap! state assoc :roster-warning msg))
              renderer-source (or (:vessel/renderers config) renderer/renderers)
              renderer-watch (Object.)
              seat (presenter/create)]
          (reset! state {:lifecycle :active
                         :refresh-ms refresh-ms
                         :roster-fn (roster-fn-of config warn!)
                         :olympus (atom model/initial-state)
                         :seat seat
                         :lenses (lens/create)
                         :refresh-pending (atom false)
                         :renderer-source renderer-source
                         :renderer-watch renderer-watch
                         :registered-renderers {}
                         :roster []
                         :panels (view/panels (model/grid-model [] model/initial-state))})
          (swap! state assoc :executor (start-loop state refresh-ms))
          (when (get config :olympus/operator-room?
                     (or (contains? config :olympus/operator-sources)
                         (not (contains? config :olympus/roster-fn))))
            (swap! state assoc :operator-room
                   (operator/open config (fn [] (request-refresh! state)))))
          (add-watch renderer-source renderer-watch
                     (fn [_ _ before after]
                       (when (not= before after) (request-refresh! state))))
          (refresh-locked! state)
          {:success? true :metadata {:refresh-ms refresh-ms
                                     :agents (count (:roster @state))}})
        (catch Throwable t
          (when-let [source (:renderer-source @state)]
            (remove-watch source (:renderer-watch @state)))
          (when-let [close (get-in @state [:operator-room :close])] (close))
          (when-let [executor (:executor @state)]
            (.shutdownNow ^ScheduledExecutorService executor))
          (reset! state {:lifecycle :failed :last-error (or (ex-message t) (str t))})
          {:success? false :errors [(or (ex-message t) (str t))]})))))

(defn- stop! [state]
  (let [^ScheduledExecutorService executor (:executor @state)]
    (when-let [source (:renderer-source @state)]
      (remove-watch source (:renderer-watch @state)))
    (when executor
      (.shutdownNow executor)
      (.awaitTermination executor 2 TimeUnit/SECONDS))
    (locking state
      (when-let [close (get-in @state [:operator-room :close])] (close))
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

(defn- register-lens!
  "Register LENS under ID and re-render, so a focused agent shows it at once."
  [state id lens]
  (locking state
    (when (= :active (:lifecycle @state))
      (when-let [registered (lens/register! (:lenses @state) id lens)]
        (refresh-locked! state)
        registered))))

(defn- unregister-lens!
  [state id]
  (locking state
    (when-let [lenses (:lenses @state)]
      (when-let [removed (lens/unregister! lenses id)]
        (when (= :active (:lifecycle @state)) (refresh-locked! state))
        removed))))

(defn- lens-status
  "id -> status of every registered lens: its last observation, or :idle
   while no agent is focused."
  [{:keys [lenses lens-sections]}]
  (let [observed (lens/status (or lens-sections []))]
    (into {} (map (fn [id] [id (get observed id :idle)])) (if lenses (lens/ids lenses) []))))

(defn- tab-count [state]
  (count (get-in @state [:model :grid/tabs] [nil])))

(defrecord OlympusAddon [state seed]
  notify/INotify
  (notify-id [_] :olympus)
  (backend-available? [_] (and (= :active (:lifecycle @state))
                              (some? (:operator-room @state))))
  (accepts? [_ event-type] (qualified-keyword? event-type))
  (notify! [_ notification]
    (try
      (let [observe (get-in @state [:operator-room :observe!])
            delivered? (boolean
                        (when observe
                          (observe (assoc notification
                                          :message (or (:summary notification) (:body notification))))))]
        {:delivered? delivered? :backend :olympus
         :detail (if delivered? {} {:reason :unavailable})})
      (catch Throwable t
        {:delivered? false :backend :olympus
         :detail {:reason :observation-failed :message (ex-message t)}})))
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:olympus :presenter-seat :lens-seat :health-reporting})
  (initialize! [_ runtime-config] (start! state seed runtime-config))
  (shutdown! [_] (stop! state))
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_]
    (let [{:keys [lifecycle seat model refresh-ms roster-error roster-warning operator-error operator-snapshot last-error] :as s} @state
          presenters (if seat (presenter/status seat) {})
          lenses (lens-status s)
          degraded? (or roster-error roster-warning operator-error (seq (:unavailable operator-snapshot))
                        (some #(= :degraded (:status %)) (vals presenters))
                        (some #(= :error %) (vals lenses)))]
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
                         :focus (:grid/focus model)
                         :presenters presenters
                         :lenses lenses
                         :operator-room (when operator-snapshot
                                          {:pending (count (:requests operator-snapshot))
                                           :events (count (:events operator-snapshot))
                                           :unavailable (:unavailable operator-snapshot)}))
                  operator-error (assoc :operator-error operator-error)
                  roster-error (assoc :roster-error roster-error)
                  roster-warning (assoc :roster-warning roster-warning)
                  last-error (assoc :last-error last-error))}))
  (excluded-tools [_] #{})
  (hooks [_]
    (if (= :active (:lifecycle @state))
      {:olympus/register-presenter! (fn [id target] (register-presenter! state id target))
       :olympus/unregister-presenter! (fn [id] (unregister-presenter! state id))
       :olympus/register-lens! (fn [id lens] (register-lens! state id lens))
       :olympus/unregister-lens! (fn [id] (unregister-lens! state id))
       :olympus/lenses (fn [] (lens-status @state))
       :olympus/operator-snapshot (fn [] (:operator-snapshot @state))
       :olympus/observe! (fn [event]
                           (when-let [observe (get-in @state [:operator-room :observe!])]
                             (observe event)))
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