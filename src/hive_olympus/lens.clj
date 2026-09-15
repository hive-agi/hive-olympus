(ns hive-olympus.lens
  "Lenses zoom into one agent. A lens is (fn [Agent] -> Doc | nil): nil means
   it has nothing to say about that agent. `observe` applies every registered
   lens to the focused agent and reports each as a LensSection; a lens that
   throws or answers an invalid document is :error and never affects another.

   The registry mirrors the presenter seat: an atom of id -> lens fn. Callers
   serialize mutations."
  (:require [hive-olympus.schema :as s]
            [malli.core :as m]
            [malli.error :as me]))

(defn create
  "A new, empty lens registry atom."
  []
  (atom {}))

(defn register!
  "Register LENS under ID on LENSES. Returns ID, or nil when LENS is not a fn."
  [lenses id lens]
  (when (fn? lens)
    (swap! lenses assoc id lens)
    id))

(defn unregister!
  "Forget ID on LENSES. Returns ID, or nil when it was not registered."
  [lenses id]
  (when (contains? @lenses id)
    (swap! lenses dissoc id)
    id))

(defn ids
  "Registered lens ids, in id order."
  [lenses]
  (vec (sort-by str (keys @lenses))))

(defn- section
  [id lens agent]
  (try
    (let [doc (lens agent)]
      (cond
        (nil? doc) {:lens/id id :lens/status :empty}
        (m/validate s/Doc doc) {:lens/id id :lens/status :ok :doc doc}
        :else {:lens/id id :lens/status :error
               :error (str "lens answered an invalid document: "
                           (pr-str (me/humanize (m/explain s/Doc doc))))}))
    (catch Throwable t
      {:lens/id id :lens/status :error :error (or (ex-message t) (str t))})))

(defn observe
  "One LensSection per lens of LENS-MAP (id -> fn) applied to AGENT, in id
   order."
  [lens-map agent]
  (mapv (fn [[id lens]] (section id lens agent))
        (sort-by (comp str key) lens-map)))

(defn status
  "id -> :lens/status of SECTIONS."
  [sections]
  (into {} (map (juxt :lens/id :lens/status)) sections))

(m/=> observe [:=> [:cat [:map-of s/LensId fn?] s/Agent] s/LensSections])
(m/=> status [:=> [:cat s/LensSections] [:map-of s/LensId s/LensStatus]])
