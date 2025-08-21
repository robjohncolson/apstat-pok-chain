(ns pok.delta
  "Delta calculation and multi-level conflict resolution for offline blockchain merges.
   Implements 4-level resolution: ID conflicts (rep-weighted/pubkey lex), timestamp clusters,
   logical latest per-student, and hybrid fork selection with 0.95 decay and diversity bonus."
  (:require [cognitect.transit :as transit]
            [goog.crypt :as crypt]
            [goog.crypt.Sha256]
            [clojure.walk]
            [pok.reputation :as rep]
            [pok.consensus :as consensus]))

;; Delta calculation constants from foundational architecture
(def ^:const max-delta-size 500)        ; <500 bytes via Transit/gzip
(def ^:const timestamp-cluster-window 1000) ; 1 second window for clustering
(def ^:const fork-decay-factor 0.95)    ; 0.95 base for height decay
(def ^:const fork-weight-proposer 0.4)  ; 40% weight for proposer reputation
(def ^:const fork-weight-height 0.4)    ; 40% weight for height with decay
(def ^:const fork-weight-consensus 0.2) ; 20% weight for consensus strength
(def ^:const diversity-bonus-cap 0.1)   ; 10% maximum diversity bonus

;; Delta payload schema
(defn create-delta-payload
  "Creates standardized delta payload with transactions, blocks, and Merkle root."
  [new-transactions new-blocks merkle-root timestamp]
  {:version "1.0"
   :timestamp timestamp
   :merkle-root merkle-root
   :transactions new-transactions
   :blocks new-blocks
   :metadata {:size (count (str new-transactions new-blocks))
              :tx-count (count new-transactions)
              :block-count (count new-blocks)}})

(defn calculate-merkle-root
  "Calculates Merkle root hash from transactions and blocks for integrity verification."
  [transactions blocks]
  (let [all-items (concat transactions blocks)
        item-hashes (map #(str (hash %)) all-items)
        combined-hash (apply str item-hashes)
        sha256-instance (goog.crypt.Sha256.)
        _ (.update sha256-instance combined-hash)
        sha256-hash (.digest sha256-instance)]
    (crypt/byteArrayToHex sha256-hash)))

;; Delta filtering and calculation
(defn filter-delta-transactions
  "Filters transactions newer than peer timestamp for delta calculation."
  [local-transactions peer-timestamp]
  (filter #(> (:timestamp %) peer-timestamp) local-transactions))

(defn filter-delta-blocks
  "Filters blocks newer than peer timestamp for delta calculation."
  [local-blocks peer-timestamp]
  (filter #(> (:timestamp %) peer-timestamp) local-blocks))

(defn calculate-delta
  "Calculates delta payload containing only new transactions and blocks since peer timestamp."
  [local-state peer-timestamp]
  (let [all-local-txns (mapcat #(concat (:mempool %) (mapcat :txns (:chain %)))
                               (vals (:nodes local-state)))
        all-local-blocks (mapcat #(:chain %) (vals (:nodes local-state)))
        
        delta-txns (filter-delta-transactions all-local-txns peer-timestamp)
        delta-blocks (filter-delta-blocks all-local-blocks peer-timestamp)
        
        merkle-root (calculate-merkle-root delta-txns delta-blocks)
        timestamp (js/Date.now)]
    
    (create-delta-payload delta-txns delta-blocks merkle-root timestamp)))

;; 4-level conflict resolution system

;; Level 1: ID conflicts with reputation weighting and pubkey lexicographic tiebreak
(defn resolve-id-conflicts
  "Resolves transaction ID conflicts using reputation weighting and pubkey tiebreak."
  [conflicting-txns nodes]
  (let [txn-groups (group-by :id conflicting-txns)]
    (mapcat (fn [[_txn-id txns]]
              (if (= (count txns) 1)
                txns ; No conflict
                ;; Resolve conflict by reputation weight and pubkey
                (let [weighted-txns (map (fn [txn]
                                          (let [node (get nodes (:owner-pubkey txn))
                                                reputation (or (:reputation node) 1.0)
                                                weight (rep/calculate-reputation-weight reputation)]
                                            (assoc txn :resolution-weight weight)))
                                        txns)
                      sorted-txns (sort-by (juxt #(- (:resolution-weight %)) :owner-pubkey) weighted-txns)]
                  [(first sorted-txns)]))) ; Take highest weighted, lexicographically first
            txn-groups)))

;; Level 2: Timestamp clustering with 1-second windows
(defn cluster-transactions-by-timestamp
  "Groups transactions by 1-second timestamp windows for collision resolution."
  [transactions]
  (let [sorted-txns (sort-by :timestamp transactions)]
    (reduce (fn [clusters txn]
              (let [last-cluster (last clusters)
                    last-timestamp (when (seq last-cluster) (:timestamp (last last-cluster)))]
                (if (and last-timestamp 
                         (< (- (:timestamp txn) last-timestamp) timestamp-cluster-window))
                  ;; Add to existing cluster
                  (conj (vec (butlast clusters)) (conj last-cluster txn))
                  ;; Start new cluster
                  (conj clusters [txn]))))
            []
            sorted-txns)))

(defn resolve-timestamp-clusters
  "Resolves conflicts within timestamp clusters using reputation and deterministic ordering."
  [transaction-clusters nodes]
  (mapcat (fn [cluster]
            (if (= (count cluster) 1)
              cluster ; No conflict
              ;; Resolve within cluster
              (let [weighted-cluster (map (fn [txn]
                                           (let [node (get nodes (:owner-pubkey txn))
                                                 reputation (or (:reputation node) 1.0)
                                                 weight (rep/calculate-reputation-weight reputation)]
                                             (assoc txn :cluster-weight weight)))
                                         cluster)
                    resolved-txns (sort-by (juxt #(- (:cluster-weight %)) 
                                                  :owner-pubkey 
                                                  :timestamp) 
                                          weighted-cluster)]
                resolved-txns))) ; Keep all in cluster but ordered by resolution priority
          transaction-clusters))

;; Level 3: Logical latest per-student resolution
(defn resolve-logical-latest
  "Resolves to latest transaction per student per question for learning flexibility."
  [transactions]
  (let [student-question-groups (group-by (juxt :owner-pubkey :question-id) transactions)]
    (map (fn [[[_pubkey _qid] txns]]
           ;; Take latest transaction for each student-question pair
           (last (sort-by :timestamp txns)))
         student-question-groups)))

;; Level 4: Hybrid fork resolution with reputation, height decay, and diversity
(defn calculate-fork-diversity-bonus
  "Calculates diversity bonus based on unique contributors to fork."
  [fork-blocks]
  (let [unique-proposers (set (map :proposer fork-blocks))
        proposer-count (count unique-proposers)
        diversity-ratio (if (> proposer-count 1)
                         (min diversity-bonus-cap 
                              (/ (dec proposer-count) 10.0)) ; Scale by contributor diversity
                         0.0)]
    diversity-ratio))

(defn calculate-consensus-strength
  "Calculates consensus strength metric for fork evaluation."
  [fork-blocks all-transactions nodes]
  (let [fork-questions (set (mapcat #(map :question-id (:txns %)) fork-blocks))
        consensus-scores (map (fn [qid]
                               (let [attestations (consensus/get-attestations-for-question 
                                                    all-transactions qid)
                                     convergence (consensus/calculate-convergence 
                                                   attestations true nodes)]
                                 convergence))
                             fork-questions)]
    (if (seq consensus-scores)
      (/ (apply + consensus-scores) (count consensus-scores))
      0.0)))

(defn calculate-fork-weight
  "Calculates hybrid fork weight using proposer reputation, height decay, and consensus."
  [fork-blocks all-transactions nodes]
  (let [fork-height (count fork-blocks)
        
        ;; Component 1: Proposer reputation sum (40%)
        proposer-rep-sum (apply + (map (fn [block]
                                        (let [proposer-node (get nodes (:proposer block))
                                              reputation (or (:reputation proposer-node) 1.0)]
                                          (rep/calculate-reputation-weight reputation)))
                                      fork-blocks))
        proposer-weight (* fork-weight-proposer proposer-rep-sum)
        
        ;; Component 2: Height with 0.95 decay (40%)
        height-weight (* fork-weight-height 
                        (apply + (map-indexed (fn [idx _]
                                               (Math/pow fork-decay-factor idx))
                                             fork-blocks)))
        
        ;; Component 3: Consensus strength + diversity bonus (20%)
        consensus-strength (calculate-consensus-strength fork-blocks all-transactions nodes)
        diversity-bonus (calculate-fork-diversity-bonus fork-blocks)
        consensus-weight (* fork-weight-consensus (+ consensus-strength diversity-bonus))
        
        total-weight (+ proposer-weight height-weight consensus-weight)]
    
    {:total-weight total-weight
     :components {:proposer proposer-weight
                  :height height-weight
                  :consensus consensus-weight
                  :diversity-bonus diversity-bonus}
     :fork-height fork-height}))

(defn resolve-fork-conflicts
  "Resolves blockchain forks using hybrid reputation + height + consensus weighting."
  [competing-chains all-transactions nodes]
  (if (= (count competing-chains) 1)
    (first competing-chains) ; No fork
    (let [fork-weights (map #(calculate-fork-weight % all-transactions nodes) competing-chains)
          weighted-forks (map vector competing-chains fork-weights)
          sorted-forks (sort-by #(- (:total-weight (second %))) weighted-forks)]
      (first (first sorted-forks))))) ; Return chain with highest weight

;; Complete 4-level merge resolution
(defn resolve-merge-conflicts
  "Applies complete 4-level conflict resolution to merge delta with local state."
  [local-state delta-payload]
  (let [nodes (:nodes local-state)
        delta-txns (:transactions delta-payload)
        delta-blocks (:blocks delta-payload)
        
        ;; Combine local and delta transactions
        all-local-txns (mapcat #(concat (:mempool %) (mapcat :txns (:chain %)))
                               (vals nodes))
        combined-txns (concat all-local-txns delta-txns)
        
        ;; Level 1: Resolve ID conflicts
        id-resolved-txns (resolve-id-conflicts combined-txns nodes)
        
        ;; Level 2: Resolve timestamp clusters
        timestamp-clusters (cluster-transactions-by-timestamp id-resolved-txns)
        cluster-resolved-txns (resolve-timestamp-clusters timestamp-clusters nodes)
        
        ;; Level 3: Logical latest per student
        latest-resolved-txns (resolve-logical-latest cluster-resolved-txns)
        
        ;; Level 4: Fork resolution for chains
        all-local-chains (map :chain (vals nodes))
        delta-chain-extensions (group-by :proposer delta-blocks)
        
        ;; Build competing fork candidates
        competing-forks (map (fn [local-chain]
                              ;; Extend with relevant delta blocks
                              (let [chain-proposer (-> local-chain last :proposer)
                                    extensions (get delta-chain-extensions chain-proposer [])]
                                (concat local-chain extensions)))
                            all-local-chains)
        
        resolved-chain (resolve-fork-conflicts competing-forks 
                                              latest-resolved-txns 
                                              nodes)]
    
    {:resolved-transactions latest-resolved-txns
     :resolved-chain resolved-chain
     :conflict-stats {:id-conflicts (- (count combined-txns) (count id-resolved-txns))
                      :timestamp-clusters (count timestamp-clusters)
                      :logical-dedupes (- (count cluster-resolved-txns) (count latest-resolved-txns))
                      :fork-candidates (count competing-forks)}}))

;; Delta application to state
(defn apply-delta-to-state
  "Applies resolved delta to local state with conflict resolution."
  [local-state delta-payload]
  (let [resolution-result (resolve-merge-conflicts local-state delta-payload)
        resolved-txns (:resolved-transactions resolution-result)
        resolved-chain (:resolved-chain resolution-result)
        
        ;; Redistribute resolved transactions to appropriate node mempools
        txn-by-owner (group-by :owner-pubkey resolved-txns)
        
        updated-nodes (into {} (map (fn [[pubkey node]]
                                     (let [owner-txns (get txn-by-owner pubkey [])
                                           ;; Separate mempool from chain transactions
                                           mempool-txns (filter #(not (some #{%} 
                                                                            (mapcat :txns resolved-chain)))
                                                               owner-txns)
                                           updated-node (-> node
                                                           (assoc :mempool mempool-txns)
                                                           (assoc :chain resolved-chain))]
                                       [pubkey updated-node]))
                                   (:nodes local-state)))]
    
    (-> local-state
        (assoc :nodes updated-nodes)
        (update :sync-history conj {:timestamp (js/Date.now)
                                    :delta-hash (:merkle-root delta-payload)
                                    :conflicts (:conflict-stats resolution-result)
                                    :status :completed}))))

;; Serialization for QR transmission
(defn serialize-delta-for-qr
  "Serializes delta payload using Transit for compact QR transmission."
  [delta-payload]
  (try
    ;; Convert any Date objects to timestamps for serialization
    (let [cleaned-payload (clojure.walk/postwalk 
                            (fn [x] 
                              (if (instance? js/Date x) 
                                (.getTime x) 
                                x)) 
                            delta-payload)
          writer (transit/writer :json {})
          serialized (transit/write writer cleaned-payload)]
      {:success true :payload serialized :size (count serialized)})
    (catch js/Error e
      {:success false :error (str "Transit serialization error: " e)})))

(defn validate-delta-size
  "Validates that delta payload is within QR transmission limits."
  [serialized-payload]
  (let [payload-size (count serialized-payload)]
    {:valid? (<= payload-size max-delta-size)
     :size payload-size
     :limit max-delta-size
     :compression-needed? (> payload-size max-delta-size)}))

;; Public API functions
(defn create-sync-delta
  "Creates complete sync delta from local state for QR transmission."
  [local-state peer-timestamp]
  (let [delta-payload (calculate-delta local-state peer-timestamp)
        serialization-result (serialize-delta-for-qr delta-payload)]
    
    (if (:success serialization-result)
      (let [size-validation (validate-delta-size (:payload serialization-result))]
        (merge serialization-result
               {:delta delta-payload
                :size-validation size-validation}))
      serialization-result)))

(defn merge-peer-delta
  "Merges peer delta into local state with full conflict resolution."
  [local-state delta-payload]
  (try
    (let [updated-state (apply-delta-to-state local-state delta-payload)]
      {:success true :state updated-state})
    (catch js/Error e
      {:success false :error (str "Delta merge error: " e)})))

;; Statistics and debugging
(defn analyze-delta-composition
  "Analyzes delta composition for optimization and debugging."
  [delta-payload]
  (let [transactions (:transactions delta-payload)
        blocks (:blocks delta-payload)
        tx-types (frequencies (map :type transactions))
        block-types (frequencies (map :type blocks))
        proposers (set (map :proposer blocks))
        question-coverage (set (map :question-id transactions))]
    
    {:transaction-analysis {:count (count transactions)
                            :types tx-types
                            :unique-questions (count question-coverage)}
     :block-analysis {:count (count blocks)
                      :types block-types
                      :unique-proposers (count proposers)}
     :temporal-span {:earliest-tx (when (seq transactions) 
                                   (apply min (map :timestamp transactions)))
                     :latest-tx (when (seq transactions) 
                                 (apply max (map :timestamp transactions)))
                     :earliest-block (when (seq blocks) 
                                      (apply min (map :timestamp blocks)))
                     :latest-block (when (seq blocks) 
                                    (apply max (map :timestamp blocks)))}
     :merkle-root (:merkle-root delta-payload)}))
