(ns hive-olympus.roster-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-olympus.roster :as roster]
            [hive-olympus.schema :as s]
            [malli.core :as m]))

(def slaves
  [{:slave/id "h" :slave/depth 0 :slave/status :idle}
   {:slave/id "l1" :slave/name "alpha" :slave/depth 1 :slave/status :working :slave/current-task "port layout"}
   {:slave/id "l2" :slave/depth 1 :slave/status :initializing}
   {:slave/id "l3" :slave/name "failed" :slave/depth 1 :slave/status :error}
   {:slave/id "l4" :slave/name "odd" :slave/depth 1 :slave/status :martian :slave/current-task {:task/title "t"}}
   {:slave/id "gone" :slave/depth 1 :slave/status :zombie :slave/alive? false}
   {:slave/id "stale" :slave/depth 1 :slave/status :idle :slave/alive? false}
   {:slave/id "done" :slave/depth 1 :slave/status :terminated}
   {:slave/id "d1" :slave/depth 2 :slave/status :working :slave/parent "l3"}
   {:slave/id "d2" :slave/depth 2 :slave/status :idle :slave/parent {:slave/id "gone"}}])

(deftest agents-are-the-live-lings-each-followed-by-its-drones
  (let [agents (roster/agents slaves)]
    (is (m/validate s/Roster agents))
    (is (= ["l1" "l2" "l3" "d1" "l4" "d2"] (mapv :agent/id agents)))
    (is (= {:agent/id "l1" :agent/name "alpha" :agent/status :working :agent/task "port layout" :agent/kind :ling}
           (first agents)))
    (testing "a nameless slave is named by its id; starting statuses read as spawning"
      (is (= {:agent/id "l2" :agent/name "l2" :agent/status :spawning :agent/kind :ling} (second agents))))
    (testing "a failed ling stays visible and counts its drones"
      (is (= {:agent/id "l3" :agent/name "failed" :agent/status :error :agent/kind :ling :agent/drones 1}
             (nth agents 2)))
      (is (= {:agent/id "d1" :agent/name "d1" :agent/status :working :agent/kind :drone :agent/parent "l3"}
             (nth agents 3))))
    (testing "an unknown status reads as idle; a task entity yields its title"
      (is (= {:agent/id "l4" :agent/name "odd" :agent/status :idle :agent/task "t" :agent/kind :ling} (nth agents 4))))
    (testing "a drone whose ling is not observable still shows, after the lings"
      (is (= "gone" (:agent/parent (last agents)))))))

(def venice-slave
  {:slave/id "obs-venice" :slave/name "obs-venice" :slave/depth 1 :slave/status :working
   :slave/current-task nil :slave/project-id "hive-olympus" :slave/alive? true
   :slave/last-active-at 1000 :slave/tasks-completed 0
   :ling/model "deepseek-v4-flash" :ling/provider :venice :ling/spawn-mode :hive-agent})

(def dash
  "The separator bb-ling puts in its progress shouts (U+2014)."
  (str (char 0x2014)))

(def shouts
  [{:event-type :progress :timestamp 60000 :task "read the layout\n and report" :message (str "bb-ling turn 1 " dash " tool_calls=[\"read_file\"]")}
   {:event-type :progress :timestamp 190000 :task "read the layout\n and report" :message (str "bb-ling turn 3 " dash " tool_calls=[\"glob_files\"]")}
   {:event-type :started :timestamp 2000 :task "read the layout\n and report" :message "spawn"}])

(deftest a-slave-row-and-its-latest-shout-make-an-observed-agent
  (let [agent (roster/slave->agent venice-slave (roster/latest-shout shouts) 250000)]
    (is (m/validate s/Agent agent))
    (is (= {:agent/id "obs-venice" :agent/name "obs-venice" :agent/status :working :agent/kind :ling
            :agent/model "deepseek-v4-flash" :agent/provider "venice" :agent/mode "hive-agent"
            :agent/project "hive-olympus" :agent/task "read the layout and report"
            :agent/activity "turn 3: tool_calls=[\"glob_files\"]" :agent/seen "1m ago" :agent/done 0}
           agent)))
  (testing "a non-progress event names itself"
    (is (= "completed: all good"
           (:agent/activity (roster/slave->agent venice-slave {:event-type :completed :message "all good"} nil)))))
  (testing "long task text is clipped"
    (is (= roster/max-text
           (count (:agent/task (roster/slave->agent (assoc venice-slave :slave/current-task (apply str (repeat 400 "x"))))))))))

(deftest seen-is-minute-coarse
  (is (= ["<1m ago" "<1m ago" "1m ago" "59m ago" "1h ago" "<1m ago"]
         (mapv roster/seen-text [0 59999 60000 3599999 3600000 -5]))))

(deftest the-live-adapter-degrades-to-empty-and-warns
  (let [warning (atom :unset)
        f (roster/live-roster-fn #(reset! warning %) (constantly nil))]
    (is (= [] (f)))
    (is (re-find #"unavailable" @warning)))
  (let [warning (atom :unset)
        f (roster/live-roster-fn #(reset! warning %)
                                 (fn [sym] (when (= roster/live-source sym) (constantly slaves))))]
    (is (= 6 (count (f))))
    (is (nil? @warning) "a resolving source clears the warning"))
  (testing "shouts enrich agents when the activity source resolves, and a throwing one is ignored"
    (let [resolve (fn [sym]
                    (condp = sym
                      roster/live-source (constantly [venice-slave (assoc venice-slave :slave/id "quiet")])
                      roster/activity-source (fn [id] (if (= "obs-venice" id) shouts (throw (ex-info "boom" {}))))
                      nil))
          [a b] ((roster/live-roster-fn (fn [_]) resolve (constantly 250000)))]
      (is (= "turn 3: tool_calls=[\"glob_files\"]" (:agent/activity a)))
      (is (nil? (:agent/activity b)))
      (is (= "4m ago" (:agent/seen b))))))
