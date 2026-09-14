(ns hive-olympus.operator
  "Read-only operator projection. No response channels or permission grants cross this boundary."
  (:require [clojure.string :as str]))

(defn text [x]
  (apply str (take 500 (remove #(Character/isISOControl ^char %)
                               (str (or x ""))))))

(defn requests [kind entries]
  (mapv (fn [[id r]]
          {:id (text id) :kind kind
           :from (text (or (:from r) (:agent-id r)))
           :to (text (or (:to r) "human"))
           :question (text (:question r))
           :options (mapv text (take 20 (:options r)))})
        (take 100 (sort-by (comp str key) entries))))

(defn event [e]
  (let [at (or (:timestamp e) (:event/at e))]
    {:agent (text (or (:agent/id e) (:agent-id e) (:from e)))
     :kind (text (or (:event/type e) (:event-type e) (:type e) (:run/status e)))
     :message (text (or (:message e) (:task e) (:run/id e)))
     :timestamp (text (if (number? at) (java.time.Instant/ofEpochMilli (long at)) at))}))

(defn panel [{:keys [requests events unavailable]}]
  {:op :ui/show-panel :panel/id "olympus/operator"
   :doc {:doc/title "Olympus operator room"
         :doc/blocks
         (vec
          (concat
           [{:block/type :heading :text "Operator room" :level 1}
            {:block/type :para :tone :info
             :text "Least privilege. Requests cascade agent -> parent -> human. Silence grants nothing."}
            {:block/type :para :tone :muted
             :text "Read-only observation. Human questions: use hivemind respond with the original request ID. Agent questions: reply through the originating conversation."}]
           (when (seq unavailable)
             [{:block/type :para :tone :warn
               :text (str "Sources unavailable: " (str/join ", " (map name unavailable)))}])
           [{:block/type :heading :text (str "Pending questions (" (count requests) ")") :level 2}]
           (if (seq requests)
             (mapcat (fn [{:keys [id from to question options kind]}]
                       [{:block/type :fields
                         :fields [["Request" id] ["Route" (str from " -> " to)] ["Kind" (name kind)]]}
                        {:block/type :para :text question}
                        {:block/type :list :items options}]) requests)
             [{:block/type :para :text "No pending questions in available sources." :tone :muted}])
           [{:block/type :heading :text "Recent activity" :level 2}
            {:block/type :list
             :items (mapv (fn [{:keys [agent kind message timestamp]}]
                            (str timestamp " " agent " " kind " " message)) events)}]))}})

(defn- live-ref [sym]
  (when-let [n (find-ns (symbol (namespace sym)))]
    (when-let [v (ns-resolve n (symbol (name sym)))]
      (let [x @v]
        (cond (instance? clojure.lang.IRef x) x
              (instance? clojure.lang.IRef (:atom x)) (:atom x))))))

(defn open
  "Create observation ports. Sources are watchable atoms; production resolves already-loaded host state.
   Watch callbacks only request a refresh. close removes every watch; observe! accepts bounded activity."
  [config changed!]
  (let [closed? (atom false)
        watched (atom #{})
        key (Object.)
        local-events (atom [])
        configured (:olympus/operator-sources config)
        sources (fn []
                  (or configured
                      {:human-asks (live-ref 'hive-mcp.hivemind.state/pending-asks)
                       :agent-asks (live-ref 'hive-mcp.hivemind.conversation/pending-asks)
                       :agents (live-ref 'hive-mcp.hivemind.state/agent-registry)}))
        watch! (fn [ref]
                 (when (and ref (not @closed?) (not (contains? @watched ref)))
                   (add-watch ref key (fn [_ _ before after]
                                        (when (and (not @closed?) (not= before after))
                                          (changed!))))
                   (swap! watched conj ref)))]
    (watch! local-events)
    {:snapshot
     (fn []
       (let [refs (sources)]
         (doseq [ref (vals refs)] (watch! ref))
         (let [values (into {} (map (fn [[k ref]] [k (when ref @ref)])) refs)
               activity (for [[id entry] (:agents values)
                              msg (:messages (or (:data entry) entry))]
                          (event (assoc msg :agent-id id)))]
           {:requests (into (requests :human (:human-asks values))
                            (requests :agent (:agent-asks values)))
            :events (vec (take-last 100 (sort-by :timestamp (concat activity @local-events))))
            :unavailable (vec (sort (for [[k ref] refs :when (nil? ref)] k)))})))
     :observe! (fn [e]
                 (when-not @closed?
                   (swap! local-events #(vec (take-last 100 (conj % (event e)))))
                   true))
     :close (fn []
              (reset! closed? true)
              (doseq [ref @watched] (remove-watch ref key))
              (reset! watched #{}))}))
