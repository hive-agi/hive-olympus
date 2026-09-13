(ns hive-olympus.harness
  "The generic per-harness projection: a harness brick is a manifest whose
   :addon/init-ns is this namespace, :addon/init-fn \"addon-ctor\", and whose
   :addon/config names the host vessel addon:

     {:olympus/host \"hive.deepseek\"}           required
     :olympus/presenter-id                      default: the host id
     :olympus/addon-id                          default: \"hive.olympus.\" + host id
                                                without its \"hive.\" prefix
     :olympus/target-resolver                   optional qualified symbol of
                                                (fn [config] -> hive-vessel Target | nil);
                                                `eval-port-target` is the built-in one for
                                                hosts reached through an eval fn
                                                (:olympus/eval-fn, :olympus/dialect)

   with :addon/dependencies #{\"hive.olympus\" <host>}.

   On initialize! it registers one presenter on hive.olympus. The presenter
   resolves its delivery route at EVERY delivery by folding `default-routes`
   (first route that resolves wins):

     :configured-resolver  :olympus/target-resolver -> Target, lowered through
                           hive-vessel's standard registry
     :host-dispatch        the host's :vessel/dispatch! hook
     :host-target          the host's :vessel/target hook (a Target or a 0-arity
                           fn returning one), lowered through hive-vessel's
                           standard registry

   hive-vessel is resolved lazily; nothing here compile-depends on it. A
   delivery with no route, or whose result is {:error ...}, throws, which
   hive.olympus records as a degraded presenter and retries."
  (:require [clojure.string :as str]
            [hive-addon.protocol :as addon]))

(def olympus-addon-id "hive.olympus")

(defprotocol IDeliveryRoute
  (route-id [route] "Keyword naming the route.")
  (resolve-route [route ctx]
    "(fn [ops] -> result) when this route can deliver for CTX, else nil. CTX is
     {:config .. :host-hooks {..} :resolve (fn [qualified-sym] -> value | nil)}."))

(defn- hook-value [hooks k]
  (let [v (get hooks k)]
    (if (fn? v) (v) v)))

(defn- standard-lowering
  "(fn [ops]) dispatching through hive-vessel's standard registry to TARGET,
   or nil when hive-vessel or TARGET is unavailable."
  [resolve target]
  (when (and (map? target) (fn? (:vessel/execute! target)))
    (let [dispatch! (resolve 'hive-vessel.core/dispatch!)
          standard-registry (resolve 'hive-vessel.core/standard-registry)]
      (when (and dispatch! standard-registry)
        (fn [ops] (dispatch! (standard-registry) target ops))))))

(def configured-resolver
  (reify IDeliveryRoute
    (route-id [_] :configured-resolver)
    (resolve-route [_ {:keys [config resolve]}]
      (when-let [sym (:olympus/target-resolver config)]
        (when-let [resolver (resolve (symbol sym))]
          (standard-lowering resolve (resolver config)))))))

(def host-dispatch
  (reify IDeliveryRoute
    (route-id [_] :host-dispatch)
    (resolve-route [_ {:keys [host-hooks]}]
      (when-let [dispatch! (get host-hooks :vessel/dispatch!)]
        (when (fn? dispatch!)
          (fn [ops] (dispatch! ops)))))))

(def host-target
  (reify IDeliveryRoute
    (route-id [_] :host-target)
    (resolve-route [_ {:keys [host-hooks resolve]}]
      (standard-lowering resolve (hook-value host-hooks :vessel/target)))))

(def default-routes
  "Route precedence: an explicit resolver, then the host's own dispatch, then
   the host's bare target."
  [configured-resolver host-dispatch host-target])

(defn select-route
  "{:route id :deliver fn} for the first of ROUTES resolving CTX, or nil."
  [routes ctx]
  (some (fn [route]
          (when-let [f (resolve-route route ctx)]
            {:route (route-id route) :deliver f}))
        routes))

(defn- safe-resolve [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn- dependency [config id]
  (let [dep (get (:mount/dependencies config) id)]
    (when (addon/addon? dep) dep)))

(defn- hooks-of [dep]
  (or (when dep (try (addon/hooks dep) (catch Throwable _ nil))) {}))

(defn harness-addon-id
  "Addon id for the harness projecting Olympus into HOST-ID."
  [host-id]
  (str olympus-addon-id "." (str/replace-first (str host-id) #"^hive\." "")))

(defn eval-port-target
  "A :olympus/target-resolver for hosts reached through an eval port rather
   than vessel hooks. Reads :olympus/eval-fn (qualified symbol of
   (fn [payload] -> result)), :olympus/dialect (a hive-vessel dialect keyword)
   and optional :olympus/vessel-id. Returns a hive-vessel Target, or nil when
   the eval fn does not resolve. A result map carrying :error throws."
  [config]
  (let [dialect (some-> (:olympus/dialect config) keyword)
        eval! (some-> (:olympus/eval-fn config) symbol safe-resolve)]
    (when (and dialect eval!)
      {:vessel/id (keyword (or (:olympus/vessel-id config) (name dialect)))
       :vessel/dialect dialect
       :vessel/features #{}
       :vessel/execute! (fn [{:native/keys [payload]}]
                          (let [result (eval! payload)]
                            (when (and (map? result) (:error result))
                              (throw (ex-info "olympus: eval port answered an error"
                                              {:reason :eval-failed :error (:error result)})))
                            result))})))

(defn presenter-target
  "The presenter target (fn [ops]) for CONFIG. Each call resolves the host
   and a route afresh, records the route used in LAST-ROUTE (an atom), and
   throws when no route resolves or the result is {:error ...}."
  ([config last-route] (presenter-target config last-route default-routes safe-resolve))
  ([config last-route routes resolve]
   (let [host-id (:olympus/host config)]
     (fn [ops]
       (let [ctx {:config config
                  :host-hooks (hooks-of (dependency config host-id))
                  :resolve resolve}
             {:keys [route deliver]} (select-route routes ctx)]
         (reset! last-route route)
         (when-not deliver
           (throw (ex-info (str "olympus: no delivery route to " host-id)
                           {:reason :vessel-unavailable :olympus/host host-id})))
         (let [result (deliver ops)]
           (when (and (map? result) (contains? result :error))
             (throw (ex-info (str "olympus: delivery to " host-id " failed")
                             {:reason :vessel-dispatch-failed
                              :olympus/host host-id
                              :route route
                              :failure (:error result)})))
           result))))))

(defn- start! [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (let [config (merge seed runtime-config)
            host-id (:olympus/host config)
            presenter-id (or (:olympus/presenter-id config) host-id)
            olympus-hooks (hooks-of (dependency config olympus-addon-id))
            register (:olympus/register-presenter! olympus-hooks)
            unregister (:olympus/unregister-presenter! olympus-hooks)
            last-route (atom nil)
            fail (fn [msg]
                   (reset! state {:lifecycle :failed :host host-id :last-error msg})
                   {:success? false :errors [msg]})]
        (cond
          (str/blank? host-id) (fail "olympus harness: :olympus/host is not configured")
          (not (fn? register)) (fail "olympus harness: hive.olympus is absent or inactive")
          :else
          (let [target (presenter-target config last-route)]
            (reset! state {:lifecycle :active
                           :host host-id
                           :presenter-id presenter-id
                           :olympus-hooks olympus-hooks
                           :unregister unregister
                           :last-route last-route})
            (if (register presenter-id target)
              {:success? true :metadata {:host host-id :presenter-id presenter-id}}
              (fail "olympus harness: hive.olympus refused the presenter"))))))))

(defn- stop! [state]
  (locking state
    (let [{:keys [lifecycle unregister presenter-id]} @state]
      (when (and (= :active lifecycle) (fn? unregister))
        (try (unregister presenter-id) (catch Throwable _ nil)))
      (swap! state (fn [s] (-> s (assoc :lifecycle :stopped) (dissoc :olympus-hooks :unregister))))
      nil)))

(defn- delivery-status [{:keys [olympus-hooks presenter-id]}]
  (let [status-fn (:olympus/presenters olympus-hooks)]
    (when (fn? status-fn)
      (get (try (status-fn) (catch Throwable _ nil)) presenter-id))))

(defrecord HarnessAddon [id state seed]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{:olympus-presenter :health-reporting})
  (initialize! [_ runtime-config] (start! state seed runtime-config))
  (shutdown! [_] (stop! state))
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_]
    (let [{:keys [lifecycle host presenter-id last-route last-error] :as s} @state
          delivery (delivery-status s)]
      {:status (case lifecycle
                 :active (if (= :degraded (:status delivery)) :degraded :ok)
                 :failed :down
                 :degraded)
       :details (cond-> {:lifecycle lifecycle :host host}
                  presenter-id (assoc :presenter-id presenter-id)
                  last-route (assoc :route @last-route)
                  delivery (assoc :delivery delivery)
                  last-error (assoc :last-error last-error))}))
  (excluded-tools [_] #{})
  (hooks [_]
    (if (= :active (:lifecycle @state))
      {:olympus.harness/status (fn [] (let [{:keys [host presenter-id last-route]} @state]
                                        {:host host :presenter-id presenter-id :route @last-route}))}
      {})))

(defn addon-ctor
  "Pure constructor resolved from a harness manifest."
  [config]
  (let [config (or config {})]
    (->HarnessAddon (or (:olympus/addon-id config) (harness-addon-id (:olympus/host config)))
                    (atom {:lifecycle :created :host (:olympus/host config)})
                    config)))
