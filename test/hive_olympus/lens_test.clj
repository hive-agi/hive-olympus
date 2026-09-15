(ns hive-olympus.lens-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-olympus.lens :as lens]
            [hive-olympus.schema :as s]
            [malli.core :as m]))

(def focused {:agent/id "ling-1" :agent/name "worker-1" :agent/status :working})

(defn- doc [text] {:doc/title "T" :doc/blocks [{:block/type :para :text text}]})

(deftest observe-reports-every-lens-in-id-order-and-isolates-failures
  ;; Ids sort by their printed form, so keywords (":...") precede strings.
  (let [sections (lens/observe {"zeta" (fn [a] (doc (:agent/id a)))
                                :alpha (fn [_] nil)
                                "mid" (fn [_] (throw (ex-info "boom" {})))
                                :odd (fn [_] {:doc/title 1})}
                               focused)]
    (is (m/validate s/LensSections sections))
    (is (= [:alpha :odd "mid" "zeta"] (mapv :lens/id sections)))
    (is (= [:empty :error :error :ok] (mapv :lens/status sections)))
    (is (re-find #"invalid document" (:error (second sections))))
    (is (= "boom" (:error (nth sections 2))))
    (is (= (doc "ling-1") (:doc (last sections))))
    (is (= {:alpha :empty :odd :error "mid" :error "zeta" :ok} (lens/status sections)))))

(deftest the-registry-keeps-fns-only
  (let [lenses (lens/create)]
    (is (nil? (lens/register! lenses :x "not a fn")))
    (is (= :x (lens/register! lenses :x (fn [_] nil))))
    (is (= "y" (lens/register! lenses "y" (fn [_] nil))))
    (is (= [:x "y"] (lens/ids lenses)))
    (testing "unregistering answers the id once"
      (is (= :x (lens/unregister! lenses :x)))
      (is (nil? (lens/unregister! lenses :x)))
      (is (= ["y"] (lens/ids lenses))))))
