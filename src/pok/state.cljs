(ns pok.state
  "Re-frame state management for PoK blockchain application.
   Manages nodes, chains, mempools, and consensus state with immutable updates."
  (:require [re-frame.core :as rf]
            [clojure.core.async :as async :refer [go <!]]
            [pok.reputation :as rep]
            [pok.consensus :as consensus]
            [pok.curriculum :as curriculum]
            [pok.renderer :as renderer]))

;; Schema definitions based on app_state.json and foundational architecture

(def node-archetype-defaults
  "Default accuracy and participation rates by archetype from final_simulation.rkt"
  {:aces {:accuracy 0.95 :participation 0.90}
   :diligent {:accuracy 0.80 :participation 0.85}
   :strugglers {:accuracy 0.60 :participation 0.70}
   :guessers {:accuracy 0.30 :participation 0.95}})

(def default-node
  "Default node structure with initial values"
  {:pubkey ""
   :archetype :diligent
   :mempool []
   :chain []
   :progress 0
   :reputation 1.0
   :consensus-history {}
   :created-at (js/Date.now)})

;; Default database structure

(rf/reg-event-db
  ::initialize-db
  (fn [_ _]
    {:nodes {}                    ; pubkey -> node-map
     :current-user nil            ; current user pubkey
     :curriculum-index nil        ; curriculum structure index
     :loaded-lessons {}           ; lesson-key -> lesson-data cache
     :active-question nil         ; currently displayed question
     :current-lesson nil          ; currently loaded lesson
     :chart-specs {}              ; question-id -> vega-lite spec cache
     :network-stats {}            ; consensus health metrics
     :sync-history []             ; QR sync operations
     :sync {:current-delta nil    ; current delta for QR generation
            :peer-timestamps {}}   ; last known timestamps from peers
     :ui {:loading? false
          :error nil
          :modal nil
          :lesson-loading? false
          :chart-loading? false}}))

;; Node management events

(rf/reg-event-db
  ::add-node
  (fn [db [_ pubkey archetype provisional-reputation]]
    (let [existing-nodes (:nodes db)
          reputation (or provisional-reputation
                         (rep/get-median-reputation existing-nodes)
                         1.0)
          new-node (merge default-node
                          {:pubkey pubkey
                           :archetype archetype
                           :reputation reputation})]
      (assoc-in db [:nodes pubkey] new-node))))

(rf/reg-event-db
  ::set-current-user
  (fn [db [_ pubkey]]
    (assoc db :current-user pubkey)))

(rf/reg-event-db
  ::update-node-reputation
  (fn [db [_ pubkey new-reputation]]
    (assoc-in db [:nodes pubkey :reputation] new-reputation)))

(rf/reg-event-db
  ::update-node-progress
  (fn [db [_ pubkey new-progress]]
    (assoc-in db [:nodes pubkey :progress] new-progress)))

;; Transaction and mempool events

(rf/reg-event-db
  ::create-transaction
  (fn [db [_ {:keys [question-id pubkey answer type]}]]
    (let [timestamp (js/Date.now)
          hash (str (hash answer)) ; Simple hash for now, replace with crypto-js
          txn-id (str timestamp "-" (subs pubkey 0 5) "-" type)
          transaction {:id txn-id
                       :timestamp timestamp
                       :owner-pubkey pubkey
                       :question-id question-id
                       :type type
                       :payload {:answer answer
                                 :hash hash}}]
      ;; Add to node's mempool
      (update-in db [:nodes pubkey :mempool] conj transaction))))

(rf/reg-event-db
  ::submit-answer
  (fn [db [_ question-id answer]]
    (let [current-user (:current-user db)]
      (if current-user
        ;; Create completion transaction
        (let [updated-db (rf/dispatch-sync [::create-transaction
                                             {:question-id question-id
                                              :pubkey current-user
                                              :answer answer
                                              :type "completion"}])]
          ;; Update progress
          (rf/dispatch [::update-node-progress current-user
                        (inc (get-in db [:nodes current-user :progress] 0))])
          updated-db)
        db))))

(rf/reg-event-db
  ::submit-attestation
  (fn [db [_ question-id answer]]
    (let [current-user (:current-user db)]
      (if current-user
        (rf/dispatch-sync [::create-transaction
                           {:question-id question-id
                            :pubkey current-user
                            :answer answer
                            :type "attestation"}])
        db))))

;; Blockchain events

(rf/reg-event-db
  ::create-block
  (fn [db [_ {:keys [proposer-pubkey transactions block-type]}]]
    (let [node (get-in db [:nodes proposer-pubkey])
          block-hash (str (count (:chain node)) "-" block-type "-block")
          new-block {:hash block-hash
                     :txns transactions
                     :type block-type
                     :proposer proposer-pubkey
                     :timestamp (js/Date.now)}
          ;; Remove mined transactions from mempool
          mined-txn-ids (set (map :id transactions))
          updated-mempool (remove #(contains? mined-txn-ids (:id %)) (:mempool node))]
      (-> db
          (update-in [:nodes proposer-pubkey :chain] conj new-block)
          (assoc-in [:nodes proposer-pubkey :mempool] updated-mempool)))))

(rf/reg-event-fx
  ::propose-pok-block
  (fn [{:keys [db]} [_ pubkey]]
    (let [node (get-in db [:nodes pubkey])
          all-transactions (concat (:mempool node)
                                   (mapcat :txns (:chain node)))
          completion-txns (filter #(and (= (:type %) "completion")
                                        (= (:owner-pubkey %) pubkey))
                                  (:mempool node))
          curriculum-size (count (:curriculum db))
          active-nodes (count (:nodes db))
          ;; Check each completion for consensus readiness
          minable-completions
          (filter (fn [completion]
                    (let [validation (consensus/validate-pok-consensus
                                       completion all-transactions
                                       active-nodes curriculum-size)]
                      (:ready? validation)))
                  completion-txns)]
      
      (if (seq minable-completions)
        ;; Create PoK block and trigger reputation updates
        (let [minable-qids (set (map :question-id minable-completions))
              related-attestations (filter #(and (= (:type %) "attestation")
                                                 (contains? minable-qids (:question-id %)))
                                           (:mempool node))
              block-transactions (concat minable-completions related-attestations)]
          {:db db
           :dispatch [::create-block {:proposer-pubkey pubkey
                                      :transactions block-transactions
                                      :block-type "pok"}]
           :fx [[:dispatch [::process-reputation-updates minable-completions]]]})
        {:db db}))))

(rf/reg-event-fx
  ::process-reputation-updates
  (fn [{:keys [db]} [_ mined-completions]]
    (let [nodes (:nodes db)]
      ;; Process reputation updates for each mined completion
      (go
        (doseq [completion mined-completions]
          (let [question-id (:question-id completion)
                final-hash (get-in completion [:payload :hash])
                all-transactions (mapcat #(concat (:mempool %)
                                                  (mapcat :txns (:chain %)))
                                         (vals nodes))
                attestations (consensus/get-attestations-for-question
                               all-transactions question-id)]
            ;; Process reputation rewards asynchronously
            (rep/process-attestation-rewards attestations final-hash nodes))))
      {:db db})))

;; Phase 3: QR Synchronization events

(rf/reg-event-fx
  ::start-qr-scan
  (fn [{:keys [db]} [_ video-element canvas-element expected-merkle-root]]
    (let [scan-id (str "scan-" (js/Date.now))]
      {:db (-> db
               (assoc-in [:ui :qr-scanning?] true)
               (assoc-in [:ui :scan-id] scan-id)
               (assoc-in [:ui :scan-progress] {:type :initializing}))
       :fx [[:dispatch [::perform-qr-scan-async video-element canvas-element expected-merkle-root scan-id]]]})))

(rf/reg-event-fx
  ::perform-qr-scan-async
  (fn [{:keys [db]} [_ video-element canvas-element expected-merkle-root scan-id]]
    ;; This would integrate with pok.qr namespace
    (go
      (let [qr-ns (-> (js/require "pok.qr") .-qr) ; Require QR namespace
            scan-result (<! (.scan-qr-delta qr-ns video-element canvas-element expected-merkle-root))]
        (rf/dispatch [::qr-scan-completed scan-id scan-result])))
    {:db db}))

(rf/reg-event-fx
  ::qr-scan-completed
  (fn [{:keys [db]} [_ _scan-id scan-result]]
    (if (:success scan-result)
      {:db (-> db
               (assoc-in [:ui :qr-scanning?] false)
               (assoc-in [:ui :scan-progress] {:type :completed}))
       :fx [[:dispatch [::process-scanned-delta (:delta scan-result)]]]}
      {:db (-> db
               (assoc-in [:ui :qr-scanning?] false)
               (assoc-in [:ui :error] (str "QR scan failed: " (:error scan-result))))})))

(rf/reg-event-fx
  ::process-scanned-delta
  (fn [{:keys [db]} [_ delta-payload]]
    {:db (assoc-in db [:ui :processing-delta?] true)
     :fx [[:dispatch [::merge-delta-async delta-payload]]]}))

(rf/reg-event-fx
  ::merge-delta-async
  (fn [{:keys [db]} [_ delta-payload]]
    (go
      (let [delta-ns (-> (js/require "pok.delta") .-delta) ; Require delta namespace
            merge-result (<! (.merge-peer-delta delta-ns db delta-payload))]
        (rf/dispatch [::delta-merge-completed merge-result])))
    {:db db}))

(rf/reg-event-db
  ::delta-merge-completed
  (fn [db [_ merge-result]]
    (if (:success merge-result)
      (-> (:state merge-result) ; Use the merged state
          (assoc-in [:ui :processing-delta?] false)
          (assoc-in [:ui :last-sync] {:timestamp (js/Date.now)
                                      :status :success
                                      :source :qr-scan}))
      (-> db
          (assoc-in [:ui :processing-delta?] false)
          (assoc-in [:ui :error] (str "Delta merge failed: " (:error merge-result)))))))

(rf/reg-event-fx
  ::create-sync-delta
  (fn [{:keys [db]} [_ peer-timestamp]]
    {:db (assoc-in db [:ui :creating-delta?] true)
     :fx [[:dispatch [::create-delta-async peer-timestamp]]]}))

(rf/reg-event-fx
  ::create-delta-async
  (fn [{:keys [db]} [_ peer-timestamp]]
    (go
      (let [delta-ns (-> (js/require "pok.delta") .-delta) ; Require delta namespace
            delta-result (<! (.create-sync-delta delta-ns db peer-timestamp))]
        (rf/dispatch [::sync-delta-created delta-result])))
    {:db db}))

(rf/reg-event-db
  ::sync-delta-created
  (fn [db [_ delta-result]]
    (-> db
        (assoc-in [:ui :creating-delta?] false)
        (assoc-in [:sync :current-delta] delta-result)
        (assoc-in [:ui :delta-ready?] (:success delta-result)))))

(rf/reg-event-db
  ::cancel-qr-scan
  (fn [db _]
    (-> db
        (assoc-in [:ui :qr-scanning?] false)
        (assoc-in [:ui :scan-progress] nil))))

(rf/reg-event-db
  ::update-scan-progress
  (fn [db [_ progress-data]]
    (assoc-in db [:ui :scan-progress] progress-data)))

;; Legacy sync events (maintained for backward compatibility)

(rf/reg-event-db
  ::sync-with-peer
  (fn [db [_ local-pubkey peer-data]]
    (let [local-node (get-in db [:nodes local-pubkey])
          peer-chain (:chain peer-data)
          peer-mempool (:mempool peer-data)
          ;; Implement longest chain rule and mempool merge
          updated-node
          (cond
            ;; Peer has longer chain - adopt it
            (> (count peer-chain) (count (:chain local-node)))
            (assoc local-node :chain peer-chain)
            
            ;; Equal chains - keep local
            (= (count peer-chain) (count (:chain local-node)))
            local-node
            
            ;; Local chain is longer - keep it
            :else
            local-node)
          
          ;; Merge mempools (deduplicate by transaction ID)
          existing-txn-ids (set (map :id (:mempool updated-node)))
          new-txns (remove #(contains? existing-txn-ids (:id %)) peer-mempool)
          merged-mempool (concat (:mempool updated-node) new-txns)]
      
      (assoc-in db [:nodes local-pubkey] (assoc updated-node :mempool merged-mempool)))))

;; Query subscriptions

(rf/reg-sub
  ::nodes
  (fn [db _]
    (:nodes db)))

(rf/reg-sub
  ::current-user
  (fn [db _]
    (:current-user db)))

(rf/reg-sub
  ::current-user-node
  :<- [::current-user]
  :<- [::nodes]
  (fn [[current-user nodes] _]
    (get nodes current-user)))

(rf/reg-sub
  ::user-reputation
  (fn [db [_ pubkey]]
    (get-in db [:nodes pubkey :reputation] 1.0)))

(rf/reg-sub
  ::user-mempool
  (fn [db [_ pubkey]]
    (get-in db [:nodes pubkey :mempool] [])))

(rf/reg-sub
  ::user-chain
  (fn [db [_ pubkey]]
    (get-in db [:nodes pubkey :chain] [])))

(rf/reg-sub
  ::network-reputation-stats
  :<- [::nodes]
  (fn [nodes _]
    (rep/reputation-stats nodes)))

(rf/reg-sub
  ::network-consensus-health
  (fn [db _]
    (let [nodes (:nodes db)
          all-questions (set (mapcat (fn [node]
                                       (map :question-id
                                            (concat (:mempool node)
                                                    (mapcat :txns (:chain node)))))
                                     (vals nodes)))
          all-transactions (mapcat (fn [node]
                                     (concat (:mempool node)
                                             (mapcat :txns (:chain node))))
                                   (vals nodes))]
      (consensus/calculate-network-consensus-health
        all-questions all-transactions nodes))))

(rf/reg-sub
  ::question-consensus
  (fn [db [_ question-id]]
    (let [nodes (:nodes db)
          all-transactions (mapcat (fn [node]
                                     (concat (:mempool node)
                                             (mapcat :txns (:chain node))))
                                   (vals nodes))]
      (consensus/debug-consensus-state question-id all-transactions nodes))))

;; UI state subscriptions

(rf/reg-sub
  ::loading?
  (fn [db _]
    (get-in db [:ui :loading?] false)))

(rf/reg-sub
  ::error
  (fn [db _]
    (get-in db [:ui :error])))

(rf/reg-sub
  ::active-question
  (fn [db _]
    (:active-question db)))

;; Curriculum subscriptions

(rf/reg-sub
  ::curriculum-index
  (fn [db _]
    (:curriculum-index db)))

(rf/reg-sub
  ::current-lesson
  (fn [db _]
    (:current-lesson db)))

(rf/reg-sub
  ::loaded-lessons
  (fn [db _]
    (:loaded-lessons db)))

(rf/reg-sub
  ::lesson-loaded?
  (fn [db [_ unit-id lesson-id]]
    (let [lesson-key (str unit-id "/" lesson-id)]
      (contains? (:loaded-lessons db) lesson-key))))

(rf/reg-sub
  ::lesson-data
  (fn [db [_ unit-id lesson-id]]
    (let [lesson-key (str unit-id "/" lesson-id)]
      (get-in db [:loaded-lessons lesson-key]))))

(rf/reg-sub
  ::lesson-loading?
  (fn [db _]
    (get-in db [:ui :lesson-loading?] false)))

;; Phase 3: QR Synchronization subscriptions

(rf/reg-sub
  ::qr-scanning?
  (fn [db _]
    (get-in db [:ui :qr-scanning?] false)))

(rf/reg-sub
  ::scan-progress
  (fn [db _]
    (get-in db [:ui :scan-progress])))

(rf/reg-sub
  ::processing-delta?
  (fn [db _]
    (get-in db [:ui :processing-delta?] false)))

(rf/reg-sub
  ::creating-delta?
  (fn [db _]
    (get-in db [:ui :creating-delta?] false)))

(rf/reg-sub
  ::delta-ready?
  (fn [db _]
    (get-in db [:ui :delta-ready?] false)))

(rf/reg-sub
  ::current-delta
  (fn [db _]
    (get-in db [:sync :current-delta])))

(rf/reg-sub
  ::sync-history
  (fn [db _]
    (:sync-history db [])))

(rf/reg-sub
  ::last-sync
  (fn [db _]
    (get-in db [:ui :last-sync])))

(rf/reg-sub
  ::sync-statistics
  :<- [::sync-history]
  (fn [sync-history _]
    (let [successful-syncs (filter #(= (:status %) :success) sync-history)
          failed-syncs (filter #(= (:status %) :failed) sync-history)]
      {:total-syncs (count sync-history)
       :successful-syncs (count successful-syncs)
       :failed-syncs (count failed-syncs)
       :success-rate (if (pos? (count sync-history))
                      (/ (count successful-syncs) (count sync-history))
                      0.0)
       :last-success (when (seq successful-syncs)
                      (:timestamp (last successful-syncs)))})))

;; Chart subscriptions

(rf/reg-sub
  ::chart-spec
  (fn [db [_ question-id]]
    (get-in db [:chart-specs question-id])))

(rf/reg-sub
  ::chart-statistics
  (fn [db [_ question-id]]
    (get-in db [:chart-specs question-id :stats])))

(rf/reg-sub
  ::current-question-chart
  :<- [::active-question]
  :<- [::chart-spec]
  (fn [[active-question chart-spec] _]
    (when active-question
      (get chart-spec (:id active-question)))))

(rf/reg-sub
  ::chart-loading?
  (fn [db _]
    (get-in db [:ui :chart-loading?] false)))

(rf/reg-sub
  ::available-lessons
  :<- [::curriculum-index]
  (fn [curriculum-index _]
    (when curriculum-index
      (mapcat (fn [unit]
                (map (fn [lesson]
                       {:unit-id (:id unit)
                        :lesson-id (:id lesson)
                        :unit-name (:name unit)
                        :lesson-name (:name lesson)})
                     (:lessons unit)))
              (:units curriculum-index)))))

(rf/reg-sub
  ::lessons-by-unit
  :<- [::curriculum-index]
  (fn [curriculum-index _]
    (when curriculum-index
      (into {} (map (fn [unit]
                      [(:id unit) 
                       {:unit unit
                        :lessons (:lessons unit)}])
                    (:units curriculum-index))))))

(rf/reg-sub
  ::user-progress
  (fn [db [_ pubkey]]
    (get-in db [:nodes pubkey :progress] 0)))

;; Curriculum loading events

(rf/reg-event-fx
  ::load-curriculum-index
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:ui :loading?] true)
     :fx [[:dispatch [::load-curriculum-index-async]]]}))

(rf/reg-event-fx
  ::load-curriculum-index-async
  (fn [{:keys [db]} _]
    (js/console.log "Starting async curriculum index load...")
    (go
      (let [result (<! (curriculum/load-curriculum-index))]
        (js/console.log "Curriculum index load result:" (clj->js result))
        (if (:success result)
          (do
            (js/console.log "Dispatching curriculum-index-loaded with data:" (clj->js (:data result)))
            (rf/dispatch [::curriculum-index-loaded (:data result)]))
          (do
            (js/console.error "Curriculum index load failed:" (:error result))
            (rf/dispatch [::set-error (str "Failed to load curriculum index: " (:error result))])))))
    {:db db}))

(rf/reg-event-db
  ::curriculum-index-loaded
  (fn [db [_ curriculum-index]]
    (js/console.log "curriculum-index-loaded event triggered with:" (clj->js curriculum-index))
    (-> db
        (assoc :curriculum-index curriculum-index)
        (assoc-in [:ui :loading?] false))))

(rf/reg-event-fx
  ::load-lesson
  (fn [{:keys [db]} [_ unit-id lesson-id]]
    (let [lesson-key (str unit-id "/" lesson-id)]
      (if (get-in db [:loaded-lessons lesson-key])
        ;; Lesson already loaded
        {:db (assoc db :current-lesson (get-in db [:loaded-lessons lesson-key]))}
        ;; Load lesson asynchronously
        {:db (assoc-in db [:ui :lesson-loading?] true)
         :fx [[:dispatch [::load-lesson-async unit-id lesson-id]]]}))))

(rf/reg-event-fx
  ::load-lesson-async
  (fn [{:keys [db]} [_ unit-id lesson-id]]
    (go
      (let [result (<! (curriculum/load-lesson unit-id lesson-id))
            lesson-key (str unit-id "/" lesson-id)]
        (if (:success result)
          (rf/dispatch [::lesson-loaded lesson-key (:data result)])
          (rf/dispatch [::set-error (str "Failed to load lesson: " (:error result))]))))
    {:db db}))

(rf/reg-event-db
  ::lesson-loaded
  (fn [db [_ lesson-key lesson-data]]
    (-> db
        (assoc-in [:loaded-lessons lesson-key] lesson-data)
        (assoc :current-lesson lesson-data)
        (assoc-in [:ui :lesson-loading?] false))))

(rf/reg-event-fx
  ::preload-lessons
  (fn [{:keys [db]} [_ lesson-specs]]
    {:db (assoc-in db [:ui :loading?] true)
     :fx [[:dispatch [::preload-lessons-async lesson-specs]]]}))

(rf/reg-event-fx
  ::preload-lessons-async
  (fn [{:keys [db]} [_ lesson-specs]]
    (go
      (let [results (<! (curriculum/load-multiple-lessons lesson-specs))]
        (rf/dispatch [::lessons-preloaded results])))
    {:db db}))

(rf/reg-event-db
  ::lessons-preloaded
  (fn [db [_ results]]
    (let [successful-loads (into {} (filter #(get-in (second %) [:success]) results))
          lesson-data (into {} (map (fn [[key result]] [key (:data result)]) successful-loads))]
      (-> db
          (update :loaded-lessons merge lesson-data)
          (assoc-in [:ui :loading?] false)))))

;; Chart rendering events

(rf/reg-event-fx
  ::prepare-chart
  (fn [{:keys [db]} [_ question-id chart-data]]
    (if (curriculum/validate-chart-data chart-data)
      {:db (assoc-in db [:ui :chart-loading?] true)
       :fx [[:dispatch [::prepare-chart-async question-id chart-data]]]}
      {:db db
       :fx [[:dispatch [::set-error "Invalid chart data structure"]]]})))

(rf/reg-event-fx
  ::prepare-chart-async
  (fn [{:keys [db]} [_ question-id chart-data]]
    (try
      (let [vega-spec (renderer/create-chart-spec chart-data)
            chart-stats (renderer/get-chart-statistics chart-data)]
        (rf/dispatch [::chart-prepared question-id vega-spec chart-stats]))
      (catch js/Error e
        (rf/dispatch [::set-error (str "Chart preparation failed: " e)])))
    {:db db}))

(rf/reg-event-db
  ::chart-prepared
  (fn [db [_ question-id vega-spec chart-stats]]
    (-> db
        (assoc-in [:chart-specs question-id] {:spec vega-spec :stats chart-stats})
        (assoc-in [:ui :chart-loading?] false))))

(rf/reg-event-fx
  ::render-chart
  (fn [{:keys [db]} [_ question-id element-id]]
    (let [chart-spec (get-in db [:chart-specs question-id])]
      (if chart-spec
        {:fx [[:dispatch [::render-chart-async question-id element-id (:spec chart-spec)]]]}
        {:fx [[:dispatch [::set-error "Chart specification not found"]]]}))))

(rf/reg-event-fx
  ::render-chart-async
  (fn [{:keys [db]} [_ question-id element-id vega-spec]]
    (go
      (let [result (<! (renderer/render-chart-to-element element-id vega-spec))]
        (if (:success result)
          (rf/dispatch [::chart-rendered question-id])
          (rf/dispatch [::set-error (str "Chart rendering failed: " (:error result))]))))
    {:db db}))

(rf/reg-event-db
  ::chart-rendered
  (fn [db [_ question-id]]
    (js/console.log "Chart rendered successfully:" question-id)
    db))

;; UI events

(rf/reg-event-db
  ::set-loading
  (fn [db [_ loading?]]
    (assoc-in db [:ui :loading?] loading?)))

(rf/reg-event-db
  ::set-lesson-loading
  (fn [db [_ loading?]]
    (assoc-in db [:ui :lesson-loading?] loading?)))

(rf/reg-event-db
  ::set-chart-loading
  (fn [db [_ loading?]]
    (assoc-in db [:ui :chart-loading?] loading?)))

(rf/reg-event-db
  ::set-error
  (fn [db [_ error]]
    (assoc-in db [:ui :error] error)))

(rf/reg-event-db
  ::clear-error
  (fn [db _]
    (assoc-in db [:ui :error] nil)))

(rf/reg-event-db
  ::set-active-question
  (fn [db [_ question]]
    (assoc db :active-question question)))

(rf/reg-event-db
  ::set-current-lesson
  (fn [db [_ lesson]]
    (assoc db :current-lesson lesson)))

;; Initialize default database
(defonce initialized?
  (do
    (rf/dispatch-sync [::initialize-db])
    true))
