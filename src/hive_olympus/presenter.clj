(ns hive-olympus.presenter
  "The presenter seat: harness targets registered by id, each receiving the
   view delta since its own last successful delivery.

   A target is (fn [ops]) where ops is a vector of hive-vessel op maps. A
   target that throws is marked :degraded with its error and keeps its last
   successful delivery, so the next broadcast retries the full delta; it never
   affects another presenter. Callers serialize seat mutations."
  (:require [hive-olympus.view :as view]))

(defn- offer
  "ENTRY after offering it the delta to CURRENT panels."
  [entry current]
  (let [ops (view/delta (:last entry) current)]
    (if (empty? ops)
      entry
      (try
        ((:target entry) ops)
        (-> entry
            (assoc :last current :status :live)
            (update :deliveries (fnil inc 0))
            (dissoc :error))
        (catch Throwable t
          (assoc entry :status :degraded :error (or (ex-message t) (str t))))))))

(defn create
  "A new, empty seat atom."
  []
  (atom {}))

(defn register!
  "Register TARGET under ID on SEAT and catch it up to CURRENT panels.
   Re-registering the same target keeps its delivery history; a different
   target starts fresh. Returns ID, or nil when TARGET is not a fn."
  [seat id target current]
  (when (fn? target)
    (swap! seat (fn [entries]
                  (let [existing (get entries id)
                        entry (if (= target (:target existing))
                                existing
                                {:target target :last nil :status :pending})]
                    (assoc entries id (offer entry current)))))
    id))

(defn unregister!
  "Forget ID on SEAT. Returns ID, or nil when it was not registered."
  [seat id]
  (when (contains? @seat id)
    (swap! seat dissoc id)
    id))

(defn broadcast!
  "Offer CURRENT panels to every presenter on SEAT. Returns the status map."
  [seat current]
  (swap! seat (fn [entries]
                (into {} (map (fn [[id entry]] [id (offer entry current)])) entries))))

(defn status
  "id -> {:status :live|:degraded|:pending, :deliveries n, :error msg?}."
  [seat]
  (into {}
        (map (fn [[id entry]]
               [id (cond-> {:status (:status entry) :deliveries (:deliveries entry 0)}
                     (:error entry) (assoc :error (:error entry)))]))
        @seat))
