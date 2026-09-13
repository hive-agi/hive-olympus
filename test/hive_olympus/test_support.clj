(ns hive-olympus.test-support
  "IAddon stubs and roster fixtures shared by the boundary tests."
  (:require [hive-addon.protocol :as addon]))

(defrecord StubAddon [id hook-map]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _] {:success? true})
  (shutdown! [_] nil)
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] {:status :ok})
  (excluded-tools [_] #{})
  (hooks [_] (if (instance? clojure.lang.IDeref hook-map) @hook-map hook-map)))

(defn agents
  "N idle agents ling-1..ling-N."
  ([n] (agents n :idle))
  ([n status]
   (mapv #(hash-map :agent/id (str "ling-" %) :agent/name (str "worker-" %) :agent/status status)
         (range 1 (inc n)))))

(defn recorder
  "[calls-atom target-fn]: target-fn appends each ops batch to calls-atom."
  []
  (let [calls (atom [])]
    [calls (fn [ops] (swap! calls conj ops) {:ok ops})]))

(defn panel-ids
  "Panel ids of the :ui/show-panel ops in OPS."
  [ops]
  (->> ops (filter #(= :ui/show-panel (:op %))) (mapv :panel/id)))

(defn eventually
  "Poll PRED every 10ms up to TIMEOUT-MS; its last value."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (pred)]
        (if (or v (> (System/currentTimeMillis) deadline))
          v
          (do (Thread/sleep 10) (recur)))))))
