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

(deftest an-agent-carries-its-recent-log-not-only-its-last-word
  (let [now 1789900000000
        shouts [{:timestamp (- now 30000) :event-type :progress :message "bb-ling turn 13 — tool_calls=[\"bash\"]"}
                {:timestamp (- now 300000) :event-type :progress :message "bb-ling turn 12 — tool_calls=[\"bash\"]"}
                {:timestamp (- now 7200000) :event-type :started :message "bb-ling started"}
                {:timestamp (- now 60000) :event-type :progress :message "   "}]]
    (testing "the log is newest first and aged; a bodyless shout still names its event"
      (is (= ["<1m ago  turn 13: tool_calls=[\"bash\"]"
              "1m ago  progress"
              "5m ago  turn 12: tool_calls=[\"bash\"]"
              "2h ago  started"]
             (roster/activity-log shouts now))))
    (testing "limit caps it, keeping the newest"
      (is (= ["<1m ago  turn 13: tool_calls=[\"bash\"]"] (roster/activity-log shouts now 1)))
      (is (= [] (roster/activity-log [] now))))
    (testing "a shout with neither body nor event says nothing at all"
      (is (= [] (roster/activity-log [{:timestamp now :message "  "}] now))))
    (testing "without a clock the line is the body alone"
      (is (= ["turn 13: tool_calls=[\"bash\"]"] (roster/activity-log [(first shouts)] nil))))
    (testing "agents attach the log, and :agent/activity still holds the latest"
      (let [rows (roster/agents slaves (fn [id] (when (= "l1" id) shouts)) now)
            alpha (first (filter #(= "l1" (:agent/id %)) rows))
            quiet (first (filter #(= "l2" (:agent/id %)) rows))]
        (is (= 4 (count (:agent/recent alpha))))
        (is (= "turn 13: tool_calls=[\"bash\"]" (:agent/activity alpha))
            "the cell still shows one line")
        (is (= (first (:agent/recent alpha)) (str "<1m ago  " (:agent/activity alpha)))
            "the log's newest line is the cell's line, aged")
        (is (not (contains? quiet :agent/recent))
            "an agent with nothing to say carries no empty log")
        (is (m/validate s/Roster rows))))
    (testing "the log survives the agent leaving the swarm"
      (let [ghost (roster/exited-agent (assoc {:agent/id "l1" :agent/name "alpha" :agent/status :working}
                                              :agent/recent (roster/activity-log shouts now))
                                       (roster/final-shout shouts) now)]
        (is (= 4 (count (:agent/recent ghost))))
        (is (true? (:agent/exited? ghost)))))))

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

(def axon-failure
  "The shouts a hive-agent ling leaves when its provider refuses the first call."
  [{:event-type :started :timestamp 1000 :message "bb-ling spawn"}
   {:event-type :progress :timestamp 2000
    :message (str "bb-ling turn 1 " dash " error: {:reason :llm/call-failed, :status 402, :message \"axon API error: 402\"}")}
   {:event-type :error :timestamp 3000 :message (str "bb-ling exit " dash " variant=error turns=1")}
   {:event-type :failed :timestamp 3100 :message "Loop failed: {:status 402, :body \"{}\", :message \"axon API error: 402\"}"}
   {:event-type :error :timestamp 3200 :message "Agent scout-axon failed: unknown error"}])

(deftest the-final-shout-prefers-failure-then-error-then-completion
  (is (= 3100 (:timestamp (roster/final-shout axon-failure))))
  (is (= 3200 (:timestamp (roster/final-shout (remove #(= :failed (:event-type %)) axon-failure)))))
  (is (= 9 (:timestamp (roster/final-shout [{:event-type :completed :timestamp 9} {:event-type :progress :timestamp 10}]))))
  (is (= 10 (:timestamp (roster/final-shout [{:event-type :progress :timestamp 10} {:timestamp 4}]))))
  (is (nil? (roster/final-shout []))))

(deftest a-failure-shout-reads-as-its-embedded-message
  (is (= "Loop failed: axon API error: 402"
         (:agent/activity (roster/slave->agent venice-slave (roster/final-shout axon-failure) nil)))
      "a message that already says failed is not prefixed again")
  (is (= "turn 1: error: axon API error: 402"
         (:agent/activity (roster/slave->agent venice-slave (second axon-failure) nil)))))

(deftest a-departed-agent-lingers-with-how-it-ended
  (let [alive {:agent/id "a" :agent/name "a" :agent/status :working :agent/drones 1}
        other {:agent/id "b" :agent/name "b" :agent/status :idle}
        shouts-of {"a" axon-failure}
        t0 (roster/settle {:previous [] :ghosts {}} [alive other] shouts-of 10000 60000)
        t1 (roster/settle t0 [other] shouts-of 20000 60000)
        t2 (roster/settle t1 [other] shouts-of 50000 60000)
        t3 (roster/settle t2 [other] shouts-of 80001 60000)]
    (is (= [alive other] (:roster t0)))
    (testing "the agent that left is shown after the live ones, exited, as it failed"
      (is (= [other {:agent/id "a" :agent/name "a" :agent/status :error :agent/exited? true
                     :agent/activity "Loop failed: axon API error: 402" :agent/seen "<1m ago"}]
             (:roster t1)))
      (is (m/validate s/Roster (:roster t1))))
    (testing "it stays for the linger window, aging, then leaves"
      (is (= ["b" "a"] (mapv :agent/id (:roster t2))))
      (is (= [other] (:roster t3))))
    (testing "an agent that comes back is live again, not a ghost"
      (let [back (roster/settle t1 [alive other] shouts-of 30000 60000)]
        (is (= [alive other] (:roster back)))
        (is (empty? (:ghosts back)))))
    (testing "a completed agent lingers idle"
      (let [done [{:event-type :completed :timestamp 15000 :message "Agent a completed: all good"}]
            t (roster/settle t0 [other] {"a" done} 20000 60000)]
        (is (= {:agent/status :idle :agent/activity "Agent a completed: all good"}
               (select-keys (last (:roster t)) [:agent/status :agent/activity])))))))

(deftest the-live-adapter-lingers-unless-disabled
  (let [slaves (atom [venice-slave])
        clock (atom 100000)
        resolve (fn [sym] (condp = sym
                            roster/live-source (fn [] @slaves)
                            roster/activity-source (constantly axon-failure)
                            nil))
        lingering (roster/live-roster-fn (fn [_]) resolve #(deref clock) 60000)
        plain (roster/live-roster-fn (fn [_]) resolve #(deref clock) 0)]
    (lingering) (plain)
    (reset! slaves [])
    (swap! clock + 1000)
    (is (= [:error] (mapv :agent/status (lingering))))
    (is (= [] (plain)))))

(deftest a-final-shout-survives-the-host-clearing-it-on-exit
  (let [registry {:atom (atom {"obs-venice" {:messages axon-failure}}) :name "" :opts {}}
        slaves (atom [venice-slave])
        clock (atom 100000)
        resolve (fn [sym] (condp = sym
                            roster/live-source (fn [] @slaves)
                            roster/activity-source (fn [id] (:messages (get @(:atom registry) id)))
                            roster/registry-source registry
                            nil))
        f (roster/live-roster-fn (fn [_]) resolve #(deref clock) 60000)]
    (is (= [:working] (mapv :agent/status (f))))
    (testing "the host exits the agent: shouts and slave row vanish in the same tick"
      (swap! (:atom registry) dissoc "obs-venice")
      (reset! slaves [])
      (swap! clock + 1000)
      (is (= [{:agent/status :error :agent/exited? true :agent/activity "Loop failed: axon API error: 402"}]
             (mapv #(select-keys % [:agent/status :agent/exited? :agent/activity]) (f)))))
    (testing "closing the adapter removes its watch"
      (is (= 1 (count (.getWatches ^clojure.lang.IRef (:atom registry)))))
      ((:olympus/close (meta f)))
      (is (empty? (.getWatches ^clojure.lang.IRef (:atom registry)))))
    (is (= {"a" [{:message "m"}]}
           (roster/departures {"a" {:messages [{:message "m"}]} "b" {:messages []} "c" {:data {:messages [1]}}}
                              {"c" {}}))
        "only agents that left with messages are departures")))
