(ns hive-olympus.transcript-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-olympus.transcript :as t]))

(defprotocol AnIndex
  "Stands in for hive-agent's ITranscriptIndex, which this library does not
   depend on. `searchable?` only ever asks `satisfies?`, so any protocol
   exercises the same decision."
  (rank [_ opts]))

(defrecord Indexed [entries] AnIndex (rank [_ _] nil))

(defn- entry
  [turn role content & {:keys [tools ts]}]
  (cond-> {:transcript/turn turn
           :transcript/role role
           :transcript/content content
           :transcript/timestamp (or ts (+ 1789900000000 (* turn 1000)))}
    tools (assoc :transcript/tool-calls (mapv (fn [n] {:tool-call/name n}) tools))))

(def ^:private conversation
  [(entry 1 :user "find the flicker")
   (entry 2 :assistant "reading the panel code" :tools ["carto search" "read-form"])
   (entry 3 :assistant "the repaint is unconditional")])

(defn- port
  "The port over a stub hive-agent. REGISTRY is an atom {agent-id store};
   OPENABLE is {[agent-id project-id] store} that `ensure!` may register, so
   a port that forgets the project cannot open anything."
  ([registry] (port registry {}))
  ([registry openable]
   (t/live-transcript-port
    (fn [sym]
      (condp = sym
        t/get-store-source (fn [id] (get @registry id))
        t/ensure-store-source (fn [{:keys [agent-id project-id]}]
                                (when-let [store (get openable [agent-id project-id])]
                                  (swap! registry assoc agent-id store)))
        t/query-source (fn [store _id] {:ok (:entries store)})
        t/since-source (fn [store {:keys [turn]}]
                         {:ok (filterv #(> (:transcript/turn %) turn) (:entries store))})
        t/search-source (fn [store {:keys [query limit]}]
                          {:ok (into []
                                     (comp (filter #(str/includes? (:transcript/content %) query))
                                           (map #(assoc % :score 0.91))
                                           (take limit))
                                     (:entries store))})
        nil)))))

(deftest a-message-is-bounded-and-says-when-it-was-cut
  (is (nil? (t/clip nil)))
  (is (nil? (t/clip "   ")))
  (is (= ["hello" 5 false] (t/clip "  hello  ")))
  (let [long-text (apply str (repeat (* 3 t/max-content) "x"))
        [text length clipped?] (t/clip long-text)]
    (is clipped?)
    (is (= (* 3 t/max-content) length) "the full length survives the clip")
    (is (= (inc t/max-content) (count text)) "the bound, plus the ellipsis that admits it")))

(deftest an-exchange-carries-tool-names-not-tool-arguments
  (let [e (t/entry->exchange (entry 2 :assistant "reading" :tools ["carto search" "read-form"]))]
    (is (= 2 (:turn e)))
    (is (= "assistant" (:role e)) "a keyword role reads as a plain word")
    (is (= ["carto search" "read-form"] (:tools e)))
    (is (= "reading" (:text e))))
  (testing "absent facts are omitted rather than carried as nil"
    (let [e (t/entry->exchange {:transcript/turn 1})]
      (is (= [:turn] (keys e)))
      (is (not (contains? e :tools)))))
  (testing "a search hit keeps its score"
    (is (= 0.5 (:score (t/entry->exchange (assoc (entry 1 :user "x") :score 0.5)))))))

(deftest an-answer-is-newest-first-and-admits-what-it-left-out
  (let [a (t/answer "vd-ops-a" conversation nil)]
    (is (= [3 2 1] (mapv :turn (:exchanges a))) "newest turn first")
    (is (= 3 (:count a)))
    (is (= 3 (:total a)))
    (is (not (:truncated? a))))
  (testing "a limit is reported, not silently applied"
    (let [a (t/answer "vd-ops-a" conversation 2)]
      (is (= [3 2] (mapv :turn (:exchanges a))))
      (is (:truncated? a))
      (is (= 3 (:total a)) "the total is what exists, not what was shown")))
  (testing "nothing recorded is a note, not an error"
    (let [a (t/answer "vd-ops-a" [] nil)]
      (is (empty? (:exchanges a)))
      (is (string? (:note a)))
      (is (not (:error a))))))

(deftest a-result-is-read-structurally-so-an-empty-ok-is-still-success
  (is (= {:entries []} (t/result->entries {:ok []})) "no entries is not a failure")
  (is (= {:entries [1 2]} (t/result->entries {:ok [1 2]})))
  (is (= {:error "boom"} (t/result->entries {:error "boom"})))
  (is (= {:entries [1]} (t/result->entries [1])))
  (is (:error (t/result->entries nil))))

(deftest search-is-refused-only-when-the-store-is-known-to-lack-an-index
  (is (t/searchable? nil {:any :store})
      "with hive-agent absent the question cannot be asked, so it is not a refusal")
  (is (t/searchable? AnIndex (->Indexed [])))
  (is (not (t/searchable? AnIndex {:plain :map}))))

(deftest the-port-answers-one-agents-conversation
  (let [registry (atom {"vd-ops-a" {:entries conversation}})
        {:keys [conversation]} (port registry)
        a (conversation {:agent-id "vd-ops-a"})]
    (is (= "vd-ops-a" (:agent-id a)))
    (is (= [3 2 1] (mapv :turn (:exchanges a))))
    (is (not (:error a))))
  (testing "a turn cursor follows a live agent"
    (let [registry (atom {"vd-ops-a" {:entries conversation}})
          {:keys [conversation]} (port registry)
          a (conversation {:agent-id "vd-ops-a" :since-turn 1})]
      (is (= [3 2] (mapv :turn (:exchanges a))))))
  (testing "an unnamed agent is refused before any store is touched"
    (is (:error ((:conversation (port (atom {}))) {})))))

(deftest a-finished-ling-is-reopened-under-its-own-project
  ;; The store lives at <root>/<project-id>/<agent-id>. An observer asking
  ;; about an exited ling has only the roster's Agent, so the project it
  ;; carries is the whole join -- forget it and the answer is an empty
  ;; conversation from the "global" partition rather than the real one.
  (let [registry (atom {})
        openable {["w2-story" "vtranslate"] {:entries conversation}}
        {:keys [conversation]} (port registry openable)]
    (testing "with the project, the exited agent is readable again"
      (let [a (conversation {:agent-id "w2-story" :project-id "vtranslate"})]
        (is (= 3 (:count a)))
        (is (contains? @registry "w2-story") "the reopened store is registered once")))
    (testing "without it, there is no store and the answer says so"
      (let [a ((:conversation (port (atom {}) openable)) {:agent-id "w2-story"})]
        (is (:error a))
        (is (str/includes? (:error a) "w2-story"))))))

(deftest the-port-ranks-a-transcript-against-a-query
  (let [registry (atom {"vd-ops-a" {:entries conversation}})
        {:keys [search]} (port registry)]
    (let [a (search {:agent-id "vd-ops-a" :query "panel"})]
      (is (= "panel" (:query a)))
      (is (= 1 (:count a)))
      (is (= 0.91 (:score (first (:exchanges a))))))
    (testing "a search with nothing to search for is refused"
      (is (:error (search {:agent-id "vd-ops-a"})))
      (is (:error (search {:agent-id "vd-ops-a" :query "  "}))))))

(deftest the-port-degrades-instead-of-throwing
  (testing "hive-agent absent: an error answer, not an exception"
    (let [p (t/live-transcript-port (constantly nil))]
      (is (:error ((:conversation p) {:agent-id "vd-ops-a"})))
      (is (:error ((:search p) {:agent-id "vd-ops-a" :query "x"})))))
  (testing "a store that throws is reported, not propagated"
    (let [p (t/live-transcript-port
             (fn [sym]
               (condp = sym
                 t/get-store-source (fn [_] {:entries []})
                 t/query-source (fn [_ _] (throw (ex-info "store is closed" {})))
                 nil)))
          a ((:conversation p) {:agent-id "vd-ops-a"})]
      (is (= "store is closed" (:error a))))))
