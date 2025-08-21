(ns pok.state
  "Re-frame state management for PoK blockchain application.
   Manages nodes, chains, mempools, and consensus state with immutable updates."
  (:require [re-frame.core :as rf]
            [clojure.core.async :as async :refer [go]]
            [pok.reputation :as rep]
            [pok.consensus :as consensus]))

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
     :curriculum []               ; loaded curriculum questions
     :active-question nil         ; currently displayed question
     :network-stats {}            ; consensus health metrics
     :sync-history []             ; QR sync operations
     :ui {:loading? false
          :error nil
          :modal nil}}))

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

;; Synchronization events

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

;; UI events

(rf/reg-event-db
  ::set-loading
  (fn [db [_ loading?]]
    (assoc-in db [:ui :loading?] loading?)))

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

;; Initialize default database
(defonce initialized?
  (do
    (rf/dispatch-sync [::initialize-db])
    true))
