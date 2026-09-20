(ns hive-olympus.schema
  "Malli value objects for Olympus: agents, the canonical layout, positions,
   the navigation state, the grid model, and the two vessel ops the view emits.

   The op schemas restate the hive-vessel primitive SHAPE (:ui/show-panel,
   :ui/close-panel) without depending on hive-vessel; the test suite checks
   them against hive-vessel's own schemas.")

(def Status
  [:enum :idle :working :blocked :error :spawning])

(def AgentId
  [:string {:min 1}])

(def Kind
  [:enum :ling :drone])

(def Agent
  "An observed swarm member. Only id, name and status are required; every
   other key is what the roster source could tell, and the view shows only
   what is present. :agent/exited? marks an agent that already left the swarm
   and is still shown with how it ended. :agent/activity is the latest thing
   it said; :agent/recent is its recent log, newest first, shown only in the
   focus zoom so the grid cells stay one screen."
  [:map
   [:agent/id AgentId]
   [:agent/name :string]
   [:agent/status Status]
   [:agent/task {:optional true} [:maybe :string]]
   [:agent/kind {:optional true} Kind]
   [:agent/parent {:optional true} AgentId]
   [:agent/model {:optional true} :string]
   [:agent/provider {:optional true} :string]
   [:agent/mode {:optional true} :string]
   [:agent/project {:optional true} :string]
   [:agent/activity {:optional true} :string]
   [:agent/recent {:optional true} [:vector :string]]
   [:agent/seen {:optional true} :string]
   [:agent/done {:optional true} nat-int?]
   [:agent/drones {:optional true} nat-int?]
   [:agent/exited? {:optional true} :boolean]])

(def Roster
  [:vector {:gen/max 12} Agent])

(def Cell
  [:tuple nat-int? nat-int?])

(def GridLayout
  [:map
   [:rows nat-int?]
   [:cols nat-int?]
   [:empty-cells {:optional true} [:set Cell]]])

(def TabbedLayout
  [:map
   [:tabs pos-int?]
   [:per-tab pos-int?]])

(def Layout
  [:or TabbedLayout GridLayout])

(def Position
  [:map
   [:row nat-int?]
   [:col nat-int?]
   [:tab [:maybe nat-int?]]])

(def Positions
  [:map-of AgentId Position])

(def OlympusState
  [:map
   [:active-tab nat-int?]
   [:focus [:maybe AgentId]]])

(def GridCell
  [:map
   [:cell/row nat-int?]
   [:cell/col nat-int?]
   [:cell/agent Agent]
   [:cell/focused? :boolean]])

(def Tab
  [:map
   [:tab/index nat-int?]
   [:tab/active? :boolean]
   [:tab/cells [:vector {:gen/max 4} GridCell]]])

(def Counts
  [:map
   [:total nat-int?]
   [:working nat-int?]
   [:blocked nat-int?]
   [:error nat-int?]
   [:idle nat-int?]
   [:spawning nat-int?]])

(def GridModel
  [:map
   [:grid/layout Layout]
   [:grid/tabs [:vector {:min 1 :gen/max 3} Tab]]
   [:grid/active-tab nat-int?]
   [:grid/focus [:maybe AgentId]]
   [:grid/counts Counts]
   [:grid/routes {:optional true} [:vector [:tuple :string pos-int?]]]])

(def Tone
  [:enum :plain :muted :info :success :warn :error])

(def Block
  "The hive-vessel block vocabulary, restated: every block a lens or the
   operator room emits must be one a vessel can paint."
  [:multi {:dispatch :block/type}
   [:heading [:map [:block/type [:= :heading]] [:text :string]
              [:level {:optional true} [:int {:min 1 :max 3}]]]]
   [:para [:map [:block/type [:= :para]] [:text :string] [:tone {:optional true} Tone]]]
   [:fields [:map [:block/type [:= :fields]] [:fields [:vector [:tuple :string :string]]]]]
   [:list [:map [:block/type [:= :list]] [:items [:vector :string]]]]
   [:code [:map [:block/type [:= :code]] [:text :string] [:lang {:optional true} :string]]]
   [:diff [:map [:block/type [:= :diff]]
           [:text {:optional true} :string]
           [:lines {:optional true} [:vector [:map
                                              [:line/kind [:enum :context :added :removed :hunk]]
                                              [:line/text :string]]]]]]
   [:link [:map [:block/type [:= :link]] [:text :string] [:file [:string {:min 1}]]
           [:line {:optional true} [:int {:min 1}]]]]])

(def Doc
  [:map
   [:doc/title :string]
   [:doc/blocks [:vector Block]]])

(def PanelId
  "A grid tab, the focus zoom, or the operator room."
  [:re {:gen/schema [:int {:min 1 :max 99}]
        :gen/fmap #(str "olympus/tab-" %)}
   #"^olympus/(tab-[1-9][0-9]*|focus|operator)$"])

(def ShowPanel
  [:map
   [:op [:= :ui/show-panel]]
   [:panel/id PanelId]
   [:doc Doc]])

(def ClosePanel
  [:map
   [:op [:= :ui/close-panel]]
   [:panel/id PanelId]])

(def Op
  [:multi {:dispatch :op}
   [:ui/show-panel ShowPanel]
   [:ui/close-panel ClosePanel]])

(def Ops
  [:vector Op])

(def Panels
  [:vector {:min 1} ShowPanel])

(def LensId
  [:or :keyword [:string {:min 1}]])

(def LensStatus
  "What a lens answered for the focused agent: a document, nothing, or a
   failure."
  [:enum :ok :empty :error])

(def LensSection
  [:map
   [:lens/id LensId]
   [:lens/status LensStatus]
   [:doc {:optional true} Doc]
   [:error {:optional true} :string]])

(def LensSections
  [:vector {:gen/max 4} LensSection])
