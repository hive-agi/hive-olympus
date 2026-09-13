(ns hive-olympus.roster-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-olympus.roster :as roster]
            [hive-olympus.schema :as s]
            [malli.core :as m]))

(def slaves
  [{:slave/id "h" :slave/depth 0 :slave/status :idle}
   {:slave/id "l1" :slave/name "alpha" :slave/depth 1 :slave/status :working :slave/current-task "port layout"}
   {:slave/id "l2" :slave/depth 1 :slave/status :initializing}
   {:slave/id "l3" :slave/name "gone" :slave/depth 1 :slave/status :error}
   {:slave/id "l4" :slave/name "odd" :slave/depth 1 :slave/status :martian :slave/current-task {:task/title "t"}}
   {:slave/id "d1" :slave/depth 2 :slave/status :working}])

(deftest lings-are-depth-one-and-not-errored
  (let [agents (roster/lings slaves)]
    (is (m/validate s/Roster agents))
    (is (= ["l1" "l2" "l4"] (mapv :agent/id agents)))
    (is (= {:agent/id "l1" :agent/name "alpha" :agent/status :working :agent/task "port layout"}
           (first agents)))
    (testing "a nameless slave is named by its id; starting statuses read as spawning"
      (is (= {:agent/id "l2" :agent/name "l2" :agent/status :spawning} (second agents))))
    (testing "an unknown status reads as idle; a task entity yields its title"
      (is (= {:agent/id "l4" :agent/name "odd" :agent/status :idle :agent/task "t"} (nth agents 2))))))

(deftest the-live-adapter-degrades-to-empty-and-warns
  (let [warning (atom :unset)
        f (roster/live-roster-fn #(reset! warning %) (constantly nil))]
    (is (= [] (f)))
    (is (re-find #"unavailable" @warning)))
  (let [warning (atom :unset)
        f (roster/live-roster-fn #(reset! warning %)
                                 (fn [sym] (when (= roster/live-source sym) (constantly slaves))))]
    (is (= 3 (count (f))))
    (is (nil? @warning) "a resolving source clears the warning")))
