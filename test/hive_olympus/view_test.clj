(ns hive-olympus.view-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-olympus.model :as model]
            [hive-olympus.schema :as s]
            [hive-olympus.view :as view]
            [hive-schemas.test :as hst]
            [hive-test.mutation :as mut]
            [hive-vessel.schema :as vs]
            [malli.core :as m]
            [malli.generator :as mg]))

(def roster
  [{:agent/id "a1" :agent/name "alpha" :agent/status :working :agent/task "port layout"}
   {:agent/id "a2" :agent/name "beta" :agent/status :working}
   {:agent/id "a3" :agent/name "gamma" :agent/status :blocked}
   {:agent/id "a4" :agent/name "" :agent/status :idle}
   {:agent/id "a5" :agent/name "epsilon" :agent/status :idle}])

(defn- render [agents state]
  (view/panels (model/grid-model agents state)))

(deftest one-panel-per-tab-with-counted-titles
  (let [[p1 p2 :as panels] (render roster {:active-tab 0 :focus "a5"})]
    (is (= 2 (count panels)))
    (is (= ["olympus/tab-1" "olympus/tab-2"] (mapv :panel/id panels)))
    (is (= "Olympus  tab 1/2  (5 agents: 2 working, 1 blocked, 0 error, 2 idle)"
           (get-in p1 [:doc :doc/title])))
    (is (str/starts-with? (get-in p2 [:doc :doc/title]) "Olympus  tab 2/2"))
    (testing "tab activity leads a tabbed panel"
      (is (= {:block/type :para :text "active tab" :tone :info} (first (get-in p1 [:doc :doc/blocks]))))
      (is (= {:block/type :para :text "inactive tab" :tone :muted} (first (get-in p2 [:doc :doc/blocks])))))
    (testing "a cell is heading, toned status, fields"
      (is (= [{:block/type :heading :level 2 :text "alpha"}
              {:block/type :para :text "working" :tone :info}
              {:block/type :fields :fields [["id" "a1"] ["task" "port layout"] ["cell" "row 1, col 1"]]}]
             (subvec (get-in p1 [:doc :doc/blocks]) 1 4))))
    (testing "the focused cell is marked and a blank name falls back to the id"
      (is (= "> epsilon" (:text (second (get-in p2 [:doc :doc/blocks])))))
      (is (some #(= "a4" (:text %)) (get-in p1 [:doc :doc/blocks]))))))

(deftest an-observed-agent-shows-what-the-roster-knows
  (let [agents [{:agent/id "l1" :agent/name "scout" :agent/status :working :agent/kind :ling
                 :agent/provider "venice" :agent/model "deepseek-v4-flash" :agent/mode "hive-agent"
                 :agent/project "hive-olympus" :agent/drones 1 :agent/done 2
                 :agent/task "read layout" :agent/activity "turn 3: tool_calls=[read_file]"
                 :agent/seen "<1m ago"}
                {:agent/id "d1" :agent/name "d1" :agent/status :error :agent/kind :drone
                 :agent/parent "l1" :agent/model "glm-5.3" :agent/provider "axon" :agent/done 0}
                {:agent/id "l2" :agent/name "helper" :agent/status :idle :agent/kind :ling
                 :agent/provider "venice" :agent/model "deepseek-v4-flash"}]
        [panel] (render agents model/initial-state)
        blocks (get-in panel [:doc :doc/blocks])]
    (testing "the tab opens with the swarm's routes, most used first"
      (is (= {:block/type :para :text "routes: venice/deepseek-v4-flash x2, axon/glm-5.3" :tone :muted}
             (first blocks))))
    (testing "every known fact of a ling is a field, in reading order"
      (is (= [["id" "l1"] ["model" "venice/deepseek-v4-flash"] ["mode" "hive-agent"]
              ["project" "hive-olympus"] ["drones" "1"] ["done" "2"] ["task" "read layout"]
              ["activity" "turn 3: tool_calls=[read_file]"] ["seen" "<1m ago"] ["cell" "row 1, col 1"]]
             (:fields (nth blocks 3)))))
    (testing "a drone is labelled, names its ling, and a zero done count is omitted"
      (is (= {:block/type :heading :level 2 :text "d1  (drone)"} (nth blocks 4)))
      (is (= [["id" "d1"] ["model" "axon/glm-5.3"] ["ling" "l1"] ["cell" "row 1, col 2"]]
             (:fields (nth blocks 6)))))
    (testing "renders stay hive-vessel ops"
      (is (m/validate vs/ShowPanel panel)))))

(deftest the-empty-roster-still-shows-a-panel
  (is (= [{:op :ui/show-panel
           :panel/id "olympus/tab-1"
           :doc {:doc/title "Olympus  tab 1/1  (0 agents: 0 working, 0 blocked, 0 error, 0 idle)"
                 :doc/blocks [{:block/type :para :text "No active agents" :tone :muted}]}}]
         (render [] model/initial-state))))

(deftest status-tones
  (is (= {:idle :muted :working :info :blocked :warn :error :error :spawning :muted}
         view/status-tone))
  (is (str/ends-with? (view/summary {:total 1 :working 0 :blocked 0 :error 0 :idle 0 :spawning 1})
                      ", 1 spawning")))

(deftest ops-conform-to-hive-vessel-primitives
  (testing "the local op schemas are faithful to hive-vessel's"
    (doseq [panel (mg/sample s/ShowPanel {:size 8 :seed 7})]
      (is (m/validate vs/ShowPanel panel) (pr-str panel)))
    (doseq [close (mg/sample s/ClosePanel {:size 8 :seed 7})]
      (is (m/validate vs/ClosePanel close))))
  (testing "real renders validate as hive-vessel ops"
    (doseq [n [0 1 3 5 9]
            op (view/delta (render (take 9 (cycle roster)) model/initial-state)
                           (render (take n roster) model/initial-state))]
      (is (m/validate vs/Op op) (pr-str op)))))

(deftest delta-is-minimal
  (let [five (render roster model/initial-state)
        four (render (take 4 roster) model/initial-state)]
    (testing "first delivery is every panel"
      (is (= five (view/delta nil five))))
    (testing "no change, no ops"
      (is (= [] (view/delta five five))))
    (testing "a shrinking tab count closes the vanished panel first"
      (let [ops (view/delta five four)]
        (is (= {:op :ui/close-panel :panel/id "olympus/tab-2"} (first ops)))
        (is (= four (rest ops)))))
    (testing "only changed panels are re-sent"
      (let [moved (render roster {:active-tab 0 :focus "a5"})]
        (is (= ["olympus/tab-2"] (mapv :panel/id (view/delta five moved))))))))

(hst/deftrifecta-from-schema panels
  hive-olympus.view/panels
  {:in s/GridModel
   :out s/Panels
   :rel (fn [grid out]
          (let [n (count (:grid/tabs grid))]
            (and (= n (count out))
                 (= (mapv view/panel-id (range n)) (mapv :panel/id out))
                 (every? #(m/validate vs/ShowPanel %) out)
                 (every? (fn [[i p]] (str/includes? (get-in p [:doc :doc/title]) (str "tab " (inc i) "/" n)))
                         (map-indexed vector out)))))
   :classify (fn [grid _] (if (> (count (:grid/tabs grid)) 1) :tabbed :single))
   :classify-domain #{:tabbed :single}
   :classify-floor 2
   :num-tests 40})

(def ^:private original-panels view/panels)

(mut/deftest-mutations panels-hand-mutants
  hive-olympus.view/panels
  [["zero-based-ids" (fn [g] (mapv #(update % :panel/id (fn [id] (str/replace id #"\d+$" (fn [d] (str (dec (parse-long d)))))))
                                   (original-panels g)))]
   ["drops-last-tab" (fn [g] (vec (butlast (original-panels g))))]
   ["blocked-reads-info" (fn [g] (mapv (fn [p] (update-in p [:doc :doc/blocks]
                                                          (fn [bs] (mapv #(if (= "blocked" (:text %)) (assoc % :tone :info) %) bs))))
                                       (original-panels g)))]]
  (fn []
    (let [ps (render roster model/initial-state)]
      (is (= ["olympus/tab-1" "olympus/tab-2"] (mapv :panel/id ps)))
      (is (some #(= {:block/type :para :text "blocked" :tone :warn} %)
                (get-in (first ps) [:doc :doc/blocks]))))))
