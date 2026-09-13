(ns hive-olympus.presenter-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-olympus.model :as model]
            [hive-olympus.presenter :as presenter]
            [hive-olympus.test-support :as t]
            [hive-olympus.view :as view]))

(defn- render [n] (view/panels (model/grid-model (t/agents n) model/initial-state)))

(deftest registration-catches-up-and-deltas-follow
  (let [seat (presenter/create)
        [calls target] (t/recorder)]
    (is (= :deepseek (presenter/register! seat :deepseek target (render 5))))
    (is (= [["olympus/tab-1" "olympus/tab-2"]] (mapv t/panel-ids @calls)) "catch-up is every panel")
    (presenter/broadcast! seat (render 5))
    (is (= 1 (count @calls)) "identical render delivers nothing")
    (presenter/broadcast! seat (render 3))
    (is (= [{:op :ui/close-panel :panel/id "olympus/tab-2"}] (take 1 (last @calls))))
    (is (= {:deepseek {:status :live :deliveries 2}} (presenter/status seat)))))

(deftest re-registering-the-same-target-keeps-history
  (let [seat (presenter/create)
        [calls target] (t/recorder)]
    (presenter/register! seat :x target (render 1))
    (presenter/register! seat :x target (render 1))
    (is (= 1 (count @calls)))
    (let [[calls2 target2] (t/recorder)]
      (presenter/register! seat :x target2 (render 1))
      (is (= 1 (count @calls2)) "a new target under the same id is caught up afresh"))))

(deftest a-throwing-presenter-degrades-alone-and-retries
  (let [seat (presenter/create)
        [good-calls good] (t/recorder)
        fail? (atom true)
        attempts (atom [])
        flaky (fn [ops] (swap! attempts conj ops) (when @fail? (throw (ex-info "vessel down" {}))))]
    (presenter/register! seat :good good (render 1))
    (presenter/register! seat :flaky flaky (render 1))
    (is (= {:status :degraded :deliveries 0 :error "vessel down"} (:flaky (presenter/status seat))))
    (presenter/broadcast! seat (render 2))
    (is (= 2 (count @good-calls)) "the healthy presenter is unaffected")
    (is (= 2 (count @attempts)) "the degraded presenter is retried")
    (reset! fail? false)
    (presenter/broadcast! seat (render 2))
    (is (= ["olympus/tab-1"] (t/panel-ids (last @attempts)))
        "retry carries the delta from the last SUCCESSFUL delivery")
    (is (= :live (:status (:flaky (presenter/status seat)))))
    (is (= 2 (count @good-calls)))))

(deftest unregister-and-non-fn-targets
  (let [seat (presenter/create)
        [calls target] (t/recorder)]
    (is (nil? (presenter/register! seat :bad nil (render 1))))
    (presenter/register! seat :x target (render 1))
    (is (= :x (presenter/unregister! seat :x)))
    (is (nil? (presenter/unregister! seat :x)))
    (presenter/broadcast! seat (render 4))
    (is (= 1 (count @calls)))))
