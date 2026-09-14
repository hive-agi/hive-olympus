(ns hive-olympus.test-runner
  (:require [clojure.test :as test]
            [hive-olympus.addon-test]
            [hive-olympus.harness-test]
            [hive-olympus.layout-test]
            [hive-olympus.model-test]
            [hive-olympus.mount-test]
            [hive-olympus.presenter-test]
            [hive-olympus.roster-test]
            [hive-olympus.view-test]
            [hive-olympus.operator-test]))

(def test-namespaces
  '[hive-olympus.layout-test hive-olympus.model-test hive-olympus.view-test
    hive-olympus.roster-test hive-olympus.presenter-test hive-olympus.addon-test
    hive-olympus.harness-test hive-olympus.mount-test hive-olympus.operator-test])

(defn -main
  [& _]
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))
