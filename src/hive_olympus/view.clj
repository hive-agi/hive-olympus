(ns hive-olympus.view
  "GridModel -> hive-vessel ops, pure. One `:ui/show-panel` per tab with
   panel id \"olympus/tab-N\" (1-based) and a Doc of standard blocks
   (:heading, :para with tone, :fields). `delta` turns two renders into the
   minimal op batch: closes for vanished panels, then the panels that changed.

   The contract is the op shape; there is no compile dependency on hive-vessel."
  (:require [clojure.string :as str]
            [hive-olympus.model :as model]
            [hive-olympus.schema :as s]
            [malli.core :as m]))

(def status-tone
  "Tone of each agent status."
  {:idle :muted
   :working :info
   :blocked :warn
   :error :error
   :spawning :muted})

(defn panel-id
  "Panel id of the tab at zero-based INDEX."
  [index]
  (str "olympus/tab-" (inc index)))

(defn summary
  "Header count text, e.g. \"5 agents: 2 working, 1 blocked, 0 error, 2 idle\"."
  [{:keys [total working blocked error idle spawning]}]
  (str total (if (= 1 total) " agent" " agents") ": "
       working " working, " blocked " blocked, " error " error, " idle " idle"
       (when (pos? spawning) (str ", " spawning " spawning"))))

(defn title
  "Panel title for the tab at INDEX of N-TABS."
  [counts index n-tabs]
  (str "Olympus  tab " (inc index) "/" n-tabs "  (" (summary counts) ")"))

(defn- present [s]
  (when-not (str/blank? s) s))

(defn agent-fields
  "The [label value] rows AGENT can fill, in reading order; absent facts are
   omitted rather than shown empty."
  [{:agent/keys [id mode project parent drones done task activity seen] :as agent}]
  (->> [["id" id]
        ["model" (model/route agent)]
        ["mode" mode]
        ["project" project]
        ["ling" parent]
        ["drones" (some-> drones str)]
        ["done" (when (and done (pos? done)) (str done))]
        ["task" task]
        ["activity" activity]
        ["seen" seen]]
       (keep (fn [[label v]] (when-let [v (present v)] [label v])))
       vec))

(defn cell-blocks
  "Blocks for one grid cell: heading, toned status para, fields."
  [{:cell/keys [row col agent focused?]}]
  (let [{:agent/keys [id name status kind exited?]} agent]
    [{:block/type :heading
      :level 2
      :text (str (when focused? "> ")
                 (if (str/blank? name) id name)
                 (when (= :drone kind) "  (drone)"))}
     {:block/type :para
      :text (str (clojure.core/name status)
                 (when exited? "  (exited)")
                 (when focused? "  (focused)"))
      :tone (status-tone status)}
     {:block/type :fields
      :fields (conj (agent-fields agent)
                    ["cell" (str "row " (inc row) ", col " (inc col))])}]))

(defn routes-text
  "\"routes: venice/deepseek-v4-flash x2, axon/glm-5.3\" for ROUTES, or nil."
  [routes]
  (when (seq routes)
    (str "routes: "
         (str/join ", " (map (fn [[label n]] (if (> n 1) (str label " x" n) label)) routes)))))

(defn tab-blocks
  "Blocks for TAB of N-TABS under the swarm-wide ROUTES."
  ([tab n-tabs] (tab-blocks tab n-tabs nil))
  ([{:tab/keys [active? cells]} n-tabs routes]
   (let [cells (sort-by (juxt :cell/row :cell/col) cells)]
     (vec
      (concat
       (when (> n-tabs 1)
         [{:block/type :para
           :text (if active? "active tab" "inactive tab")
           :tone (if active? :info :muted)}])
       (when-let [t (routes-text routes)]
         [{:block/type :para :text t :tone :muted}])
       (if (seq cells)
         (mapcat cell-blocks cells)
         [{:block/type :para :text "No active agents" :tone :muted}]))))))

(defn panels
  "One :ui/show-panel op per tab of MODEL, in tab order."
  [{:grid/keys [tabs counts routes]}]
  (let [n-tabs (count tabs)]
    (vec (map-indexed
          (fn [i tab]
            {:op :ui/show-panel
             :panel/id (panel-id i)
             :doc {:doc/title (title counts i n-tabs)
                   :doc/blocks (tab-blocks tab n-tabs routes)}})
          tabs))))

(def focus-panel-id
  "Panel id of the zoom into the focused agent."
  "olympus/focus")

(defn- section-blocks
  "Blocks of one lens observation: its document under a level-2 heading, an
   error in error tone, nothing for a lens with nothing to say."
  [{:lens/keys [id status] :keys [doc error]}]
  (case status
    :ok (into [{:block/type :heading :level 2 :text (:doc/title doc)}] (:doc/blocks doc))
    :error [{:block/type :heading :level 2 :text (str "lens " (if (keyword? id) (name id) (str id)))}
            {:block/type :para :tone :error :text (str "lens failed: " error)}]
    []))

(defn activity-blocks
  "The focused AGENT's recent log under its own heading, newest first, or
   nothing when the roster source told us none. Grid cells never show this:
   the log is what the zoom is for."
  [{:agent/keys [recent]}]
  (if (seq recent)
    [{:block/type :heading :level 2 :text (str "Activity (" (count recent) ")")}
     {:block/type :list :items (vec recent)}]
    [{:block/type :heading :level 2 :text "Activity"}
     {:block/type :para :tone :muted :text "No activity recorded for this agent"}]))

(defn focus-panel
  "The :ui/show-panel zooming into MODEL's focused agent: its cell, its recent
   activity, then one section per lens in SECTIONS. nil when nothing is
   focused."
  [model sections]
  (when-let [cell (model/focused-cell model)]
    (let [{:agent/keys [id name] :as agent} (:cell/agent cell)
          shown (remove #(= :empty (:lens/status %)) sections)]
      {:op :ui/show-panel
       :panel/id focus-panel-id
       :doc {:doc/title (str "Olympus  focus  " (if (str/blank? name) id name))
             :doc/blocks (-> (cell-blocks cell)
                             (into (activity-blocks agent))
                             (into (if (seq shown)
                                     (mapcat section-blocks shown)
                                     [{:block/type :para :tone :muted
                                       :text (if (seq sections)
                                               "No lens has anything on this agent"
                                               "No lenses registered")}])))}})))

(def transcript-panel-id
  "Panel id of the transcript an observer opened on purpose."
  "olympus/transcript")

(defn exchange-blocks
  "One exchange as blocks: a heading naming the turn and the speaker, the
   message body, and the tools that turn called."
  [{:keys [turn role text tools clipped? length score]}]
  (into [{:block/type :heading
          :level 2
          :text (str (when turn (str "turn " turn "  "))
                     (or role "?")
                     (when score (str "  score " (format "%.2f" (double score)))))}]
        (cond-> []
          text (conj {:block/type :para :text text})
          clipped? (conj {:block/type :para :tone :muted
                          :text (str "clipped here; " length " characters in full")})
          (seq tools) (conj {:block/type :fields
                             :fields [["tools" (str/join ", " tools)]]}))))

(defn transcript-panel
  "The `:ui/show-panel` showing ANSWER, the transcript port's reply about one
   agent. An error is a panel too: an observer who asked a question has to
   see why nothing came back."
  [{:keys [agent-id exchanges total query error note truncated?] shown :count :as answer}]
  (when answer
    {:op :ui/show-panel
     :panel/id transcript-panel-id
     :doc {:doc/title (str "Olympus  transcript  " (or agent-id "?"))
           :doc/blocks
           (into [{:block/type :heading
                   :level 1
                   :text (if query
                           (str "Transcript search  " (pr-str query))
                           (str "Transcript  " (or agent-id "?")))}]
                 (cond
                   error
                   [{:block/type :para :tone :error :text error}]

                   (seq exchanges)
                   (into [{:block/type :para :tone :muted
                           :text (str shown " of " total " exchanges, newest first"
                                      (when truncated? "; raise the limit to see more"))}]
                         (mapcat exchange-blocks exchanges))

                   :else
                   [{:block/type :para :tone :muted
                     :text (or note "no exchanges recorded")}]))}}))

(m/=> focus-panel [:=> [:cat s/GridModel s/LensSections] [:maybe s/ShowPanel]])

(defn delta
  "Ops that move a vessel showing PREVIOUS (a panels vector, nil when nothing
   was delivered) to CURRENT: a :ui/close-panel per panel id that vanished,
   then every CURRENT panel absent from or different in PREVIOUS. Empty when
   nothing changed."
  [previous current]
  (let [prev-by-id (into {} (map (juxt :panel/id identity)) previous)
        cur-ids (into #{} (map :panel/id) current)]
    (into (vec (for [p previous
                     :when (not (contains? cur-ids (:panel/id p)))]
                 {:op :ui/close-panel :panel/id (:panel/id p)}))
          (remove #(= % (get prev-by-id (:panel/id %))))
          current)))

(m/=> summary [:=> [:cat s/Counts] :string])
(m/=> cell-blocks [:=> [:cat s/GridCell] [:vector s/Block]])
(m/=> panels [:=> [:cat s/GridModel] s/Panels])
(m/=> delta [:=> [:cat [:maybe [:sequential s/ShowPanel]] [:sequential s/ShowPanel]] s/Ops])
