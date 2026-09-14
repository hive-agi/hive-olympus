(ns hive-olympus.layout-test
  "The canonical grid, ported from hive-emacs.olympus-test with agents keyed
   by :agent/id, plus schema-synthesized property and mutation coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-olympus.layout :as layout]
            [hive-olympus.schema :as s]
            [hive-schemas.test :as hst]
            [hive-test.mutation :as mut]))

(defn- agents [n]
  (mapv #(hash-map :agent/id (str "ling-" %) :agent/name (str "worker-" %) :agent/status :idle)
        (range 1 (inc n))))

(deftest calculate-layout-grid-sizes
  (testing "n=1 is full screen"
    (is (= {:rows 1 :cols 1} (layout/calculate-layout 1))))
  (testing "n=2 is side-by-side"
    (is (= {:rows 1 :cols 2} (layout/calculate-layout 2))))
  (testing "n=3 is 2x2 with the last cell blanked"
    (is (= {:rows 2 :cols 2 :empty-cells #{[1 1]}} (layout/calculate-layout 3))))
  (testing "n=4 is a perfect 2x2"
    (is (= {:rows 2 :cols 2} (layout/calculate-layout 4)))))

(deftest calculate-layout-tabs-past-four
  (testing "n=5 opens a second tab"
    (is (= {:tabs 2 :per-tab 4} (layout/calculate-layout 5))))
  (testing "n=8 still fits two tabs"
    (is (= 2 (:tabs (layout/calculate-layout 8)))))
  (testing "n=9 needs three"
    (is (= 3 (:tabs (layout/calculate-layout 9))))))

(deftest calculate-layout-edge-cases
  (testing "n=0 is an empty grid"
    (is (= {:rows 0 :cols 0} (layout/calculate-layout 0))))
  (testing "negative n degrades to empty rather than throwing"
    (is (= 0 (:rows (layout/calculate-layout -1)))))
  (testing "nil n degrades to empty"
    (is (= 0 (:rows (layout/calculate-layout nil))))))

(deftest assign-positions-fills-cells-in-order
  (testing "one agent takes the only cell"
    (let [positions (layout/assign-positions (agents 1) {:rows 1 :cols 1})]
      (is (= {"ling-1" {:row 0 :col 0 :tab nil}} positions))))
  (testing "two agents sit side by side"
    (let [positions (layout/assign-positions (agents 2) {:rows 1 :cols 2})]
      (is (= {:row 0 :col 0 :tab nil} (get positions "ling-1")))
      (is (= {:row 0 :col 1 :tab nil} (get positions "ling-2"))))))

(deftest assign-positions-skips-empty-cells
  (let [positions (layout/assign-positions (agents 3) {:rows 2 :cols 2 :empty-cells #{[1 1]}})]
    (is (= 3 (count positions)))
    (is (= {:row 0 :col 0 :tab nil} (get positions "ling-1")))
    (is (= {:row 0 :col 1 :tab nil} (get positions "ling-2")))
    (is (= {:row 1 :col 0 :tab nil} (get positions "ling-3")))
    (is (not-any? #(= {:row 1 :col 1 :tab nil} %) (vals positions)))))

(deftest assign-positions-overflows-into-tabs
  (let [positions (layout/assign-positions (agents 5) {:tabs 2 :per-tab 4})]
    (is (= 5 (count positions)))
    (is (= {:row 0 :col 0 :tab 0} (get positions "ling-1")))
    (is (= {:row 1 :col 1 :tab 0} (get positions "ling-4")))
    (is (= {:row 0 :col 0 :tab 1} (get positions "ling-5")))))

(deftest assign-positions-empty-input
  (is (= {} (layout/assign-positions [] {:rows 0 :cols 0}))))

(deftest grid-capacity-and-tabbed
  (testing "capacity subtracts blanked cells"
    (is (= 3 (layout/grid-capacity {:rows 2 :cols 2 :empty-cells #{[1 1]}})))
    (is (= 4 (layout/grid-capacity {:rows 2 :cols 2})))
    (is (= 0 (layout/grid-capacity {:rows 0 :cols 0}))))
  (testing "capacity of a tabbed layout is tabs * per-tab"
    (is (= 8 (layout/grid-capacity {:tabs 2 :per-tab 4}))))
  (testing "tabbed? keys off :tabs"
    (is (true? (layout/tabbed? {:tabs 2 :per-tab 4})))
    (is (false? (layout/tabbed? {:rows 2 :cols 2}))))
  (testing "a grid layout, even the empty one, is one tab"
    (is (= 1 (layout/tab-count {:rows 0 :cols 0})))
    (is (= 3 (layout/tab-count (layout/calculate-layout 9))))))

(deftest position-for-cell-reverse-lookup
  (let [positions (layout/place (agents 4))]
    (is (= "ling-1" (layout/position-for-cell positions 0 0 nil)))
    (is (= "ling-4" (layout/position-for-cell positions 1 1 nil)))
    (is (nil? (layout/position-for-cell positions 5 5 nil)))
    (is (nil? (layout/position-for-cell positions 0 0 0))
        "a tab mismatch must not match a non-tabbed position")))

(deftest full-layout-flow
  (let [positions (layout/place (agents 4))]
    (is (= 4 (count positions)))
    (is (= #{{:row 0 :col 0 :tab nil} {:row 0 :col 1 :tab nil}
             {:row 1 :col 0 :tab nil} {:row 1 :col 1 :tab nil}}
           (set (vals positions))))))

(hst/deftrifecta-from-schema calculate-layout
  hive-olympus.layout/calculate-layout
  ;; Uniform ints over [-3, 40] leave the four grid sizes at ~10% of draws, so
  ;; a seed could starve :grid. The elements weight every branch boundary.
  {:in [:maybe [:int {:min -3 :max 40
                      :gen/elements [-3 -1 0 1 2 3 4 5 8 9 12 13 40]}]]
   :out s/Layout
   :rel (fn [n out]
          (let [n (max 0 (or n 0))
                cap (layout/grid-capacity out)]
            (and (>= cap n) (< (- cap n) 4)
                 (= (count (layout/cell-positions out n)) n))))
   :classify (fn [n out]
               (cond (layout/tabbed? out) :tabbed
                     (zero? (layout/grid-capacity out)) :empty
                     :else :grid))
   :classify-domain #{:tabbed :empty :grid}
   :classify-floor 2
   :num-tests 80})

(hst/deftrifecta-from-schema place
  hive-olympus.layout/place
  {:in s/Roster
   :out s/Positions
   :rel (fn [roster out]
          (let [ids (set (map :agent/id roster))
                cells (vals out)]
            (and (= ids (set (keys out)))
                 (= (count cells) (count (set cells)))
                 (every? (fn [{:keys [row col]}] (and (< row 2) (< col 2))) cells))))
   :mutation false
   :num-tests 60})

(def ^:private original-calculate-layout layout/calculate-layout)

(mut/deftest-mutations calculate-layout-hand-mutants
  hive-olympus.layout/calculate-layout
  [["three-without-blank" (fn [n] (if (= n 3) {:rows 2 :cols 2} (original-calculate-layout n)))]
   ["tabs-floor" (fn [n] (if (and n (> n 4)) {:tabs (quot n 4) :per-tab 4} (original-calculate-layout n)))]
   ["two-stacked" (fn [n] (if (= n 2) {:rows 2 :cols 1} (original-calculate-layout n)))]
   ["tabs-at-four" (fn [n] (if (= n 4) {:tabs 1 :per-tab 4} (original-calculate-layout n)))]]
  (fn []
    (is (= {:rows 1 :cols 2} (layout/calculate-layout 2)))
    (is (= {:rows 2 :cols 2 :empty-cells #{[1 1]}} (layout/calculate-layout 3)))
    (is (= {:rows 2 :cols 2} (layout/calculate-layout 4)))
    (is (= 3 (:tabs (layout/calculate-layout 9))))))
