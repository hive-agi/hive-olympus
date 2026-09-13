(ns hive-olympus.layout
  "The canonical Olympus grid, pure.

     n = 0   {:rows 0 :cols 0}
     n = 1   {:rows 1 :cols 1}
     n = 2   {:rows 1 :cols 2}
     n = 3   {:rows 2 :cols 2 :empty-cells #{[1 1]}}
     n = 4   {:rows 2 :cols 2}
     n >= 5  {:tabs (ceil n/4) :per-tab 4}, each tab a 2x2 filled row-major

   `cell-positions` is the single placement rule; `assign-positions` and the
   grid model both read it."
  (:require [hive-olympus.schema :as s]
            [malli.core :as m]))

(def per-tab
  "Agents per tab once the layout overflows a single 2x2."
  4)

(defn calculate-layout
  "Layout for N agents. nil or negative N is the empty layout."
  [n]
  (cond
    (or (nil? n) (not (pos? n))) {:rows 0 :cols 0}
    (= n 1) {:rows 1 :cols 1}
    (= n 2) {:rows 1 :cols 2}
    (= n 3) {:rows 2 :cols 2 :empty-cells #{[1 1]}}
    (= n 4) {:rows 2 :cols 2}
    :else {:tabs (long (Math/ceil (/ (double n) per-tab))) :per-tab per-tab}))

(defn tabbed?
  "True when LAYOUT spreads agents across tabs."
  [layout]
  (contains? layout :tabs))

(defn tab-count
  "Number of tabs LAYOUT shows; a grid layout (even the empty one) is one tab."
  [layout]
  (if (tabbed? layout) (:tabs layout) 1))

(defn grid-capacity
  "Maximum agents LAYOUT holds."
  [layout]
  (if (tabbed? layout)
    (* (:tabs layout) (:per-tab layout))
    (- (* (or (:rows layout) 0) (or (:cols layout) 0))
       (count (or (:empty-cells layout) #{})))))

(defn cell-positions
  "The first N positions of LAYOUT in placement order, as Position maps.
   Grid layouts fill row-major skipping :empty-cells; tabbed layouts fill a
   2x2 per tab. Yields at most `grid-capacity` positions."
  [layout n]
  (let [n (max 0 (or n 0))]
    (if (tabbed? layout)
      (let [pt (:per-tab layout)]
        (->> (range (min n (grid-capacity layout)))
             (mapv (fn [idx]
                     (let [in-tab (mod idx pt)]
                       {:row (quot in-tab 2) :col (mod in-tab 2) :tab (quot idx pt)})))))
      (let [empty-cells (or (:empty-cells layout) #{})]
        (->> (for [r (range (or (:rows layout) 0))
                   c (range (or (:cols layout) 0))
                   :when (not (contains? empty-cells [r c]))]
               {:row r :col c :tab nil})
             (take n)
             vec)))))

(defn assign-positions
  "Map of agent id -> Position for AGENTS placed on LAYOUT in order."
  [agents layout]
  (let [agents (vec agents)]
    (zipmap (map :agent/id agents)
            (cell-positions layout (count agents)))))

(defn place
  "AGENTS on their own canonical layout: id -> Position."
  [agents]
  (assign-positions agents (calculate-layout (count agents))))

(defn position-for-cell
  "The agent id at ROW/COL/TAB in POSITIONS, or nil. TAB nil addresses a
   non-tabbed layout and never matches a tabbed position."
  [positions row col tab]
  (some (fn [[agent-id pos]]
          (when (and (= (:row pos) row) (= (:col pos) col) (= (:tab pos) tab))
            agent-id))
        positions))

(m/=> calculate-layout [:=> [:cat [:maybe :int]] s/Layout])
(m/=> tab-count [:=> [:cat s/Layout] pos-int?])
(m/=> grid-capacity [:=> [:cat s/Layout] nat-int?])
(m/=> cell-positions [:=> [:cat s/Layout [:maybe :int]] [:vector s/Position]])
(m/=> assign-positions [:=> [:cat [:sequential s/Agent] s/Layout] s/Positions])
(m/=> place [:=> [:cat [:sequential s/Agent]] s/Positions])
