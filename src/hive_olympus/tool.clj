(ns hive-olympus.tool
  "The observer's surface: one `olympus` tool over the running core.

   Olympus already paints the grid into whatever vessel is mounted. This is
   how a caller ASKS instead of watching: which agents exist, what one of
   them has actually been doing, and which one the panels should zoom into.

   Everything here reads the core's own state. The only writes are to the
   observer's viewport -- which agent is focused, which tab is shown -- and
   never to an agent. Least privilege: an observer observes."
  (:require [clojure.string :as str]
            [hive-olympus.model :as model]))

(def default-activity-limit
  "How many log lines `activity` answers when the caller names no limit."
  20)

(defn- present
  "V when it is a non-blank string, else nil -- nil, not false, so `some->`
   short-circuits on it."
  [v]
  (when (and (string? v) (not (str/blank? v))) v))

(defn agent-row
  "AGENT as a flat row for a tool caller: every fact the roster could tell,
   absent facts omitted rather than carried as nil. The recent log is
   summarized by length here; `activity` is what answers it in full."
  [{:agent/keys [id name status kind mode project parent task activity seen
                 done drones exited? recent] :as agent}]
  (cond-> {:id id :name name :status status}
    kind (assoc :kind kind)
    exited? (assoc :exited? true)
    (model/route agent) (assoc :route (model/route agent))
    (present mode) (assoc :mode mode)
    (present project) (assoc :project project)
    (present parent) (assoc :ling parent)
    drones (assoc :drones drones)
    (and done (pos? done)) (assoc :done done)
    (present task) (assoc :task task)
    (present activity) (assoc :activity activity)
    (present seen) (assoc :seen seen)
    (seq recent) (assoc :activity-lines (count recent))))

(defn find-agent
  "The agent of ROSTER the caller means by NEEDLE: an exact id, then an exact
   name, then a unique case-insensitive substring of either. nil when nothing
   matches; the ambiguous case answers nil rather than guessing."
  [roster needle]
  (when-let [needle (present needle)]
    (let [lower (str/lower-case needle)
          exact (or (first (filter #(= needle (:agent/id %)) roster))
                    (first (filter #(= needle (:agent/name %)) roster)))
          loose (filter (fn [{:agent/keys [id name]}]
                          (or (str/includes? (str/lower-case (str id)) lower)
                              (str/includes? (str/lower-case (str name)) lower)))
                        roster)]
      (or exact (when (= 1 (count loose)) (first loose))))))

(defn- resolve-agent
  "Either {:agent a} or an {:error ...} explaining what the caller could have
   said instead."
  [roster needle]
  (cond
    (not (present needle))
    {:error "name an agent: :agent \"<id or name>\". Run command=\"agents\" to see them."}

    (empty? roster)
    {:error "no agents in the roster right now"}

    :else
    (if-let [a (find-agent roster needle)]
      {:agent a}
      {:error (str "no single agent matches " (pr-str needle))
       :agents (mapv :agent/id roster)})))

(defn agents-answer
  "The whole roster: the header counts, then a row per agent. STATUS, when
   given, keeps only agents in that status."
  [{:keys [roster model]} status]
  (let [wanted (some-> (present status) str/lower-case keyword)
        rows (cond->> roster
               wanted (filter #(= wanted (:agent/status %))))]
    (cond-> {:counts (:grid/counts model)
             :tabs (count (:grid/tabs model))
             :focus (:grid/focus model)
             :agents (mapv agent-row rows)}
      wanted (assoc :filtered-to wanted))))

(defn activity-answer
  "What AGENT has been doing: its row, then its recent log newest first, at
   most LIMIT lines. Read-only -- it never moves the focus."
  [agent limit]
  (let [limit (or limit default-activity-limit)
        recent (:agent/recent agent [])]
    (cond-> (assoc (agent-row agent) :recent (vec (take limit recent)))
      (> (count recent) limit) (assoc :truncated? true :recent-total (count recent))
      (empty? recent) (assoc :note "the roster source reported no shouts for this agent"))))

(defn focus-answer
  "The state after focusing: which agent the panels now zoom into, the tab it
   lives on, and its activity, so one call both moves the view and answers."
  [{:keys [model lens-sections]} agent]
  {:focused (:agent/id agent)
   :panel "olympus/focus"
   :tab (inc (or (:grid/tab model) 0))
   :agent (activity-answer agent default-activity-limit)
   :lenses (mapv (fn [s] {:lens (:lens/id s) :status (:lens/status s)})
                 (or lens-sections []))})

(defn transcript-params
  "Port params for AGENT, merged over EXTRA: its id, and the project that
   partitions its transcript store, which is what makes a ling that has
   already finished findable on disk."
  [agent extra]
  (cond-> (assoc extra :agent-id (:agent/id agent))
    (present (:agent/project agent)) (assoc :project-id (:agent/project agent))))

(defn answer
  "Run COMMAND against the core. STATE is the core's state atom and NAV the
   viewport operations {:focus! :next-tab! :prev-tab! :refresh! :transcript!
   :search! :close-transcript!}. Returns the answer map, or {:error ...}."
  [state nav {:keys [command agent status limit query role]}]
  (let [snapshot #(deref state)
        with-agent (fn [f]
                     (let [{:keys [agent] :as r} (resolve-agent (:roster (snapshot)) agent)]
                       (if agent (f agent) r)))]
    (case command
      "agents" (agents-answer (snapshot) status)

      "activity" (with-agent #(activity-answer % limit))

      "watch" (with-agent (fn [a]
                            ((:focus! nav) (:agent/id a))
                            (focus-answer (snapshot) a)))

      "unwatch" (do ((:focus! nav) nil)
                    ((:close-transcript! nav))
                    {:focused nil :panel "olympus/focus" :closed? true})

      "transcript" (with-agent
                     (fn [a]
                       (-> ((:transcript! nav) (transcript-params a {:limit limit}))
                           (assoc :agent (agent-row a) :panel "olympus/transcript"))))

      "search" (with-agent
                 (fn [a]
                   (-> ((:search! nav) (transcript-params a {:limit limit :query query :role role}))
                       (assoc :agent (agent-row a) :panel "olympus/transcript"))))

      "close-transcript" (do ((:close-transcript! nav))
                             {:panel "olympus/transcript" :closed? true})

      "next-tab" (let [st ((:next-tab! nav))]
                   {:tab (inc (or (:tab st) 0)) :tabs (count (:grid/tabs (:model (snapshot))))})

      "prev-tab" (let [st ((:prev-tab! nav))]
                   {:tab (inc (or (:tab st) 0)) :tabs (count (:grid/tabs (:model (snapshot))))})

      "refresh" (do ((:refresh! nav))
                    (agents-answer (snapshot) nil))

      "panels" {:panels (mapv (fn [p] {:panel (:panel/id p) :title (get-in p [:doc :doc/title])
                                       :blocks (count (get-in p [:doc :doc/blocks]))})
                              (:panels (snapshot)))}

      {:error (str "unknown command: " (pr-str command))
       :commands ["agents" "activity" "transcript" "search" "watch" "unwatch"
                  "close-transcript" "next-tab" "prev-tab" "refresh" "panels"]})))

(defn tool
  "The `olympus` tool-def over the core's STATE atom and NAV viewport ops."
  [state nav]
  {:name "olympus"
   :description
   (str "Observe the agent swarm Olympus is painting. "
        "agents: every agent with status, route, task and last activity (status=working|idle|blocked|error narrows). "
        "activity: one agent's recent shouts, newest first. "
        "transcript: what one subagent actually exchanged with its model, turn by turn, tool calls included -- "
        "it works for a ling that has already finished. "
        "search: rank one agent's transcript against a natural-language query. "
        "transcript and search also paint the olympus/transcript panel, so the answer is on screen as well as here. "
        "watch: zoom the panels into one agent and answer its activity. unwatch: close the zoom. "
        "close-transcript: close the transcript panel. "
        "next-tab/prev-tab: move the grid. refresh: re-poll now. panels: what the vessel is currently showing. "
        "Read-only: it moves your own view, never an agent.")
   :inputSchema
   {:type "object"
    :properties {"command" {:type "string"
                            :enum ["agents" "activity" "transcript" "search" "watch" "unwatch"
                                   "close-transcript" "next-tab" "prev-tab" "refresh" "panels"]}
                 "agent" {:type "string"
                          :description "[activity|transcript|search|watch] agent id or name; a unique substring is enough"}
                 "query" {:type "string"
                          :description "[search] what to look for in the transcript"}
                 "role" {:type "string"
                         :description "[search] keep only this speaker: user, assistant, tool or system"}
                 "status" {:type "string"
                           :description "[agents] keep only this status"}
                 "limit" {:type "integer"
                          :description "[activity|transcript|search] how many entries (default 20 shouts, 40 exchanges)"}}
    :required ["command"]}
   :handler
   (fn [params]
     (let [get* (fn [k] (or (get params (name k)) (get params k)))
           result (try
                    (answer state nav {:command (some-> (get* :command) str)
                                       :agent (get* :agent)
                                       :status (get* :status)
                                       :query (get* :query)
                                       :role (get* :role)
                                       :limit (some-> (get* :limit) long)})
                    (catch Throwable t
                      {:error (or (ex-message t) (str t))}))]
       (cond-> {:content [{:type "text" :text (pr-str result)}]}
         (:error result) (assoc :isError true))))})
