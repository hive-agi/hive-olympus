(ns hive-olympus.operator-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [hive-addon.protocol :as addon]
            [hive-olympus.addon :as olympus]
            [hive-olympus.operator :as sut]
            [hive-olympus.harness :as harness]
            [hive-olympus.test-support :as t]
            [hive-vessel.core :as vessel]
            [hive-vessel.schema :as schema]
            [malli.core :as m]
            [hive-vessel.renderer :as renderer]
            [hive-spi.notify :as notify]))

(deftest projection-is-bounded-and-never-carries-authority
  (let [asks (atom {"ask-1" {:agent-id "child" :question "Need network"
                            :options ["deny" "escalate"] :response-chan (Object.)}})
        parent (atom {"ask-2" {:from "child" :to "parent" :question "May I write?"}})
        changes (atom 0)
        room (sut/open {:olympus/operator-sources {:human-asks asks :agent-asks parent}}
                       #(swap! changes inc))]
    (try
      (let [snapshot ((:snapshot room))]
        (is (= ["human" "parent"] (mapv :to (:requests snapshot))))
        (is (not (str/includes? (pr-str snapshot) "response-chan")))
        (is (m/validate schema/Doc (:doc (sut/panel snapshot)))))
      (swap! asks dissoc "ask-1")
      (is (= 1 @changes))
      (is (= ["ask-2"] (mapv :id (:requests ((:snapshot room))))))
      (doseq [n (range 150)]
        ((:observe! room) {:agent/id "sandbox" :event/type :run/failed
                          :event/at n :run/id (str n) :secret "must not escape"}))
      (let [events (:events ((:snapshot room)))]
        (is (= 100 (count events)))
        (is (= "149" (:message (last events))))
        (is (not (str/includes? (pr-str events) "must not escape"))))
      (finally ((:close room))))
    (let [before @changes]
      (reset! asks {})
      (is (= before @changes))
      (is (empty? (.getWatches asks)))
      (is (nil? ((:observe! room) {:message "after close"}))))))

(deftest event-driven-room-reaches-all-four-harnesses
  (doseq [[host-id dialect route]
          [["hive.vim" :vim-channel :dispatch]
           ["hive.deepseek" :text :dispatch]
           ["hive.vscode" :json :target]
           ["hive.emacs" :elisp :target]]]
    (let [asks (atom {})
          core (olympus/addon-ctor {:olympus/refresh-ms 0
                                    :olympus/roster-fn (constantly [])
                                    :olympus/operator-sources {:human-asks asks}})
          natives (atom [])
          received (promise)
          target {:vessel/id (keyword host-id) :vessel/dialect dialect
                  :vessel/features #{}
                  :vessel/execute! (fn [native]
                                     (swap! natives conj native)
                                     (when (str/includes? (pr-str native) "ask-live")
                                       (deliver received native))
                                     :ok)}
          host (t/->StubAddon host-id
                              (if (= route :dispatch)
                                {:vessel/dispatch! #(vessel/dispatch! (vessel/standard-registry) target %)}
                                {:vessel/target target}))
          _ (is (:success? (addon/initialize! core {})))
          config {:olympus/host host-id
                  :mount/dependencies {"hive.olympus" core host-id host}}
          h (harness/addon-ctor config)]
      (try
        (is (:success? (addon/initialize! h config)) host-id)
        (is (= :live (get-in (addon/health core) [:details :presenters host-id :status])) host-id)
        (swap! asks assoc "ask-live" {:agent-id "child" :question "Permission needed"
                                     :options ["deny" "escalate"]})
        (is (not= :timeout (deref received 3000 :timeout)) host-id)
        (is (= ["ask-live"] (mapv :id (:requests ((:olympus/operator-snapshot (addon/hooks core)))))))
        (is (every? #(= dialect (:native/dialect %)) @natives))
        (is (= ["deny" "escalate"] (get-in @asks ["ask-live" :options]))
            "Observation never answers or changes requests")
        (finally (addon/shutdown! h) (addon/shutdown! core)))
      (is (empty? (.getWatches asks)) host-id))))

(deftest question-updates-survive-roster-failure
  (let [asks (atom {})
        core (olympus/addon-ctor {:olympus/refresh-ms 0
                                  :olympus/roster-fn #(throw (ex-info "roster unavailable" {}))
                                  :olympus/operator-sources {:human-asks asks}})
        delivered (promise)]
    (try
      (is (:success? (addon/initialize! core {})))
      ((:olympus/register-presenter! (addon/hooks core))
       "operator" (fn [ops]
                    (when (str/includes? (pr-str ops) "still-visible")
                      (deliver delivered true))))
      (swap! asks assoc "still-visible" {:agent-id "child" :question "Help"})
      (is (= true (deref delivered 3000 :timeout)))
      (is (= :degraded (:status (addon/health core))))
      (is (= 1 (get-in (addon/health core) [:details :operator-room :pending])))
      (finally (addon/shutdown! core)))))

(deftest new-vessel-needs-no-application-specific-harness
  (let [received (promise)
        r (renderer/renderer ::new-editor
            (fn [] {:vessel/id ::new-editor :vessel/dialect :json
                    :vessel/execute! (fn [native]
                                       (when (str/includes? (pr-str native) "contract-event")
                                         (deliver received native)))}))
        core (olympus/addon-ctor {:olympus/refresh-ms 0
                                  :olympus/roster-fn (constantly [])
                                  :olympus/operator-sources {}})]
    (try
      (is (:success? (addon/initialize! core {})))
      (renderer/register! r)
      (is (satisfies? notify/INotify core))
      (is (:delivered? (notify/notify! core
                         {:event-type :run/failed :summary "contract-event"
                          :body "Run failed" :level :error :urgency :normal})))
      (is (not= :timeout (deref received 3000 :timeout)))
      (is (= :live (get-in (addon/health core) [:details :presenters ::new-editor :status])))
      (finally (renderer/unregister! r) (addon/shutdown! core)))
    (is (false? (:delivered? (notify/notify! core
                              {:event-type :run/failed :summary "after shutdown"}))))))
