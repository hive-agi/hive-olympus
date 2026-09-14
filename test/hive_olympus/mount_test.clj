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
