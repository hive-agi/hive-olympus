(ns hive-olympus.mount-test
  "The shipped manifest through the real mounter, with no host swarm on the
   classpath: the live roster adapter degrades to the empty state, and a
   data-only harness spec delivers it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]
            [hive-olympus.test-support :as t]))

(def host-calls (atom []))

(defn host-ctor [_]
  (t/->StubAddon "hive.stubvessel"
                 {:vessel/dispatch! (fn [ops] (swap! host-calls conj ops) {:ok ops})}))

(defn- manifest [file]
  (some-> (io/resource (str "META-INF/hive-addons/" file)) slurp edn/read-string))

(deftest classpath-discovery-finds-the-core-manifest
  (let [{:keys [specs]} (mount/discover-specs)]
    (is (some #(= "hive.olympus" (:addon/id %)) specs))))

(deftest core-manifest-mounts-and-a-manifest-only-harness-delivers
  (reset! host-calls [])
  (let [core-spec (update (manifest "hive-olympus.edn") :addon/config assoc :olympus/refresh-ms 0)
        specs [(assoc core-spec :addon/trust-class :foss)
               {:addon/id "hive.stubvessel" :addon/type :native
                :addon/init-ns "hive-olympus.mount-test" :addon/init-fn "host-ctor"
                :addon/capabilities #{:vessel}}
               {:addon/id "hive.olympus.stubvessel" :addon/type :native
                :addon/init-ns "hive-olympus.harness" :addon/init-fn "addon-ctor"
                :addon/config {:olympus/host "hive.stubvessel"}
                :addon/dependencies #{"hive.olympus" "hive.stubvessel"}
                :addon/capabilities #{:olympus-presenter}}]
        host (mount/atom-mount-host)
        report (mount/mount! (mount/solve specs) host)
        core (mount-port/registered host "hive.olympus")
        harness (mount-port/registered host "hive.olympus.stubvessel")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (is (= "hive.olympus.stubvessel" (last (:order report))))
      (is (= [["olympus/tab-1" "olympus/operator"]] (mapv t/panel-ids @host-calls)))
      (is (= "No active agents" (-> @host-calls first first :doc :doc/blocks first :text)))
      (is (re-find #"unavailable" (get-in (addon/health core) [:details :roster-warning])))
      (is (= :ok (:status (addon/health harness))))
      (is (empty? (:errors (mount/teardown! host (:order report)))))
      (finally
        (doseq [a [harness core]] (when a (try (addon/shutdown! a) (catch Throwable _ nil))))))))

(defn source-ctor [_]
  (t/->StubAddon "hive.source" {:source/ops (fn [] {"ling-1" 3})}))

(defn source-lens
  "A lens fn as a source library would ship it: reads the source's hooks."
  [hooks agent]
  (when-let [ops (:source/ops hooks)]
    (when-let [n (get (ops) (:agent/id agent))]
      {:doc/title "Source ops"
       :doc/blocks [{:block/type :para :text (str n " ops")}]})))

(deftest a-manifest-only-lens-brick-mounts-beside-the-harness-brick
  (reset! host-calls [])
  (let [core-spec (update (manifest "hive-olympus.edn") :addon/config assoc :olympus/refresh-ms 0)
        specs [(assoc core-spec :addon/trust-class :foss)
               {:addon/id "hive.stubvessel" :addon/type :native
                :addon/init-ns "hive-olympus.mount-test" :addon/init-fn "host-ctor"
                :addon/capabilities #{:vessel}}
               {:addon/id "hive.source" :addon/type :native
                :addon/init-ns "hive-olympus.mount-test" :addon/init-fn "source-ctor"
                :addon/capabilities #{:source}}
               {:addon/id "hive.olympus.stubvessel" :addon/type :native
                :addon/init-ns "hive-olympus.harness" :addon/init-fn "addon-ctor"
                :addon/config {:olympus/host "hive.stubvessel"}
                :addon/dependencies #{"hive.olympus" "hive.stubvessel"}
                :addon/capabilities #{:olympus-presenter}}
               {:addon/id "hive.olympus.lens.source" :addon/type :native
                :addon/init-ns "hive-olympus.lens-brick" :addon/init-fn "addon-ctor"
                :addon/config {:olympus/lens-source "hive.source"
                               :olympus/lens-fn 'hive-olympus.mount-test/source-lens}
                :addon/dependencies #{"hive.olympus" "hive.source"}
                :addon/capabilities #{:olympus-lens}}]
        host (mount/atom-mount-host)
        report (mount/mount! (mount/solve specs) host)
        core (mount-port/registered host "hive.olympus")
        harness (mount-port/registered host "hive.olympus.stubvessel")
        lens (mount-port/registered host "hive.olympus.lens.source")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (is (= #{"hive.olympus" "hive.stubvessel" "hive.source"
               "hive.olympus.stubvessel" "hive.olympus.lens.source"}
             (set (:order report))))
      (is (= :ok (:status (addon/health harness))))
      (is (= :ok (:status (addon/health lens))))
      (is (= {"hive.source" :idle} ((:olympus/lenses (addon/hooks core))))
          "the lens registers under its source id, idle until an agent is focused")
      (is (= [["olympus/tab-1" "olympus/operator"]] (mapv t/panel-ids (take 1 @host-calls)))
          "the harness still delivers with a lens mounted beside it")
      (addon/shutdown! lens)
      (is (= {} ((:olympus/lenses (addon/hooks core)))) "shutting the lens brick down unregisters the lens")
      (is (empty? (:errors (mount/teardown! host (:order report)))))
      (finally
        (doseq [a [lens harness core]] (when a (try (addon/shutdown! a) (catch Throwable _ nil))))))))
