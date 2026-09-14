(ns hive-olympus.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-olympus.layout :as layout]
            [hive-olympus.model :as model]
            [hive-olympus.schema :as s]
            [hive-schemas.test :as hst]
            [malli.core :as m]))

(defn- agents
  ([n] (agents n :idle))
  ([n status]
   (mapv #(hash-map :agent/id (str "ling-" %) :agent/name (str "worker-" %) :agent/status status)
         (range 1 (inc n)))))

(defn- cell-count [grid]
  (reduce + (map (comp count :tab/cells) (:grid/tabs grid))))

(deftest the-empty-roster-is-one-empty-tab
  (let [grid (model/grid-model [] model/initial-state)]
    (is (m/validate s/GridModel grid))
    (is (= [{:tab/index 0 :tab/active? true :tab/cells []}] (:grid/tabs grid)))
    (is (= {:total 0 :working 0 :blocked 0 :error 0 :idle 0 :spawning 0} (:grid/counts grid)))))

(deftest cells-come-from-the-canonical-layout
  (doseq [n (range 0 14)]
    (let [roster (agents n)
          grid (model/grid-model roster model/initial-state)
          from-model (for [{:tab/keys [index cells]} (:grid/tabs grid)
                           {:cell/keys [row col agent]} cells]
                       [(:agent/id agent) {:row row :col col
                                           :tab (when (layout/tabbed? (:grid/layout grid)) index)}])]
      (testing (str n " agents")
        (is (= (layout/place roster) (into {} from-model))
            "the model places agents exactly where layout/place does")
        (is (= (layout/tab-count (layout/calculate-layout n)) (count (:grid/tabs grid))))))))

(deftest five-agents-split-four-and-one
  (let [grid (model/grid-model (agents 5) {:active-tab 1 :focus "ling-5"})
        [t0 t1] (:grid/tabs grid)]
    (is (= 4 (count (:tab/cells t0))))
    (is (= 1 (count (:tab/cells t1))))
    (is (= [false true] (mapv :tab/active? [t0 t1])))
    (is (:cell/focused? (first (:tab/cells t1))))
    (is (= "ling-5" (:grid/focus grid)))))

(deftest state-is-clamped-and-unknown-focus-reads-nil
  (let [grid (model/grid-model (agents 2) {:active-tab 7 :focus "ghost"})]
    (is (= 0 (:grid/active-tab grid)))
    (is (nil? (:grid/focus grid)))
    (is (not-any? :cell/focused? (mapcat :tab/cells (:grid/tabs grid))))))

(deftest counts-histogram
  (is (= {:total 4 :working 2 :blocked 1 :error 0 :idle 0 :spawning 1}
         (model/counts [{:agent/status :working} {:agent/status :working}
                        {:agent/status :blocked} {:agent/status :spawning}]))))

(deftest tab-navigation-wraps
  (let [st model/initial-state]
    (is (= 1 (:active-tab (model/next-tab st 3))))
    (is (= 0 (:active-tab (model/next-tab {:active-tab 2 :focus nil} 3))))
    (is (= 2 (:active-tab (model/prev-tab st 3))))
    (is (= 0 (:active-tab (model/next-tab st 1))))
    (is (= 0 (:active-tab (model/prev-tab st 0))))))

(deftest focus-jumps-to-the-agents-tab
  (let [roster (agents 9)]
    (is (= {:active-tab 2 :focus "ling-9"} (model/focus model/initial-state roster "ling-9")))
    (is (= {:active-tab 1 :focus "ling-5"} (model/focus model/initial-state roster "ling-5")))
    (testing "an unknown or nil id clears focus and keeps the tab"
      (is (= {:active-tab 1 :focus nil} (model/focus {:active-tab 1 :focus "ling-5"} roster "ghost")))
      (is (= {:active-tab 1 :focus nil} (model/focus {:active-tab 1 :focus "ling-5"} roster nil))))))

(hst/deftrifecta-from-schema grid-model
  hive-olympus.model/grid-model
  {:in [:cat
         ;; Give empty, grid and tabbed rosters equal generator weight.
         [:schema {:gen/schema [:or
                                [:vector {:min 0 :max 0} s/Agent]
                                [:vector {:min 1 :max 4} s/Agent]
                                [:vector {:min 5 :max 12} s/Agent]]}
          s/Roster]
         s/OlympusState]
   :out s/GridModel
   :rel (fn [[roster state] grid]
          (let [n (count roster)
                tabs (:grid/tabs grid)]
            (and (= n (get-in grid [:grid/counts :total]))
                 (= n (cell-count grid))
                 (= (layout/tab-count (layout/calculate-layout n)) (count tabs))
                 (= 1 (count (filter :tab/active? tabs)))
                 (< (:grid/active-tab grid) (count tabs))
                 (every? #(<= (count (:tab/cells %)) layout/per-tab) tabs)
                 (= (reduce + (map #(get-in grid [:grid/counts %]) model/statuses)) n))))
   :classify (fn [[roster _] grid]
               (cond (empty? roster) :empty
                     (layout/tabbed? (:grid/layout grid)) :tabbed
                     :else :grid))
   :classify-domain #{:empty :grid :tabbed}
   :classify-floor 2
   :num-tests 60})
