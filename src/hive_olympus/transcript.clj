(ns hive-olympus.transcript
  "The transcript port: what a subagent actually exchanged with its model.

   The roster says that an agent is working and what it last shouted. It
   cannot say what was said. hive-agent persists every turn per agent and
   indexes it for vector search; this is the adapter over that store,
   resolved lazily at call time, so Olympus keeps no compile-time dependency
   on hive-agent and answers an empty log while it is absent.

   Pure projection (`entry->exchange`, `answer`) is separated from the
   boundary (`store-of`, `live-transcript-port`) so the rendering and the
   tool can be tested against a stub store with no agent in the loop."
  (:require [clojure.string :as str]))

(def get-store-source
  "Qualified symbol of hive-agent's live store lookup."
  'hive-agent.loop.transcript.handlers/get-store)

(def ensure-store-source
  "Qualified symbol of hive-agent's store opener. An agent's store is
   deregistered when its session ends, so reopening it from disk is what
   keeps a finished ling readable."
  'hive-agent.loop.transcript.handlers/handle-transcript-ensure-store!)

(def query-source
  "Qualified symbol of the ITranscriptStore method answering every entry."
  'hive-agent.loop.transcript.store/query-by-agent)

(def since-source
  "Qualified symbol of the ITranscriptStore method answering a turn cursor."
  'hive-agent.loop.transcript.store/transcript-since)

(def search-source
  "Qualified symbol of the ITranscriptIndex method ranking entries."
  'hive-agent.loop.transcript.store/semantic-search)

(def index-source
  "Qualified symbol of the semantic-search protocol. A structural-only store
   does not satisfy it, which is a different answer from having no store."
  'hive-agent.loop.transcript.store/ITranscriptIndex)

(def default-limit
  "How many exchanges an answer carries when the caller names no limit."
  40)

(def max-content
  "Longest message body an exchange carries. The full length travels beside
   it, so a clipped message is visibly clipped."
  800)

(defn clip
  "S as one bounded line-preserving string, or nil when it says nothing.
   Returns [text full-length clipped?]."
  [s]
  (let [s (some-> s str)]
    (when-not (str/blank? s)
      (let [trimmed (str/trim s)
            n (count trimmed)]
        (if (> n max-content)
          [(str (subs trimmed 0 max-content) "…") n true]
          [trimmed n false])))))

(defn tool-call-names
  "The names of TOOL-CALLS, in order, without their arguments. The arguments
   are what makes a transcript unreadable at a glance; the names are what an
   observer scans for."
  [tool-calls]
  (into []
        (comp (map #(or (:tool-call/name %) (:name %)))
              (map #(some-> % str))
              (remove str/blank?))
        tool-calls))

(defn entry->exchange
  "One stored transcript entry as a flat exchange map. Absent facts are
   omitted rather than carried as nil."
  [entry]
  (let [{:transcript/keys [turn role content timestamp cost-usd tool-calls]} entry
        [text length clipped?] (clip content)
        tools (tool-call-names tool-calls)]
    (cond-> {}
      turn (assoc :turn turn)
      role (assoc :role (if (keyword? role) (name role) (str role)))
      timestamp (assoc :timestamp timestamp)
      text (assoc :text text)
      clipped? (assoc :clipped? true :length length)
      (seq tools) (assoc :tools tools)
      (and cost-usd (pos? (double cost-usd))) (assoc :cost-usd cost-usd)
      (:score entry) (assoc :score (:score entry)))))

(defn result->entries
  "The entries of a hive-agent Result R, as {:entries [...]} or {:error ...}.
   Reads the Result shape structurally rather than depending on the library
   that builds it: an :ok key is success even when it holds no entries."
  [r]
  (cond
    (and (map? r) (contains? r :ok)) {:entries (vec (:ok r))}
    (and (map? r) (:error r)) {:error (str (:error r))}
    (sequential? r) {:entries (vec r)}
    :else {:error "the transcript store answered nothing"}))

(defn searchable?
  "Whether STORE answers semantic search. INDEX is the resolved protocol, or
   nil when hive-agent is absent -- the question cannot be asked then, so the
   store is given the benefit of the doubt and any failure surfaces as an
   error answer instead of a refusal."
  [index store]
  (or (nil? index) (satisfies? index store)))

(defn answer
  "ENTRIES as an exchange answer for AGENT-ID: newest turn first, at most
   LIMIT of them, each projected by `entry->exchange`."
  [agent-id entries limit]
  (let [limit (max 1 (long (or limit default-limit)))
        all (vec entries)
        shown (->> all
                   (sort-by #(or (:transcript/turn %) 0) >)
                   (take limit)
                   (mapv entry->exchange))]
    (cond-> {:agent-id agent-id
             :count (count shown)
             :total (count all)
             :exchanges shown}
      (> (count all) (count shown)) (assoc :truncated? true)
      (empty? all) (assoc :note "no transcript recorded for this agent"))))

(defn- resolver
  "RESOLVE or a guarded `requiring-resolve`, which answers nil rather than
   throwing while hive-agent is absent."
  [resolve]
  (or resolve (fn [sym] (try (requiring-resolve sym) (catch Throwable _ nil)))))

(defn store-of
  "The transcript store for AGENT-ID, reopening it under PROJECT-ID when the
   live registry no longer holds one. nil when hive-agent is absent or has
   nothing for this agent."
  [resolve agent-id project-id]
  (when-let [get-store (resolve get-store-source)]
    (or (get-store agent-id)
        (when-let [ensure! (resolve ensure-store-source)]
          (ensure! (cond-> {:agent-id agent-id}
                     (not (str/blank? (str project-id))) (assoc :project-id project-id)))
          (get-store agent-id)))))

(defn live-transcript-port
  "The transcript port over hive-agent's store. RESOLVE is (fn [sym] -> var
   or nil), default a guarded `requiring-resolve`.

   Answers {:conversation f :search f}, each (fn [params] -> answer map),
   where params carry :agent-id, :project-id, :limit, and for a search a
   :query. Every failure is an {:error ...} answer: an observer's question
   never throws into the vessel."
  ([] (live-transcript-port nil))
  ([resolve]
   (let [resolve (resolver resolve)
         missing {:error (str "transcript source " get-store-source " unavailable")}
         with-store
         (fn [{:keys [agent-id project-id]} f]
           (if (str/blank? (str agent-id))
             {:error "name an agent"}
             (if-let [store (store-of resolve agent-id project-id)]
               (try (f store) (catch Throwable t {:error (or (ex-message t) (str t))}))
               (if (resolve get-store-source)
                 {:error (str "no transcript store for " agent-id)
                  :agent-id agent-id}
                 missing))))]
     {:conversation
      (fn [{:keys [agent-id limit since-turn] :as params}]
        (with-store
          params
          (fn [store]
            (let [r (if since-turn
                      (when-let [since (resolve since-source)]
                        (since store {:turn (long since-turn)}))
                      (when-let [query (resolve query-source)]
                        (query store agent-id)))
                  {:keys [entries error]} (result->entries r)]
              (if error
                {:error error :agent-id agent-id}
                (answer agent-id entries limit))))))

      :search
      (fn [{:keys [agent-id query limit role] :as params}]
        (if (str/blank? (str query))
          {:error "name what to search for: :query \"...\""}
          (with-store
            params
            (fn [store]
              (let [index (some-> (resolve index-source) deref)
                    search (resolve search-source)]
                (cond
                  (not search) missing

                  (not (searchable? index store))
                  {:error (str "the store for " agent-id
                               " keeps no search index; ask for its conversation instead")
                   :agent-id agent-id}

                  :else
                  (let [opts (cond-> {:query query
                                      :agent-id agent-id
                                      :limit (max 1 (long (or limit 20)))}
                               (not (str/blank? (str role))) (assoc :role (keyword role)))
                        {:keys [entries error]} (result->entries (search store opts))]
                    (if error
                      {:error error :agent-id agent-id}
                      (assoc (answer agent-id entries limit) :query query)))))))))})))
