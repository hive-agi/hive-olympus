(ns hive-olympus.lens-brick-test
  "The generic lens brick against the real hive.olympus core and a stub source
   addon: a manifest names the source and a lens fn, and focusing an agent
   shows what the fn reads from the source's hooks."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-olympus.addon :as olympus]
            [hive-olympus.lens-brick :as sut]
            [hive-olympus.test-support :as t]))

(defn source-lens
  "A lens fn as a source library would ship it: reads the source's hooks."
  [hooks agent]
  (when-let [ops (:source/ops hooks)]
    (when-let [n (get (ops) (:agent/id agent))]
      {:doc/title "Source ops"
       :doc/blocks [{:block/type :para :text (str n " ops by " (:agent/name agent))}]})))

(defn- olympus! [roster]
  (let [a (olympus/addon-ctor {:olympus/refresh-ms 0 :olympus/roster-fn (fn [] @roster)})]
    (addon/initialize! a {})
    a))

(defn- brick! [olympus-addon source config]
  (let [cfg (merge {:olympus/lens-source "hive.source"
                    :olympus/lens-fn 'hive-olympus.lens-brick-test/source-lens
                    :mount/dependencies (cond-> {"hive.olympus" olympus-addon}
                                          source (assoc "hive.source" source))}
                   config)
        b (sut/addon-ctor cfg)]
    [b (addon/initialize! b cfg)]))

(deftest the-manifest-shape-derives-ids
  (is (= "hive.olympus.lens.carto-flow" (sut/brick-addon-id "hive.carto-flow")))
  (is (= "hive.olympus.lens.source" (addon/addon-id (sut/addon-ctor {:olympus/lens-source "hive.source"}))))
  (is (= "custom" (addon/addon-id (sut/addon-ctor {:olympus/lens-source "hive.source" :olympus/addon-id "custom"})))))

(deftest a-focused-agent-shows-the-source-through-the-brick
  (let [roster (atom (t/agents 2))
        core (olympus! roster)
        ops (atom {"ling-1" 3})
        source (t/->StubAddon "hive.source" {:source/ops (fn [] @ops)})
        [b init] (brick! core source {})
        [calls target] (t/recorder)
        h (addon/hooks core)]
    (try
      (is (:success? init) (pr-str init))
      (is (= {"hive.source" :idle} ((:olympus/lenses h))) "registered under the source id, idle until focus")
      ((:olympus/register-presenter! h) "p" target)
      (testing "focus opens the zoom with the lens document"
        ((:olympus/focus! h) "ling-1")
        (let [focus (first (filter #(= "olympus/focus" (:panel/id %)) (last @calls)))
              texts (map :text (get-in focus [:doc :doc/blocks]))]
          (is (some? focus))
          (is (= "Olympus  focus  worker-1" (get-in focus [:doc :doc/title])))
          (is (some #{"Source ops"} texts))
          (is (some #{"3 ops by worker-1"} texts))
          (is (= {"hive.source" :ok} ((:olympus/lenses h))))
          (is (= :ok (:status (addon/health b))))
          (is (= {:source "hive.source" :lens-id "hive.source" :lens :ok}
                 ((:olympus.lens-brick/status (addon/hooks b)))))))
      (testing "the source's hooks are read at every observation"
        (swap! ops assoc "ling-1" 4)
        ((:olympus/refresh! h))
        (is (some #(= "4 ops by worker-1" (:text %))
                  (mapcat #(get-in % [:doc :doc/blocks]) (last @calls)))))
      (testing "an agent the source knows nothing about gets an empty lens"
        ((:olympus/focus! h) "ling-2")
        (is (= {"hive.source" :empty} ((:olympus/lenses h))))
        (is (some #(= "No lens has anything on this agent" (:text %))
                  (mapcat #(get-in % [:doc :doc/blocks]) (last @calls)))))
      (testing "unfocusing closes the zoom"
        ((:olympus/focus! h) nil)
        (is (some #(= {:op :ui/close-panel :panel/id "olympus/focus"} %) (last @calls))))
      (addon/shutdown! b)
      (is (= {} ((:olympus/lenses h))) "shutdown unregisters the lens")
      (finally (addon/shutdown! b) (addon/shutdown! core)))))

(deftest a-missing-source-answers-empty-hooks-not-an-error
  (let [roster (atom (t/agents 1))
        core (olympus! roster)
        [b init] (brick! core nil {})
        h (addon/hooks core)]
    (try
      (is (:success? init))
      ((:olympus/focus! h) "ling-1")
      (is (= {"hive.source" :empty} ((:olympus/lenses h))))
      (finally (addon/shutdown! b) (addon/shutdown! core)))))

(deftest misconfiguration-fails-the-mount-loudly
  (let [core (olympus! (atom []))]
    (try
      (testing "no source"
        (let [[b init] (brick! core nil {:olympus/lens-source nil})]
          (is (false? (:success? init)))
          (is (re-find #"lens-source" (first (:errors init))))
          (is (= :down (:status (addon/health b))))))
      (testing "a lens fn that does not resolve"
        (let [[_ init] (brick! core nil {:olympus/lens-fn 'no.such/lens})]
          (is (false? (:success? init)))
          (is (re-find #"does not resolve" (first (:errors init))))))
      (testing "no olympus"
        (let [[_ init] (brick! nil nil {:mount/dependencies {}})]
          (is (false? (:success? init)))
          (is (re-find #"hive.olympus" (first (:errors init))))))
      (finally (addon/shutdown! core)))))
