(ns hive-olympus.addon-test
  "hive.olympus through its IAddon boundary with an injected roster port."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-olympus.addon :as sut]
            [hive-olympus.schema :as s]
            [hive-olympus.test-support :as t]
            [malli.core :as m]))

(defn- started [roster-atom config]
  (let [a (sut/addon-ctor (merge {:olympus/refresh-ms 0
                                  :olympus/roster-fn (fn [] @roster-atom)}
                                 config))]
    [a (addon/initialize! a {})]))

(deftest lifecycle-hooks-and-health
  (let [roster (atom (t/agents 5))
        [a init] (started roster {})
        h (addon/hooks a)]
    (try
      (is (:success? init))
      (is (= "hive.olympus" (addon/addon-id a)))
      (is (:already-initialized? (addon/initialize! a {})))
      (is (= {:active-tab 0 :focus nil} ((:olympus/state h))))
      (is (m/validate s/GridModel ((:olympus/model h))))
      (is (= ["olympus/tab-1" "olympus/tab-2"] (mapv :panel/id ((:olympus/panels h)))))
      (let [{:keys [status details]} (addon/health a)]
        (is (= :ok status))
        (is (= 5 (:agents details)))
        (is (= 2 (:tabs details))))
      (finally (addon/shutdown! a)))
    (is (= {} (addon/hooks a)) "hooks vanish once stopped")
    (is (= :stopped (get-in (addon/health a) [:details :lifecycle])))))

(deftest presenters-catch-up-and-receive-only-changes
  (let [roster (atom (t/agents 5))
        [a _] (started roster {})
        h (addon/hooks a)
        [calls target] (t/recorder)]
    (try
      (is (= "hive.deepseek" ((:olympus/register-presenter! h) "hive.deepseek" target)))
      (is (= [["olympus/tab-1" "olympus/tab-2"]] (mapv t/panel-ids @calls)))
      ((:olympus/refresh! h))
      (is (= 1 (count @calls)) "an unchanged roster delivers nothing")
      (reset! roster (t/agents 3))
      ((:olympus/refresh! h))
      (is (= [{:op :ui/close-panel :panel/id "olympus/tab-2"}] (take 1 (last @calls))))
      (testing "navigation re-renders and delivers"
        (reset! roster (t/agents 9))
        ((:olympus/refresh! h))
        (is (= {:active-tab 2 :focus "ling-9"} ((:olympus/focus! h) "ling-9")))
        (is (= {:active-tab 0 :focus "ling-9"} ((:olympus/next-tab! h))))
        (is (= {:active-tab 2 :focus "ling-9"} ((:olympus/prev-tab! h))))
        (is (some #(= "> worker-9" (:text %))
                  (mapcat #(get-in % [:doc :doc/blocks]) (last @calls)))))
      (is (= "hive.deepseek" ((:olympus/unregister-presenter! h) "hive.deepseek")))
      (let [n (count @calls)]
        (reset! roster (t/agents 1))
        ((:olympus/refresh! h))
        (is (= n (count @calls))))
      (finally (addon/shutdown! a)))))

(deftest a-failing-presenter-is-reported-and-isolated
  (let [roster (atom (t/agents 2))
        [a _] (started roster {})
        h (addon/hooks a)
        [calls good] (t/recorder)]
    (try
      ((:olympus/register-presenter! h) "good" good)
      ((:olympus/register-presenter! h) "bad" (fn [_] (throw (ex-info "boom" {}))))
      (reset! roster (t/agents 4))
      ((:olympus/refresh! h))
      (is (= 2 (count @calls)))
      (let [{:keys [status details]} (addon/health a)]
        (is (= :degraded status))
        (is (= {:status :degraded :deliveries 0 :error "boom"} (get-in details [:presenters "bad"]))))
      (finally (addon/shutdown! a)))))

(deftest a-failing-lens-degrades-health-and-isolates-the-others
  (let [roster (atom (t/agents 2))
        [a _] (started roster {})
        h (addon/hooks a)
        [calls target] (t/recorder)]
    (try
      ((:olympus/register-presenter! h) "p" target)
      (is (= "good" ((:olympus/register-lens! h) "good"
                     (fn [agent] {:doc/title "Good" :doc/blocks [{:block/type :para :text (:agent/id agent)}]}))))
      (is (= "bad" ((:olympus/register-lens! h) "bad" (fn [_] (throw (ex-info "boom" {}))))))
      (is (nil? ((:olympus/register-lens! h) "nope" :not-a-fn)))
      (is (= {"bad" :idle "good" :idle} ((:olympus/lenses h))) "idle until an agent is focused")
      (is (= :ok (:status (addon/health a))))
      ((:olympus/focus! h) "ling-2")
      (let [{:keys [status details]} (addon/health a)
            blocks (mapcat #(get-in % [:doc :doc/blocks])
                           (filter #(= "olympus/focus" (:panel/id %)) (last @calls)))]
        (is (= :degraded status))
        (is (= {"bad" :error "good" :ok} (:lenses details)))
        (is (= "ling-2" (:focus details)))
        (is (some #(= "ling-2" (:text %)) blocks) "the good lens still shows")
        (is (some #(= "lens failed: boom" (:text %)) blocks)))
      (testing "unregistering the failing lens restores health and re-renders"
        (is (= "bad" ((:olympus/unregister-lens! h) "bad")))
        (is (= :ok (:status (addon/health a))))
        (is (not-any? #(= "lens failed: boom" (:text %))
                      (mapcat #(get-in % [:doc :doc/blocks]) (last @calls)))))
      (finally (addon/shutdown! a)))))

(deftest a-throwing-roster-keeps-the-last-panels
  (let [roster (atom (t/agents 2))
        [a _] (started roster {})
        h (addon/hooks a)]
    (try
      (reset! roster ::boom)
      ((:olympus/refresh! h))
      (is (= ["olympus/tab-1"] (mapv :panel/id ((:olympus/panels h)))))
      (is (= :degraded (:status (addon/health a))))
      (is (string? (get-in (addon/health a) [:details :roster-error])))
      (finally (addon/shutdown! a)))))

(deftest the-refresh-loop-polls-and-stops-on-shutdown
  (let [roster (atom (t/agents 1))
        [a _] (started roster {:olympus/refresh-ms 25})
        h (addon/hooks a)
        [calls target] (t/recorder)]
    ((:olympus/register-presenter! h) "p" target)
    (reset! roster (t/agents 6))
    (is (t/eventually #(= 2 (count @calls)) 2000) "the loop delivers a roster change")
    (is (= ["olympus/tab-1" "olympus/tab-2"] (t/panel-ids (last @calls))))
    (addon/shutdown! a)
    (reset! roster (t/agents 12))
    (Thread/sleep 120)
    (is (= 2 (count @calls)) "no delivery after shutdown")))

(deftest roster-fn-may-be-named-by-symbol
  (let [a (sut/addon-ctor {:olympus/refresh-ms 0
                           :olympus/roster-fn 'hive-olympus.addon-test/two-agents})]
    (try
      (addon/initialize! a {})
      (is (= 2 (get-in (addon/health a) [:details :agents])))
      (finally (addon/shutdown! a)))))

(defn two-agents [] (t/agents 2))
