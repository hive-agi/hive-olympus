(ns hive-olympus.model
  "Roster + Olympus state -> GridModel, and the pure navigation transitions
   over OlympusState. Placement is `hive-olympus.layout`; nothing here
   re-derives a grid."
  (:require [hive-olympus.layout :as layout]
            [hive-olympus.schema :as s]
            [malli.core :as m]))

(def initial-state
  "Navigation state before any interaction."
  {:active-tab 0 :focus nil})

(def statuses
  "Every agent status, in header order."
  [:working :blocked :error :idle :spawning])

(defn counts
  "Status histogram of AGENTS plus :total."
  [agents]
  (let [freq (frequencies (map :agent/status agents))]
    (into {:total (count agents)}
          (map (fn [st] [st (get freq st 0)]))
          statuses)))

(defn route
  "The provider/model label an AGENT runs on, or nil when neither is known."
  [{:agent/keys [provider model]}]
  (cond
    (and provider model) (str provider "/" model)
    :else (or model provider)))

(defn routes
  "[label count] for every route among AGENTS, most used first, then by label."
  [agents]
  (->> agents
       (keep route)
       frequencies
       (sort-by (fn [[label n]] [(- n) label]))
       (mapv vec)))

(defn clamp-tab
  "TAB bounded to [0, TAB-COUNT - 1]."
  [tab tab-count]
  (-> (or tab 0) (max 0) (min (dec (max 1 tab-count)))))

(defn grid-model
  "The GridModel of ROSTER under STATE. Always at least one tab; the active
   tab is clamped; a focus naming no rostered agent reads as nil."
  [roster state]
  (let [agents (vec roster)
        lay (layout/calculate-layout (count agents))
        n-tabs (layout/tab-count lay)
        active (clamp-tab (:active-tab state) n-tabs)
        focus (let [f (:focus state)]
                (when (some #(= f (:agent/id %)) agents) f))
        cells (map (fn [agent {:keys [row col tab]}]
                     {:tab (or tab 0)
                      :cell {:cell/row row
                             :cell/col col
                             :cell/agent agent
                             :cell/focused? (= focus (:agent/id agent))}})
                   agents
                   (layout/cell-positions lay (count agents)))
        by-tab (group-by :tab cells)]
    {:grid/layout lay
     :grid/tabs (mapv (fn [i]
                        {:tab/index i
                         :tab/active? (= i active)
                         :tab/cells (mapv :cell (get by-tab i))})
                      (range n-tabs))
     :grid/active-tab active
     :grid/focus focus
     :grid/counts (counts agents)
     :grid/routes (routes agents)}))

(defn tab-of
  "Tab index holding AGENT-ID in ROSTER's layout, or nil."
  [roster agent-id]
  (let [agents (vec roster)
        lay (layout/calculate-layout (count agents))]
    (some (fn [[agent pos]]
            (when (= agent-id (:agent/id agent)) (or (:tab pos) 0)))
          (map vector agents (layout/cell-positions lay (count agents))))))

(defn focused-cell
  "The GridCell of GRID's focused agent, or nil when nothing is focused."
  [{:grid/keys [focus tabs]}]
  (when focus
    (some (fn [cell] (when (= focus (get-in cell [:cell/agent :agent/id])) cell))
          (mapcat :tab/cells tabs))))

(defn next-tab
  "STATE advanced one tab, wrapping after the last of TAB-COUNT."
  [state tab-count]
  (let [n (max 1 tab-count)]
    (assoc state :active-tab (mod (inc (clamp-tab (:active-tab state) n)) n))))

(defn prev-tab
  "STATE moved back one tab, wrapping before the first of TAB-COUNT."
  [state tab-count]
  (let [n (max 1 tab-count)]
    (assoc state :active-tab (mod (dec (clamp-tab (:active-tab state) n)) n))))

(defn focus
  "STATE focused on AGENT-ID, jumping to its tab when it is rostered. A nil
   or unknown AGENT-ID clears focus and keeps the active tab."
  [state roster agent-id]
  (if-let [tab (and agent-id (tab-of roster agent-id))]
    (assoc state :focus agent-id :active-tab tab)
    (assoc state :focus nil)))

(m/=> counts [:=> [:cat [:sequential s/Agent]] s/Counts])
(m/=> routes [:=> [:cat [:sequential s/Agent]] [:vector [:tuple :string pos-int?]]])
(m/=> grid-model [:=> [:cat [:sequential s/Agent] s/OlympusState] s/GridModel])
(m/=> tab-of [:=> [:cat [:sequential s/Agent] :string] [:maybe nat-int?]])
(m/=> next-tab [:=> [:cat s/OlympusState :int] s/OlympusState])
(m/=> prev-tab [:=> [:cat s/OlympusState :int] s/OlympusState])
(m/=> focus [:=> [:cat s/OlympusState [:sequential s/Agent] [:maybe :string]] s/OlympusState])
