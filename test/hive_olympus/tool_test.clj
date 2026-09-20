(ns hive-olympus.tool-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.schema :as as]
            [hive-olympus.model :as model]
            [hive-olympus.tool :as tool]
            [malli.core :as m]))

(def roster
  [{:agent/id "a1" :agent/name "review-engine-asr" :agent/status :working :agent/kind :ling
    :agent/provider "venice" :agent/model "z-ai-glm-5-3-flash" :agent/mode "hive-agent"
    :agent/project "vtranslate" :agent/task "AUDIT 7 kanban cards" :agent/seen "<1m ago"
    :agent/activity "turn 13: tool_calls=[\"bash\"]"
    :agent/recent ["<1m ago  turn 13: tool_calls=[\"bash\"]"
                   "1m ago  turn 12: tool_calls=[\"bash\"]"
                   "2m ago  turn 11: tool_calls=[\"bash\"]"]}
   {:agent/id "a2" :agent/name "quiet" :agent/status :idle :agent/kind :ling}
   {:agent/id "d1" :agent/name "drone-one" :agent/status :working :agent/kind :drone
    :agent/parent "a1"}])

(defn- core
  "A stub of the core's state atom plus a nav that records what it was asked
   to do, so the tool is exercised over the shape the addon really passes."
  []
  (let [focused (atom nil)
        tabs (atom 0)
        state (atom {:roster roster
                     :model (model/grid-model roster model/initial-state)
                     :panels [{:op :ui/show-panel :panel/id "olympus/tab-1"
                               :doc {:doc/title "Olympus  tab 1/1" :doc/blocks [{:block/type :para :text "x"}]}}]
                     :lens-sections [{:lens/id "carto-flow" :lens/status :ok}]})
        nav {:focus! (fn [id] (reset! focused id)
                       (swap! state assoc :model (model/grid-model roster (assoc model/initial-state :focus id))))
             :next-tab! (fn [] {:tab (swap! tabs inc)})
             :prev-tab! (fn [] {:tab (swap! tabs dec)})
             :refresh! (fn [] (swap! state update :refreshes (fnil inc 0)))}]
    {:state state :nav nav :focused focused}))

(defn- ask [{:keys [state nav]} params]
  (tool/answer state nav params))

(deftest the-tool-def-is-a-valid-addon-tool
  (let [{:keys [state nav]} (core)
        t (tool/tool state nav)]
    (is (m/validate as/ToolDef t))
    (is (= "olympus" (:name t)))
    (testing "the handler speaks MCP content, and string keys as the host sends them"
      (let [out ((:handler t) {"command" "agents"})
            answered (edn/read-string (get-in out [:content 0 :text]))]
        (is (nil? (:isError out)))
        (is (= 3 (count (:agents answered))))))
    (testing "an unknown command is an error result, not a throw"
      (let [out ((:handler t) {"command" "sudo"})]
        (is (true? (:isError out)))
        (is (re-find #"unknown command" (get-in out [:content 0 :text])))))))

(deftest agents-answers-the-whole-swarm
  (let [c (core)
        {:keys [agents counts]} (ask c {:command "agents"})
        alpha (first (filter #(= "a1" (:id %)) agents))]
    (is (= 3 (count agents)))
    (is (= 2 (:working counts)))
    (testing "a row carries what the roster knew and omits what it did not"
      (is (= "venice/z-ai-glm-5-3-flash" (:route alpha)))
      (is (= "vtranslate" (:project alpha)))
      (is (= 3 (:activity-lines alpha)) "the log is summarized here, not inlined")
      (is (not (contains? alpha :recent)))
      (is (not (contains? (first (filter #(= "a2" (:id %)) agents)) :route))))
    (testing "status narrows"
      (is (= ["a1" "d1"] (mapv :id (:agents (ask c {:command "agents" :status "working"})))))
      (is (= [] (:agents (ask c {:command "agents" :status "blocked"})))))))

(deftest activity-answers-what-one-subagent-has-been-doing
  (let [c (core)]
    (testing "the log comes back in full, newest first, without moving the view"
      (let [a (ask c {:command "activity" :agent "a1"})]
        (is (= 3 (count (:recent a))))
        (is (= "<1m ago  turn 13: tool_calls=[\"bash\"]" (first (:recent a))))
        (is (nil? @(:focused c)) "activity is read-only")))
    (testing "limit truncates and says so"
      (let [a (ask c {:command "activity" :agent "a1" :limit 2})]
        (is (= 2 (count (:recent a))))
        (is (true? (:truncated? a)))
        (is (= 3 (:recent-total a)))))
    (testing "an agent that never spoke says so rather than looking broken"
      (is (re-find #"no shouts" (:note (ask c {:command "activity" :agent "a2"})))))
    (testing "a name, or a unique substring, finds the agent"
      (is (= "a1" (:id (ask c {:command "activity" :agent "review-engine-asr"}))))
      (is (= "a1" (:id (ask c {:command "activity" :agent "ENGINE"})))))
    (testing "an ambiguous or absent needle refuses and says what exists"
      (is (:error (ask c {:command "activity" :agent "nope"})))
      (is (= ["a1" "a2" "d1"] (:agents (ask c {:command "activity" :agent "nope"}))))
      (is (:error (ask c {:command "activity"}))))))

(deftest watch-moves-the-zoom-and-answers-in-one-call
  (let [c (core)
        a (ask c {:command "watch" :agent "a1"})]
    (is (= "a1" (:focused a)))
    (is (= "a1" @(:focused c)) "the panels really moved")
    (is (= "olympus/focus" (:panel a)))
    (is (= 3 (count (get-in a [:agent :recent]))) "and it answers the log too")
    (is (= [{:lens "carto-flow" :status :ok}] (:lenses a)))
    (testing "unwatch closes it"
      (let [b (ask c {:command "unwatch"})]
        (is (nil? (:focused b)))
        (is (true? (:closed? b)))
        (is (nil? @(:focused c)))))))

(deftest the-viewport-commands-move-only-the-view
  (let [c (core)]
    (testing "tabs are reported 1-based, the way the panel titles read"
      ;; the stub's counter is 0-based, as OlympusState's :tab is
      (is (= 2 (:tab (ask c {:command "next-tab"}))))
      (is (= 1 (:tab (ask c {:command "prev-tab"})))))
    (is (seq (:agents (ask c {:command "refresh"}))))
    (is (= 1 (:refreshes @(:state c))))
    (testing "panels reports what the vessel is showing, not its whole content"
      (let [p (:panels (ask c {:command "panels"}))]
        (is (= [{:panel "olympus/tab-1" :title "Olympus  tab 1/1" :blocks 1}] p))))))
