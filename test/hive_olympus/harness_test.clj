(ns hive-olympus.harness-test
  "The generic harness projection against the real hive.olympus core and stub
   hosts of the three shapes found in the fleet: a host exposing
   :vessel/dispatch! (hive.deepseek, hive.vim), a host exposing only
   :vessel/target (hive.vscode), and a host exposing neither (hive.emacs),
   reached through :olympus/target-resolver."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-olympus.addon :as olympus]
            [hive-olympus.harness :as sut]
            [hive-olympus.test-support :as t]))

(defn- olympus! [roster]
  (let [a (olympus/addon-ctor {:olympus/refresh-ms 0 :olympus/roster-fn (fn [] @roster)})]
    (addon/initialize! a {})
    a))

(defn- harness! [olympus-addon host-id host config]
  (let [cfg (merge {:olympus/host host-id
                    :mount/dependencies (cond-> {"hive.olympus" olympus-addon}
                                          host (assoc host-id host))}
                   config)
        h (sut/addon-ctor cfg)]
    [h (addon/initialize! h cfg)]))

(defn- recording-vessel [dialect]
  (let [natives (atom [])]
    [natives {:vessel/id :stub :vessel/dialect dialect :vessel/features #{}
              :vessel/execute! (fn [native] (swap! natives conj native) :ok)}]))

(def emacs-natives (atom []))

(defn emacs-like-target
  "A resolver as a hive.emacs brick would name it: config -> hive-vessel Target."
  [_config]
  {:vessel/id :emacs :vessel/dialect :elisp :vessel/features #{}
   :vessel/execute! (fn [native] (swap! emacs-natives conj native) :ok)})

(deftest the-manifest-shape-derives-id-and-presenter
  (is (= "hive.olympus.deepseek" (addon/addon-id (sut/addon-ctor {:olympus/host "hive.deepseek"}))))
  (is (= "hive.olympus.vim" (sut/harness-addon-id "hive.vim")))
  (is (= "custom" (addon/addon-id (sut/addon-ctor {:olympus/host "hive.vim" :olympus/addon-id "custom"})))))

(deftest a-dispatch-host-receives-the-core-ops-verbatim
  (let [roster (atom (t/agents 5))
        core (olympus! roster)
        [calls dispatch!] (t/recorder)
        host (t/->StubAddon "hive.deepseek" {:vessel/dispatch! dispatch!})
        [h init] (harness! core "hive.deepseek" host {})]
    (try
      (is (:success? init) (pr-str init))
      (is (= [["olympus/tab-1" "olympus/tab-2"]] (mapv t/panel-ids @calls)))
      (is (= :host-dispatch (get-in (addon/health h) [:details :route])))
      (is (= {:status :live :deliveries 1} (get-in (addon/health core) [:details :presenters "hive.deepseek"])))
      (is (= :ok (:status (addon/health h))))
      (addon/shutdown! h)
      (is (= {} (get-in (addon/health core) [:details :presenters])) "shutdown unregisters")
      (finally (addon/shutdown! h) (addon/shutdown! core)))))

(deftest a-target-only-host-is-lowered-through-hive-vessel
  (let [roster (atom (t/agents 2))
        core (olympus! roster)
        [natives vessel] (recording-vessel :json)
        host (t/->StubAddon "hive.vscode" {:vessel/target (fn [] vessel)})
        [h init] (harness! core "hive.vscode" host {})]
    (try
      (is (:success? init))
      (is (= :host-target (get-in (addon/health h) [:details :route])))
      (is (= [{:dialect :json :op "ui/show-panel" :panel "olympus/tab-1"}]
             (mapv (fn [n] {:dialect (:native/dialect n)
                            :op (get-in n [:native/payload "op"])
                            :panel (get-in n [:native/payload "panel/id"])})
                   @natives)))
      (finally (addon/shutdown! h) (addon/shutdown! core)))))

(deftest a-hookless-host-projects-through-a-configured-resolver
  (reset! emacs-natives [])
  (let [roster (atom (t/agents 1))
        core (olympus! roster)
        host (t/->StubAddon "hive.emacs" {})
        [h init] (harness! core "hive.emacs" host
                           {:olympus/target-resolver 'hive-olympus.harness-test/emacs-like-target})]
    (try
      (is (:success? init))
      (is (= :configured-resolver (get-in (addon/health h) [:details :route])))
      (is (= [:elisp] (mapv :native/dialect @emacs-natives)))
      (is (string? (:native/payload (first @emacs-natives))))
      (finally (addon/shutdown! h) (addon/shutdown! core)))))

(def eval-port-payloads (atom []))

(defn fake-eval-elisp! [payload]
  (swap! eval-port-payloads conj payload)
  "ok")

(deftest an-eval-port-host-is-manifest-only-through-the-built-in-resolver
  (reset! eval-port-payloads [])
  (let [roster (atom (t/agents 1))
        core (olympus! roster)
        host (t/->StubAddon "hive.emacs" {})
        [h init] (harness! core "hive.emacs" host
                           {:olympus/target-resolver 'hive-olympus.harness/eval-port-target
                            :olympus/eval-fn 'hive-olympus.harness-test/fake-eval-elisp!
                            :olympus/dialect :elisp})]
    (try
      (is (:success? init))
      (is (= :configured-resolver (get-in (addon/health h) [:details :route])))
      (is (= 1 (count @eval-port-payloads)))
      (is (string? (first @eval-port-payloads)) "the :elisp dialect lowers the panel to source")
      (is (= :live (get-in (addon/health core) [:details :presenters "hive.emacs" :status])))
      (finally (addon/shutdown! h) (addon/shutdown! core))))
  (testing "an unresolvable eval fn is no target; an :error answer throws"
    (is (nil? (sut/eval-port-target {:olympus/eval-fn 'no.such/fn :olympus/dialect :elisp})))
    (let [target (sut/eval-port-target {:olympus/eval-fn 'clojure.core/identity :olympus/dialect "elisp"})]
      (is (= {:vessel/id :elisp :vessel/dialect :elisp :vessel/features #{}}
             (dissoc target :vessel/execute!)))
      (is (thrown? clojure.lang.ExceptionInfo
                   ((:vessel/execute! target) {:native/payload {:error :timeout}}))))))

(deftest route-precedence
  (let [[_ dispatch!] (t/recorder)
        [_ vessel] (recording-vessel :json)
        ctx (fn [hooks config] {:config config :host-hooks hooks :resolve #(try (requiring-resolve %) (catch Throwable _ nil))})
        route (fn [hooks config] (:route (sut/select-route sut/default-routes (ctx hooks config))))]
    (is (= :host-dispatch (route {:vessel/dispatch! dispatch! :vessel/target vessel} {})))
    (is (= :host-target (route {:vessel/target vessel} {})))
    (is (= :host-target (route {:vessel/target (fn [] vessel)} {})))
    (is (= :configured-resolver (route {:vessel/dispatch! dispatch!}
                                       {:olympus/target-resolver 'hive-olympus.harness-test/emacs-like-target})))
    (is (nil? (route {} {})))
    (is (nil? (route {:vessel/target (fn [] nil)} {})) "an absent target is no route")
    (testing "routes are an open chain"
      (let [custom (reify sut/IDeliveryRoute
                     (route-id [_] :custom)
                     (resolve-route [_ _] (fn [ops] {:ok ops})))]
        (is (= :custom (:route (sut/select-route (cons custom sut/default-routes) (ctx {} {})))))))))

(deftest mount-order-does-not-matter-and-failures-degrade
  (let [roster (atom (t/agents 2))
        core (olympus! roster)
        host-hooks (atom {})
        host (t/->StubAddon "hive.deepseek" host-hooks)
        [h init] (harness! core "hive.deepseek" host {})
        presenter-status #(get-in (addon/health core) [:details :presenters "hive.deepseek"])]
    (try
      (is (:success? init) "registration succeeds before the host exposes any hook")
      (is (= :degraded (:status (presenter-status))))
      (is (re-find #"no delivery route" (:error (presenter-status))))
      (is (= :degraded (:status (addon/health h))))
      (testing "an {:error} result is a failed delivery"
        (reset! host-hooks {:vessel/dispatch! (fn [_] {:error {:failure/reason :no-executor}})})
        ((:olympus/refresh! (addon/hooks core)))
        (is (re-find #"failed" (:error (presenter-status)))))
      (testing "the host coming up is picked up by the next refresh"
        (let [[calls dispatch!] (t/recorder)]
          (reset! host-hooks {:vessel/dispatch! dispatch!})
          ((:olympus/refresh! (addon/hooks core)))
          (is (= [["olympus/tab-1"]] (mapv t/panel-ids @calls)))
          (is (= :live (:status (presenter-status))))))
      (finally (addon/shutdown! h) (addon/shutdown! core)))))

(deftest initialization-fails-loudly-without-core-or-host
  (let [h (sut/addon-ctor {:olympus/host "hive.deepseek"})
        r (addon/initialize! h {})]
    (is (false? (:success? r)))
    (is (re-find #"hive.olympus is absent" (first (:errors r))))
    (is (= :down (:status (addon/health h)))))
  (let [h (sut/addon-ctor {})
        r (addon/initialize! h {})]
    (is (re-find #":olympus/host" (first (:errors r))))))
