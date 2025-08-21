(ns pok.consensus-test
  "Comprehensive unit tests for consensus calculation functions.
   Covers dynamic quorum, convergence calculation, and consensus validation."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [pok.consensus :as cons]
            [pok.reputation :as rep]))

;; Test data fixtures
(def sample-attestations
  [{:timestamp 1000 :owner-pubkey "alice" :payload {:hash "ans1"} :type "attestation"}
   {:timestamp 2000 :owner-pubkey "bob" :payload {:hash "ans1"} :type "attestation"}
   {:timestamp 3000 :owner-pubkey "carol" :payload {:hash "ans2"} :type "attestation"}
   {:timestamp 4000 :owner-pubkey "dave" :payload {:hash "ans1"} :type "ap_reveal"}])

(def sample-nodes
  {"alice" {:reputation 1.0}
   "bob" {:reputation 2.5}
   "carol" {:reputation 1.8}
   "dave" {:reputation 3.0}})

;; Unit tests for convergence calculation
(deftest test-calculate-convergence
  (testing "Empty attestations return 0.0"
    (is (= 0.0 (cons/calculate-convergence []))))
  
  (testing "Single attestation gives 1.0 convergence"
    (is (= 1.0 (cons/calculate-convergence 
                 [{:payload {:hash "single"} :type "attestation"}]))))
  
  (testing "Uniform distribution without weighting"
    ;; 3 attestations for ans1, 1 for ans2 = 3/4 = 0.75
    (is (= 0.75 (cons/calculate-convergence sample-attestations))))
  
  (testing "AP reveal gets maximum weight"
    ;; Should heavily weight dave's ap_reveal
    (let [convergence (cons/calculate-convergence sample-attestations true sample-nodes)]
      (is (> convergence 0.75)))) ; Should be higher due to AP weight
  
  (testing "Reputation-based weighting"
    (let [weighted-conv (cons/calculate-convergence sample-attestations true sample-nodes)
          unweighted-conv (cons/calculate-convergence sample-attestations false {})]
      ;; Weighted should be different from unweighted
      (is (not= weighted-conv unweighted-conv)))))

;; Unit tests for dynamic quorum calculation
(deftest test-calculate-dynamic-quorum
  (testing "Minimum base quorum enforcement"
    (is (= cons/base-quorum-size (cons/calculate-dynamic-quorum 1)))
    (is (= cons/base-quorum-size (cons/calculate-dynamic-quorum 5))))
  
  (testing "Fraction-based scaling for larger groups"
    (is (= 6 (cons/calculate-dynamic-quorum 20))) ; 0.3 * 20 = 6
    (is (= 12 (cons/calculate-dynamic-quorum 40))) ; 0.3 * 40 = 12
    (is (= 30 (cons/calculate-dynamic-quorum 100)))) ; 0.3 * 100 = 30
  
  (testing "Edge cases"
    (is (= cons/base-quorum-size (cons/calculate-dynamic-quorum 0)))
    (is (>= (cons/calculate-dynamic-quorum 10) cons/base-quorum-size))))

;; Unit tests for progressive quorum
(deftest test-calculate-progressive-quorum
  (testing "Early questions require smaller quorum"
    (is (= 2 (cons/calculate-progressive-quorum 0 100)))
    (is (= 2 (cons/calculate-progressive-quorum 25 100)))
    (is (= 2 (cons/calculate-progressive-quorum 49 100))))
  
  (testing "Later questions require larger quorum"
    (is (= 4 (cons/calculate-progressive-quorum 50 100)))
    (is (= 4 (cons/calculate-progressive-quorum 75 100)))
    (is (= 4 (cons/calculate-progressive-quorum 99 100))))
  
  (testing "Edge case: single question curriculum"
    (is (= 4 (cons/calculate-progressive-quorum 0 1)))))

;; Unit tests for attestation filtering
(deftest test-get-attestations-for-question
  (testing "Filters by question ID and type"
    (let [transactions [{:question-id "q1" :type "attestation"}
                        {:question-id "q1" :type "completion"}
                        {:question-id "q2" :type "attestation"}
                        {:question-id "q1" :type "ap_reveal"}]
          filtered (cons/get-attestations-for-question transactions "q1")]
      (is (= 2 (count filtered)))
      (is (every? #(= "q1" (:question-id %)) filtered))
      (is (every? #(contains? #{"attestation" "ap_reveal"} (:type %)) filtered)))))

;; Unit tests for consensus readiness
(deftest test-check-consensus-readiness
  (testing "Insufficient attestations"
    (let [result (cons/check-consensus-readiness 
                   [{:question-id "q1" :type "attestation" :payload {:hash "ans1"}}] 
                   "q1" 3)]
      (is (false? (:ready? result)))
      (is (= 1 (:attestation-count result)))))
  
  (testing "Sufficient attestations but low convergence"
    (let [transactions [{:question-id "q1" :type "attestation" :payload {:hash "ans1"}}
                        {:question-id "q1" :type "attestation" :payload {:hash "ans2"}}
                        {:question-id "q1" :type "attestation" :payload {:hash "ans3"}}]
          result (cons/check-consensus-readiness transactions "q1" 3)]
      (is (false? (:ready? result)))
      (is (< (:convergence result) cons/quorum-convergence-threshold))))
  
  (testing "Sufficient attestations and high convergence"
    (let [transactions [{:question-id "q1" :type "attestation" :payload {:hash "ans1"}}
                        {:question-id "q1" :type "attestation" :payload {:hash "ans1"}}
                        {:question-id "q1" :type "attestation" :payload {:hash "ans1"}}]
          result (cons/check-consensus-readiness transactions "q1" 3)]
      (is (true? (:ready? result)))
      (is (>= (:convergence result) cons/quorum-convergence-threshold)))))

;; Unit tests for consensus answer finding
(deftest test-find-consensus-answer
  (testing "Empty attestations return nil"
    (is (nil? (cons/find-consensus-answer [] false {}))))
  
  (testing "Single attestation returns its hash"
    (is (= "single" (cons/find-consensus-answer 
                      [{:payload {:hash "single"} :type "attestation"}] 
                      false {}))))
  
  (testing "Majority answer without weighting"
    (is (= "ans1" (cons/find-consensus-answer sample-attestations false {}))))
  
  (testing "AP reveal influences consensus with maximum weight"
    ;; Dave's ap_reveal should dominate even if minority
    (let [ap-dominant [{:payload {:hash "minority"} :type "ap_reveal" :owner-pubkey "dave"}
                       {:payload {:hash "majority"} :type "attestation" :owner-pubkey "alice"}
                       {:payload {:hash "majority"} :type "attestation" :owner-pubkey "bob"}]]
      (is (= "minority" (cons/find-consensus-answer ap-dominant true sample-nodes))))))

;; Integration tests for PoK validation
(deftest test-validate-pok-consensus
  (testing "Complete PoK validation flow"
    (let [completion-txn {:question-id "q1" :type "completion"}
          all-transactions [{:question-id "q1" :type "attestation" :payload {:hash "ans1"}}
                            {:question-id "q1" :type "attestation" :payload {:hash "ans1"}}
                            {:question-id "q1" :type "attestation" :payload {:hash "ans1"}}
                            {:question-id "q1" :type "attestation" :payload {:hash "ans1"}}]
          result (cons/validate-pok-consensus completion-txn all-transactions 20 100)]
      (is (contains? result :ready?))
      (is (contains? result :dynamic-quorum))
      (is (contains? result :progress-quorum))
      (is (contains? result :final-quorum))
      (is (= 6 (:dynamic-quorum result))) ; 0.3 * 20 = 6
      (is (= 2 (:progress-quorum result))) ; Early question
      (is (= 6 (:final-quorum result)))))) ; max(6, 2)

;; Unit tests for network health metrics
(deftest test-calculate-network-consensus-health
  (testing "Network health with mixed convergence"
    (let [questions ["q1" "q2" "q3"]
          transactions [{:question-id "q1" :type "attestation" :payload {:hash "ans1"}}
                        {:question-id "q1" :type "attestation" :payload {:hash "ans1"}}
                        {:question-id "q2" :type "attestation" :payload {:hash "ans2"}}
                        {:question-id "q2" :type "attestation" :payload {:hash "ans3"}}
                        {:question-id "q3" :type "attestation" :payload {:hash "ans4"}}]
          health (cons/calculate-network-consensus-health questions transactions sample-nodes)]
      (is (= 3 (:total-questions health)))
      (is (>= (:avg-convergence health) 0.0))
      (is (<= (:avg-convergence health) 1.0))
      (is (>= (:consensus-ratio health) 0.0))
      (is (<= (:consensus-ratio health) 1.0))))
  
  (testing "Empty network health"
    (let [health (cons/calculate-network-consensus-health [] [] {})]
      (is (= 0 (:total-questions health)))
      (is (= 0.0 (:avg-convergence health)))
      (is (= 0.0 (:consensus-ratio health))))))

;; Edge case tests
(deftest test-consensus-edge-cases
  (testing "Identical timestamps with deterministic resolution"
    (let [identical-ts [{:timestamp 1000 :payload {:hash "ans1"} :type "attestation"}
                        {:timestamp 1000 :payload {:hash "ans2"} :type "attestation"}
                        {:timestamp 1000 :payload {:hash "ans1"} :type "attestation"}]
          convergence (cons/calculate-convergence identical-ts)]
      (is (number? convergence))
      (is (>= convergence 0.0))
      (is (<= convergence 1.0))))
  
  (testing "Large node count quorum calculation"
    (is (= 300 (cons/calculate-dynamic-quorum 1000))))
  
  (testing "Question filtering with no matches"
    (is (empty? (cons/get-attestations-for-question 
                  [{:question-id "other" :type "attestation"}] 
                  "target"))))
  
  (testing "Debug consensus state for troubleshooting"
    (let [debug-info (cons/debug-consensus-state 
                       "q1" 
                       [{:question-id "q1" :type "attestation" :payload {:hash "ans1"}}] 
                       sample-nodes)]
      (is (contains? debug-info :convergence))
      (is (contains? debug-info :hash-distribution))
      (is (contains? debug-info :meets-threshold?)))))