(ns pok.delta-test
  "Test suite for delta calculation and 4-level conflict resolution."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.core.async :as async :refer [go <!]]
            [pok.delta :as delta]
            [pok.reputation :as rep]))

;; Test data fixtures
(def sample-nodes
  {"alice" {:pubkey "alice" :reputation 2.5 :archetype :aces}
   "bob" {:pubkey "bob" :reputation 1.8 :archetype :diligent}
   "charlie" {:pubkey "charlie" :reputation 0.9 :archetype :strugglers}})

(def sample-transactions
  [{:id "tx1" :timestamp 1000 :owner-pubkey "alice" :question-id "q1" :type "completion"}
   {:id "tx2" :timestamp 1500 :owner-pubkey "bob" :question-id "q1" :type "attestation"}
   {:id "tx3" :timestamp 2000 :owner-pubkey "charlie" :question-id "q2" :type "completion"}])

(def sample-blocks
  [{:hash "block1" :timestamp 3000 :proposer "alice" :txns []}
   {:hash "block2" :timestamp 3500 :proposer "bob" :txns []}])

(deftest test-merkle-root-calculation
  (testing "Merkle root consistency"
    (let [txns [{:id "tx1"} {:id "tx2"}]
          blocks [{:hash "block1"}]
          root1 (delta/calculate-merkle-root txns blocks)
          root2 (delta/calculate-merkle-root txns blocks)]
      (is (= root1 root2)) ; Same input should give same root
      (is (string? root1))
      (is (> (count root1) 0))))
  
  (testing "Different data gives different roots"
    (let [txns1 [{:id "tx1"}]
          txns2 [{:id "tx2"}]
          blocks []
          root1 (delta/calculate-merkle-root txns1 blocks)
          root2 (delta/calculate-merkle-root txns2 blocks)]
      (is (not= root1 root2)))))

(deftest test-delta-filtering
  (testing "Transaction filtering by timestamp"
    (let [peer-timestamp 1250
          filtered (delta/filter-delta-transactions sample-transactions peer-timestamp)]
      (is (= (count filtered) 2)) ; tx2 and tx3 should be included
      (is (every? #(> (:timestamp %) peer-timestamp) filtered))))
  
  (testing "Block filtering by timestamp"
    (let [peer-timestamp 3250
          filtered (delta/filter-delta-blocks sample-blocks peer-timestamp)]
      (is (= (count filtered) 1)) ; Only block2 should be included
      (is (every? #(> (:timestamp %) peer-timestamp) filtered)))))

(deftest test-delta-creation
  (testing "Delta payload structure"
    (let [local-state {:nodes {"alice" {:mempool sample-transactions
                                        :chain sample-blocks}}}
          peer-timestamp 1250
          delta (delta/calculate-delta local-state peer-timestamp)]
      (is (= (:version delta) "1.0"))
      (is (contains? delta :timestamp))
      (is (contains? delta :merkle-root))
      (is (contains? delta :transactions))
      (is (contains? delta :blocks))
      (is (contains? delta :metadata))
      (is (> (count (:transactions delta)) 0)))))

(deftest test-id-conflict-resolution
  (testing "No conflicts - single transaction per ID"
    (let [txns [{:id "tx1" :owner-pubkey "alice"}
                {:id "tx2" :owner-pubkey "bob"}]
          resolved (delta/resolve-id-conflicts txns sample-nodes)]
      (is (= (count resolved) 2))))
  
  (testing "ID conflicts resolved by reputation"
    (let [conflicting-txns [{:id "tx1" :owner-pubkey "alice" :timestamp 1000}
                            {:id "tx1" :owner-pubkey "charlie" :timestamp 1000}]
          resolved (delta/resolve-id-conflicts conflicting-txns sample-nodes)]
      (is (= (count resolved) 1))
      (is (= (:owner-pubkey (first resolved)) "alice")) ; Higher reputation wins
      ))
  
  (testing "ID conflicts with equal reputation - pubkey tiebreak"
    (let [equal-rep-nodes {"alice" {:reputation 1.0}
                           "bob" {:reputation 1.0}}
          conflicting-txns [{:id "tx1" :owner-pubkey "bob"}
                            {:id "tx1" :owner-pubkey "alice"}]
          resolved (delta/resolve-id-conflicts conflicting-txns equal-rep-nodes)]
      (is (= (count resolved) 1))
      (is (= (:owner-pubkey (first resolved)) "alice")) ; Lexicographically first
      )))

(deftest test-timestamp-clustering
  (testing "Transactions within cluster window"
    (let [txns [{:timestamp 1000}
                {:timestamp 1500}  ; Within 1s window
                {:timestamp 2800}  ; New cluster
                {:timestamp 2900}] ; Within new cluster
          clusters (delta/cluster-transactions-by-timestamp txns)]
      (is (= (count clusters) 2))
      (is (= (count (first clusters)) 2))
      (is (= (count (second clusters)) 2))))
  
  (testing "Single transaction clusters"
    (let [txns [{:timestamp 1000}
                {:timestamp 3000}  ; > 1s gap
                {:timestamp 6000}] ; > 1s gap
          clusters (delta/cluster-transactions-by-timestamp txns)]
      (is (= (count clusters) 3))
      (is (every? #(= (count %) 1) clusters)))))

(deftest test-cluster-resolution
  (testing "Cluster resolution by reputation and ordering"
    (let [cluster [{:owner-pubkey "charlie" :timestamp 1000}
                   {:owner-pubkey "alice" :timestamp 1001}]
          clusters [cluster]
          resolved (delta/resolve-timestamp-clusters clusters sample-nodes)]
      (is (= (count resolved) 2))
      ;; Alice should be first due to higher reputation
      (is (= (:owner-pubkey (first resolved)) "alice")))))

(deftest test-logical-latest-resolution
  (testing "Latest transaction per student-question"
    (let [txns [{:owner-pubkey "alice" :question-id "q1" :timestamp 1000}
                {:owner-pubkey "alice" :question-id "q1" :timestamp 2000} ; Latest for alice-q1
                {:owner-pubkey "alice" :question-id "q2" :timestamp 1500}
                {:owner-pubkey "bob" :question-id "q1" :timestamp 1800}]
          resolved (delta/resolve-logical-latest txns)]
      (is (= (count resolved) 3)) ; 3 unique student-question pairs
      ;; Find alice-q1 transaction - should be the latest (timestamp 2000)
      (let [alice-q1 (first (filter #(and (= (:owner-pubkey %) "alice")
                                          (= (:question-id %) "q1")) resolved))]
        (is (= (:timestamp alice-q1) 2000))))))

(deftest test-fork-diversity-bonus
  (testing "Diversity bonus calculation"
    (let [diverse-blocks [{:proposer "alice"} {:proposer "bob"} {:proposer "charlie"}]
          single-proposer-blocks [{:proposer "alice"} {:proposer "alice"}]
          diverse-bonus (delta/calculate-fork-diversity-bonus diverse-blocks)
          single-bonus (delta/calculate-fork-diversity-bonus single-proposer-blocks)]
      (is (> diverse-bonus single-bonus))
      (is (>= diverse-bonus 0.0))
      (is (<= diverse-bonus delta/diversity-bonus-cap))))
  
  (testing "Diversity bonus capped at maximum"
    (let [many-proposers (map #(hash-map :proposer (str "proposer-" %)) (range 20))
          bonus (delta/calculate-fork-diversity-bonus many-proposers)]
      (is (<= bonus delta/diversity-bonus-cap)))))

(deftest test-fork-weight-calculation
  (testing "Fork weight components"
    (let [fork-blocks [{:proposer "alice" :txns []}
                       {:proposer "bob" :txns []}]
          all-txns []
          weight-result (delta/calculate-fork-weight fork-blocks all-txns sample-nodes)]
      (is (contains? weight-result :total-weight))
      (is (contains? weight-result :components))
      (is (contains? weight-result :fork-height))
      (is (= (:fork-height weight-result) 2))
      (is (> (:total-weight weight-result) 0))))
  
  (testing "Height decay factor"
    (let [single-block [{:proposer "alice" :txns []}]
          double-block [{:proposer "alice" :txns []} {:proposer "alice" :txns []}]
          weight1 (delta/calculate-fork-weight single-block [] sample-nodes)
          weight2 (delta/calculate-fork-weight double-block [] sample-nodes)]
      ;; Second block should contribute less due to decay
      (is (< (get-in weight2 [:components :height])
             (* 2 (get-in weight1 [:components :height])))))))

(deftest test-fork-resolution
  (testing "Fork resolution with clear winner"
    (let [weak-chain [{:proposer "charlie" :txns []}] ; Low reputation proposer
          strong-chain [{:proposer "alice" :txns []}  ; High reputation proposer
                        {:proposer "alice" :txns []}]
          competing-chains [weak-chain strong-chain]
          winner (delta/resolve-fork-conflicts competing-chains [] sample-nodes)]
      (is (= winner strong-chain))))
  
  (testing "Single chain - no conflict"
    (let [single-chain [{:proposer "alice" :txns []}]
          winner (delta/resolve-fork-conflicts [single-chain] [] sample-nodes)]
      (is (= winner single-chain)))))

(deftest test-complete-conflict-resolution
  (testing "4-level resolution integration"
    (let [local-state {:nodes sample-nodes}
          delta-payload {:transactions sample-transactions
                         :blocks sample-blocks}
          resolution (delta/resolve-merge-conflicts local-state delta-payload)]
      (is (contains? resolution :resolved-transactions))
      (is (contains? resolution :resolved-chain))
      (is (contains? resolution :conflict-stats))
      (is (>= (count (:resolved-transactions resolution)) 0)))))

(deftest test-delta-serialization
  (testing "Transit serialization success"
    (let [delta-payload {:version "1.0"
                         :transactions []
                         :blocks []
                         :merkle-root "test-root"}
          result (delta/serialize-delta-for-qr delta-payload)]
      (is (:success result))
      (is (contains? result :payload))
      (is (contains? result :size))))
  
  (testing "Size validation"
    (let [large-payload (apply str (repeat 1000 "x"))
          size-validation (delta/validate-delta-size large-payload)]
      (is (contains? size-validation :valid?))
      (is (contains? size-validation :size))
      (is (contains? size-validation :limit)))))

(deftest test-delta-application
  (testing "Delta application to state"
    (let [local-state {:nodes {"alice" {:mempool [] :chain [] :reputation 1.0}}
                       :sync-history []}
          delta-payload {:transactions sample-transactions
                         :blocks sample-blocks
                         :merkle-root "test-root"}
          result (delta/apply-delta-to-state local-state delta-payload)]
      (is (contains? result :nodes))
      (is (contains? result :sync-history))
      ;; Sync history should be updated
      (is (> (count (:sync-history result)) 0)))))

(deftest test-edge-cases
  (testing "Empty delta payload"
    (let [empty-delta {:transactions [] :blocks [] :merkle-root "empty"}
          local-state {:nodes sample-nodes}
          resolution (delta/resolve-merge-conflicts local-state empty-delta)]
      (is (contains? resolution :resolved-transactions))
      (is (= (count (:resolved-transactions resolution)) 0))))
  
  (testing "Identical timestamps with different pubkeys"
    (let [identical-ts-txns [{:id "tx1" :timestamp 1000 :owner-pubkey "alice"}
                             {:id "tx2" :timestamp 1000 :owner-pubkey "bob"}]
          clusters (delta/cluster-transactions-by-timestamp identical-ts-txns)]
      (is (= (count clusters) 1)) ; Should be in same cluster
      (is (= (count (first clusters)) 2))))
  
  (testing "Large reputation differences"
    (let [extreme-rep-nodes {"alice" {:reputation 10.0}
                             "bob" {:reputation 0.1}}
          conflicting-txns [{:id "tx1" :owner-pubkey "alice"}
                            {:id "tx1" :owner-pubkey "bob"}]
          resolved (delta/resolve-id-conflicts conflicting-txns extreme-rep-nodes)]
      (is (= (count resolved) 1))
      (is (= (:owner-pubkey (first resolved)) "alice")))))

(deftest test-delta-analysis
  (testing "Delta composition analysis"
    (let [delta-payload {:transactions sample-transactions
                         :blocks sample-blocks
                         :merkle-root "test-root"}
          analysis (delta/analyze-delta-composition delta-payload)]
      (is (contains? analysis :transaction-analysis))
      (is (contains? analysis :block-analysis))
      (is (contains? analysis :temporal-span))
      (is (= (get-in analysis [:transaction-analysis :count]) 3))
      (is (= (get-in analysis [:block-analysis :count]) 2)))))

(deftest test-performance-constraints
  (testing "Delta size constraints"
    (let [large-state {:nodes (into {} (map #(vector (str "user-" %)
                                                     {:mempool (repeat 10 {:id (str "tx-" %)
                                                                           :timestamp (+ 1000 %)})
                                                      :chain []})
                                            (range 50)))}
          delta (delta/calculate-delta large-state 0)]
      ;; Delta should be reasonable size
      (is (< (count (str delta)) 10000)))) ; Reasonable upper bound
  
  (testing "Conflict resolution performance"
    (let [many-conflicts (map #(hash-map :id "same-id"
                                         :owner-pubkey (str "user-" %)
                                         :timestamp 1000) (range 20))
          resolved (delta/resolve-id-conflicts many-conflicts 
                                               (into {} (map #(vector (str "user-" %)
                                                                     {:reputation (rand)})
                                                            (range 20))))]
      (is (= (count resolved) 1)) ; Should resolve to single transaction
      )))

(defn run-delta-tests []
  (run-tests 'pok.delta-test))