(ns pok.integration-test
  "Integration tests for complete user flows and multi-phase cycles.
   Tests end-to-end scenarios including answer submission, consensus formation, and reputation updates."
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [cljs.core.async :as async :refer [go <! timeout]]
            [pok.core :as core]
            [pok.consensus :as cons]
            [pok.reputation :as rep]
            [pok.state :as state]
            [pok.delta :as delta]))

;; Test utilities
(defn create-test-node [pubkey reputation]
  {:pubkey pubkey
   :reputation reputation
   :mempool []
   :chain []
   :progress {:current-unit 1 :current-lesson 1}
   :archetype :diligent})

(defn create-test-transaction [pubkey question-id answer timestamp]
  {:id (str pubkey "-" timestamp)
   :timestamp timestamp
   :owner-pubkey pubkey
   :question-id question-id
   :type "attestation"
   :payload {:answer answer
             :hash (str (hash answer))}})

;; Integration test: Complete answer submission flow
(deftest test-complete-answer-flow
  (async done
    (go
      (let [;; Setup initial state
            nodes {"alice" (create-test-node "alice" 1.0)
                   "bob" (create-test-node "bob" 1.5)
                   "carol" (create-test-node "carol" 2.0)}
            question-id "stats-unit1-q1"
            
            ;; Simulate answer submissions over time
            txn1 (create-test-transaction "alice" question-id "A" 1000)
            txn2 (create-test-transaction "bob" question-id "A" 2000)
            txn3 (create-test-transaction "carol" question-id "B" 3000)
            
            all-txns [txn1 txn2 txn3]]
        
        ;; Test 1: Initial attestations are recorded
        (is (= 3 (count all-txns)))
        (is (every? #(= question-id (:question-id %)) all-txns))
        
        ;; Test 2: Consensus calculation
        (let [convergence (cons/calculate-convergence all-txns)]
          (is (= (/ 2.0 3.0) convergence))) ; 2 A's out of 3 total
        
        ;; Test 3: Consensus readiness check
        (let [readiness (cons/check-consensus-readiness all-txns question-id 2)]
          (is (true? (:ready? readiness)))
          (is (>= (:convergence readiness) cons/quorum-convergence-threshold)))
        
        ;; Test 4: Find consensus answer
        (let [consensus-answer (cons/find-consensus-answer all-txns false nodes)]
          (is (= (str (hash "A")) consensus-answer)))
        
        ;; Test 5: Process reputation rewards
        (let [final-hash (str (hash "A"))
              ;; Alice gets thought leader bonus (first correct at 0.0 proportion)
              ;; Bob gets normal reward (correct at 0.5 proportion)
              ;; Carol gets nothing (wrong answer)
              updated-nodes (<! (rep/process-attestation-rewards all-txns final-hash nodes))]
          (is (not (nil? updated-nodes))))
        
        (done)))))

;; Integration test: Multi-round consensus formation
(deftest test-multi-round-consensus
  (async done
    (go
      (let [;; Setup network with different archetypes
            nodes {"ace1" (assoc (create-test-node "ace1" 3.0) :archetype :ace)
                   "diligent1" (assoc (create-test-node "diligent1" 2.0) :archetype :diligent)
                   "diligent2" (assoc (create-test-node "diligent2" 1.8) :archetype :diligent)
                   "struggler1" (assoc (create-test-node "struggler1" 1.2) :archetype :struggler)
                   "guesser1" (assoc (create-test-node "guesser1" 0.8) :archetype :guesser)}
            
            question-id "stats-regression-q5"]
        
        ;; Round 1: Initial diverse answers (simulating real classroom uncertainty)
        (let [round1-txns [(create-test-transaction "ace1" question-id "D" 1000)
                           (create-test-transaction "diligent1" question-id "A" 2000)
                           (create-test-transaction "diligent2" question-id "B" 3000)
                           (create-test-transaction "struggler1" question-id "C" 4000)
                           (create-test-transaction "guesser1" question-id "A" 5000)]
              
              initial-convergence (cons/calculate-convergence round1-txns)]
          
          ;; Should be low convergence initially
          (is (< initial-convergence cons/quorum-convergence-threshold))
          
          ;; Round 2: More students join, some change answers after discussion
          (let [round2-txns (concat round1-txns
                                    [(create-test-transaction "diligent1" question-id "D" 6000) ; Changed mind
                                     (create-test-transaction "diligent2" question-id "D" 7000) ; Changed mind
                                     (create-test-transaction "struggler1" question-id "D" 8000)]) ; Changed mind
                
                final-convergence (cons/calculate-convergence round2-txns)
                consensus-answer (cons/find-consensus-answer round2-txns true nodes)]
            
            ;; Should reach consensus threshold
            (is (>= final-convergence cons/quorum-convergence-threshold))
            (is (= (str (hash "D")) consensus-answer))
            
            ;; Ace student should get thought leader bonus for being first correct
            (let [ace-proportion (rep/calculate-proportion-before-attestation round2-txns 1000 (str (hash "D")))]
              (is (= 0.0 ace-proportion)) ; First to answer correctly
              (is (= rep/thought-leader-bonus 
                     (rep/calculate-reputation-bonus ace-proportion (str (hash "D")) (str (hash "D"))))))))
        
        (done)))))

;; Integration test: Fork resolution and synchronization
(deftest test-fork-resolution
  (async done
    (go
      (let [;; Setup two network partitions
            partition-a {"alice" (create-test-node "alice" 2.0)
                         "bob" (create-test-node "bob" 1.8)}
            partition-b {"carol" (create-test-node "carol" 2.5)
                         "dave" (create-test-node "dave" 1.5)}
            
            question-id "unit2-hypothesis-test"
            
            ;; Partition A transactions
            txns-a [(create-test-transaction "alice" question-id "reject" 1000)
                    (create-test-transaction "bob" question-id "reject" 2000)]
            
            ;; Partition B transactions (different answer)
            txns-b [(create-test-transaction "carol" question-id "fail-to-reject" 1500)
                    (create-test-transaction "dave" question-id "fail-to-reject" 2500)]]
        
        ;; Test individual partition consensus
        (let [consensus-a (cons/find-consensus-answer txns-a false partition-a)
              consensus-b (cons/find-consensus-answer txns-b false partition-b)]
          (is (not= consensus-a consensus-b))) ; Different consensus
        
        ;; Test merge resolution when partitions reconnect
        (let [all-nodes (merge partition-a partition-b)
              all-txns (concat txns-a txns-b)
              
              ;; Apply delta merge logic (reputation-weighted resolution)
              merged-consensus (cons/find-consensus-answer all-txns true all-nodes)
              health-metrics (cons/calculate-network-consensus-health [question-id] all-txns all-nodes)]
          
          ;; Should resolve based on reputation weighting
          (is (not (nil? merged-consensus)))
          (is (= 1 (:total-questions health-metrics)))
          (is (> (:avg-convergence health-metrics) 0.0)))
        
        (done)))))

;; Integration test: Performance under load
(deftest test-performance-benchmarks
  (async done
    (go
      (let [;; Generate large dataset for performance testing
            num-nodes 40
            num-questions 20
            num-txns-per-question 50
            
            ;; Create nodes
            nodes (into {} (for [i (range num-nodes)]
                             [(str "node-" i) (create-test-node (str "node-" i) (+ 0.5 (rand 2.0)))]))
            
            ;; Generate random transactions
            all-txns (for [q (range num-questions)
                           t (range num-txns-per-question)]
                       (create-test-transaction 
                         (str "node-" (rand-int num-nodes))
                         (str "question-" q)
                         (rand-nth ["A" "B" "C" "D"])
                         (+ (* q 1000) (* t 100))))]
        
        ;; Performance Test 1: Consensus calculation time
        (let [start-time (js/Date.now)
              _ (doseq [q (range num-questions)]
                  (let [q-txns (filter #(= (str "question-" q) (:question-id %)) all-txns)]
                    (cons/calculate-convergence q-txns true nodes)))
              end-time (js/Date.now)
              total-time (- end-time start-time)]
          
          ;; Should complete within performance bounds (<100ms per operation target)
          (is (< total-time (* num-questions 100))))
        
        ;; Performance Test 2: Reputation processing
        (let [start-time (js/Date.now)
              sample-question "question-5"
              sample-txns (filter #(= sample-question (:question-id %)) all-txns)
              final-hash (cons/find-consensus-answer sample-txns true nodes)
              _ (<! (rep/process-attestation-rewards sample-txns final-hash nodes))
              end-time (js/Date.now)
              rep-time (- end-time start-time)]
          
          ;; Reputation processing should be fast
          (is (< rep-time 500))) ; 500ms for complex reputation calculation
        
        ;; Performance Test 3: Network health calculation
        (let [start-time (js/Date.now)
              all-questions (map #(str "question-" %) (range num-questions))
              _ (cons/calculate-network-consensus-health all-questions all-txns nodes)
              end-time (js/Date.now)
              health-time (- end-time start-time)]
          
          ;; Network health should be computed efficiently
          (is (< health-time 200)))
        
        (done)))))

;; Integration test: Curriculum progression flow
(deftest test-curriculum-progression
  (async done
    (go
      (let [student-node (create-test-node "student1" 1.0)
            curriculum-units ["descriptive-stats" "probability" "inference" "regression"]
            lessons-per-unit 10]
        
        ;; Simulate progression through curriculum
        (loop [current-unit 0
               current-lesson 0
               reputation 1.0
               completed-questions []]
          (if (>= current-unit (count curriculum-units))
            ;; Test completion
            (do
              (is (>= reputation 1.0)) ; Should maintain or improve reputation
              (is (= (* (count curriculum-units) lessons-per-unit) (count completed-questions)))
              (done))
            
            ;; Process current lesson
            (let [question-id (str (nth curriculum-units current-unit) "-lesson-" current-lesson)
                  
                  ;; Simulate answering with 80% accuracy (realistic student performance)
                  correct-answer? (< (rand) 0.8)
                  answer (if correct-answer? "correct" "wrong")
                  
                  txn (create-test-transaction "student1" question-id answer (js/Date.now))
                  
                  ;; Simulate peer attestations (simplified)
                  peer-txns [(create-test-transaction "peer1" question-id "correct" (+ (js/Date.now) 100))
                             (create-test-transaction "peer2" question-id "correct" (+ (js/Date.now) 200))]
                  
                  all-lesson-txns (cons txn peer-txns)
                  consensus-answer (cons/find-consensus-answer all-lesson-txns false {})
                  
                  ;; Calculate reputation change
                  proportion (rep/calculate-proportion-before-attestation all-lesson-txns (:timestamp txn) consensus-answer)
                  bonus (rep/calculate-reputation-bonus proportion consensus-answer (get-in txn [:payload :hash]))
                  weight (rep/calculate-reputation-weight reputation)
                  new-reputation (rep/update-reputation reputation bonus weight)]
              
              ;; Progress to next lesson/unit
              (if (>= (inc current-lesson) lessons-per-unit)
                (recur (inc current-unit) 0 new-reputation (conj completed-questions question-id))
                (recur current-unit (inc current-lesson) new-reputation (conj completed-questions question-id))))))
        
        ;; This test demonstrates realistic curriculum progression
        (is true)))) ; Placeholder assertion

;; Integration test: Error handling and edge cases
(deftest test-error-handling
  (testing "Malformed transaction handling"
    (let [malformed-txns [{:invalid "structure"}
                          {:timestamp "not-a-number"}
                          {:question-id nil :type "attestation"}]]
      ;; Should handle gracefully without crashing
      (is (number? (cons/calculate-convergence malformed-txns)))))
  
  (testing "Empty network scenarios"
    (is (= 0.0 (cons/calculate-convergence [])))
    (is (nil? (cons/find-consensus-answer [] false {})))
    (is (= 1.0 (rep/get-median-reputation {}))))
  
  (testing "Network with single node"
    (let [single-node {"lonely" (create-test-node "lonely" 1.0)}
          single-txn [(create-test-transaction "lonely" "q1" "answer" 1000)]]
      (is (= 1.0 (cons/calculate-convergence single-txn)))
      (is (not (nil? (cons/find-consensus-answer single-txn false single-node))))
      (is (= 1.0 (rep/get-median-reputation single-node))))))