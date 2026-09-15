(ns hive-olympus.lens-brick
  "The generic lens brick: a manifest whose :addon/init-ns is this namespace,
   :addon/init-fn \"addon-ctor\", and whose :addon/config names a source addon
   and a lens fn over its hooks:

     {:olympus/lens-source \"hive.carto-flow\"}                   required
     :olympus/lens-fn                                          required: qualified
                                                               symbol of
                                                               (fn [source-hooks agent]
                                                                 -> Doc | nil)
     :olympus/lens-id                                          default: the source id
     :olympus/addon-id                                         default: \"hive.olympus.lens.\"
                                                               + source id without its
                                                               \"hive.\" prefix

   with :addon/dependencies #{\"hive.olympus\" <source>}.

   On initialize! it resolves the lens fn (one that does not resolve fails the
   mount, loudly) and registers on hive.olympus a lens that, at EVERY
   observation, reads the source's current hooks and applies the fn. An absent
   or inactive source answers {} hooks, and the lens fn decides what to say."
  (:require [clojure.string :as str]
            [hive-addon.protocol :as addon]))

(def olympus-addon-id "hive.olympus")

(defn- dependency [config id]
  (let [dep (get (:mount/dependencies config) id)]
    (when (addon/addon? dep) dep)))

(defn- hooks-of [dep]
  (or (when dep (try (addon/hooks dep) (catch Throwable _ nil))) {}))

(defn- safe-resolve [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn brick-addon-id
  "Addon id for the brick lensing SOURCE-ID into Olympus."
  [source-id]
  (str olympus-addon-id ".lens." (str/replace-first (str source-id) #"^hive\." "")))

(defn lens-of
  "The lens (fn [agent]) CONFIG describes: LENS-FN applied to the source's
   hooks, read afresh at every call."
  [config lens-fn]
  (let [source-id (:olympus/lens-source config)]
    (fn [agent]
      (lens-fn (hooks-of (dependency config source-id)) agent))))

(defn- start! [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (let [config (merge seed runtime-config)
            source-id (:olympus/lens-source config)
            lens-id (or (:olympus/lens-id config) source-id)
            olympus-hooks (hooks-of (dependency config olympus-addon-id))
            register (:olympus/register-lens! olympus-hooks)
            unregister (:olympus/unregister-lens! olympus-hooks)
            lens-fn (some-> (:olympus/lens-fn config) str symbol safe-resolve)
            fail (fn [msg]
                   (reset! state {:lifecycle :failed :source source-id :last-error msg})
                   {:success? false :errors [msg]})]
        (cond
          (str/blank? source-id)
          (fail "olympus lens brick: :olympus/lens-source is not configured")

          (nil? lens-fn)
          (fail (str "olympus lens brick: :olympus/lens-fn "
                     (pr-str (:olympus/lens-fn config)) " does not resolve"))

          (not (fn? register))
          (fail "olympus lens brick: hive.olympus is absent, inactive, or has no lens seat")

          :else
          (do (reset! state {:lifecycle :active
                             :source source-id
                             :lens-id lens-id
                             :olympus-hooks olympus-hooks
                             :unregister unregister})
              (if (register lens-id (lens-of config lens-fn))
                {:success? true :metadata {:source source-id :lens-id lens-id}}
                (fail "olympus lens brick: hive.olympus refused the lens"))))))))

(defn- stop! [state]
  (locking state
    (let [{:keys [lifecycle unregister lens-id]} @state]
      (when (and (= :active lifecycle) (fn? unregister))
        (try (unregister lens-id) (catch Throwable _ nil)))
      (swap! state (fn [s] (-> s (assoc :lifecycle :stopped) (dissoc :olympus-hooks :unregister))))
      nil)))

(defn- lens-status [{:keys [olympus-hooks lens-id]}]
  (let [status-fn (:olympus/lenses olympus-hooks)]
    (when (fn? status-fn)
      (get (try (status-fn) (catch Throwable _ nil)) lens-id))))

(defrecord LensBrickAddon [id state seed]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{:olympus-lens :health-reporting})
  (initialize! [_ runtime-config] (start! state seed runtime-config))
  (shutdown! [_] (stop! state))
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_]
    (let [{:keys [lifecycle source lens-id last-error] :as s} @state
          status (lens-status s)]
      {:status (case lifecycle
                 :active (if (= :error status) :degraded :ok)
                 :failed :down
                 :degraded)
       :details (cond-> {:lifecycle lifecycle :source source}
                  lens-id (assoc :lens-id lens-id)
                  status (assoc :lens status)
                  last-error (assoc :last-error last-error))}))
  (excluded-tools [_] #{})
  (hooks [_]
    (if (= :active (:lifecycle @state))
      {:olympus.lens-brick/status (fn [] (let [{:keys [source lens-id] :as s} @state]
                                          {:source source :lens-id lens-id :lens (lens-status s)}))}
      {})))

(defn addon-ctor
  "Pure constructor resolved from a lens brick manifest."
  [config]
  (let [config (or config {})]
    (->LensBrickAddon (or (:olympus/addon-id config)
                          (brick-addon-id (:olympus/lens-source config)))
                      (atom {:lifecycle :created :source (:olympus/lens-source config)})
                      config)))
