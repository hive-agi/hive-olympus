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

(def Agent
  [:map
   [:agent/id AgentId]
   [:agent/name :string]
   [:agent/status Status]
   [:agent/task {:optional true} [:maybe :string]]])

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
   [:grid/counts Counts]])

(def Tone
  [:enum :plain :muted :info :success :warn :error])

(def Block
  [:multi {:dispatch :block/type}
   [:heading [:map [:block/type [:= :heading]] [:text :string]
              [:level {:optional true} [:int {:min 1 :max 3}]]]]
   [:para [:map [:block/type [:= :para]] [:text :string] [:tone {:optional true} Tone]]]
   [:fields [:map [:block/type [:= :fields]] [:fields [:vector [:tuple :string :string]]]]]])

(def Doc
  [:map
   [:doc/title :string]
   [:doc/blocks [:vector Block]]])

(def PanelId
  [:re {:gen/schema [:int {:min 1 :max 99}]
        :gen/fmap #(str "olympus/tab-" %)}
   #"^olympus/tab-[1-9][0-9]*$"])

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
