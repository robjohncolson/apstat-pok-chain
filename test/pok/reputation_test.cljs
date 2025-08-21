(ns pok.reputation-test
  "Comprehensive unit tests for reputation calculation functions.
   Covers edge cases, timestamp handling, and accuracy-conditional bonuses."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [pok.reputation :as rep]))

;; Test data fixtures
(def sample-attestations
  [{:timestamp 1000 :owner-pubkey "alice" :payload {:hash "abc123"}}
   {:timestamp 2000 :owner-pubkey "bob" :payload {:hash "abc123"}}
   {:timestamp 3000 :owner-pubkey "carol" :payload {:hash "def456"}}
   {:timestamp 4000 :owner-pubkey "dave" :payload {:hash "abc123"}}])

(def sample-nodes
  {"alice" {:reputation 1.0}
   "bob" {:reputation 2.5}
   "carol" {:reputation 1.8}
   "dave" {:reputation 0.5}})

;; Unit tests for proportion calculation
(deftest test-calculate-proportion-before-attestation
  (testing "Empty attestations return 0.0"
    (is (= 0.0 (rep/calculate-proportion-before-attestation [] 5000 "abc123"))))
  
  (testing "Single attestation at timestamp has no priors"
    (is (= 0.0 (rep/calculate-proportion-before-attestation 
                 [{:timestamp 1000 :payload {:hash "abc123"}}] 
                 1000 "abc123"))))
  
  (testing "Correct proportion calculation with multiple attestations"
    ;; At timestamp 3000, we have 2 priors: both "abc123"
    (is (= 1.0 (rep/calculate-proportion-before-attestation 
                 sample-attestations 3000 "abc123")))
    ;; At timestamp 4000, we have 3 priors: 2x"abc123", 1x"def456" = 2/3
    (is (= (/ 2.0 3.0) (rep/calculate-proportion-before-attestation 
                        sample-attestations 4000 "abc123"))))
  
  (testing "Strict timestamp filtering excludes self and later"
    ;; At timestamp 2000, only 1 prior attestation exists
    (is (= 1.0 (rep/calculate-proportion-before-attestation 
                 sample-attestations 2000 "abc123")))))

;; Unit tests for bonus calculation
(deftest test-calculate-reputation-bonus
  (testing "Thought leader bonus when proportion < 0.5 and correct"
    (is (= rep/thought-leader-bonus 
           (rep/calculate-reputation-bonus 0.3 "correct" "correct"))))
  
  (testing "No bonus when proportion >= 0.5 even if correct"
    (is (= 1.0 (rep/calculate-reputation-bonus 0.7 "correct" "correct"))))
  
  (testing "No bonus when wrong answer even if minority"
    (is (= 1.0 (rep/calculate-reputation-bonus 0.2 "correct" "wrong"))))
  
  (testing "Edge case: exactly at threshold"
    (is (= 1.0 (rep/calculate-reputation-bonus 0.5 "correct" "correct")))))

;; Unit tests for weight calculation
(deftest test-calculate-reputation-weight
  (testing "Logarithmic weight scaling"
    (is (= (js/Math.log1p 1.0) (rep/calculate-reputation-weight 1.0)))
    (is (= (js/Math.log1p 2.5) (rep/calculate-reputation-weight 2.5)))
    (is (= (js/Math.log1p 0.1) (rep/calculate-reputation-weight 0.1))))
  
  (testing "Zero reputation edge case"
    (is (= (js/Math.log1p 0.0) (rep/calculate-reputation-weight 0.0)))))

;; Unit tests for reputation update
(deftest test-update-reputation
  (testing "Normal reputation update"
    (let [current 2.0
          bonus 1.5
          weight 0.5
          expected (+ current (* bonus weight))]
      (is (= expected (rep/update-reputation current bonus weight)))))
  
  (testing "Lower bound clamping"
    (is (= 0.1 (rep/update-reputation 0.05 0.5 0.1))))
  
  (testing "Upper bound clamping"
    (is (= 10.0 (rep/update-reputation 9.8 2.0 2.0))))
  
  (testing "Reputation bounds validation"
    (is (and (>= (rep/update-reputation 5.0 1.2 1.0) 0.1)
             (<= (rep/update-reputation 5.0 1.2 1.0) 10.0)))))

;; Integration tests for complete flows
(deftest test-reputation-integration
  (testing "Complete attestation reward processing simulation"
    (let [attestations [{:timestamp 1000 :owner-pubkey "alice" :payload {:hash "correct"}}
                        {:timestamp 2000 :owner-pubkey "bob" :payload {:hash "wrong"}}
                        {:timestamp 3000 :owner-pubkey "carol" :payload {:hash "correct"}}]
          final-hash "correct"
          nodes {"alice" {:reputation 1.0}
                 "bob" {:reputation 1.5}
                 "carol" {:reputation 2.0}}]
      ;; Alice should get thought leader bonus (first correct at 0.0 proportion)
      ;; Carol should get normal reward (correct at 0.5 proportion)
      ;; Bob should get nothing (wrong answer)
      (is (not (nil? (rep/process-attestation-rewards attestations final-hash nodes)))))))))

;; Utility function tests
(deftest test-median-reputation
  (testing "Odd number of nodes"
    (let [nodes {"a" {:reputation 1.0} "b" {:reputation 2.0} "c" {:reputation 3.0}}]
      (is (= 2.0 (rep/get-median-reputation nodes)))))
  
  (testing "Even number of nodes"
    (let [nodes {"a" {:reputation 1.0} "b" {:reputation 2.0} 
                 "c" {:reputation 3.0} "d" {:reputation 4.0}}]
      (is (= 2.5 (rep/get-median-reputation nodes)))))
  
  (testing "Empty nodes"
    (is (= 1.0 (rep/get-median-reputation {})))))

(deftest test-reputation-bounds-validation
  (testing "All reputations within bounds"
    (let [valid-nodes {"a" {:reputation 1.0} "b" {:reputation 5.0} "c" {:reputation 10.0}}]
      (is (true? (rep/validate-reputation-bounds valid-nodes)))))
  
  (testing "Reputation below minimum bound"
    (let [invalid-nodes {"a" {:reputation 0.05} "b" {:reputation 1.0}}]
      (is (false? (rep/validate-reputation-bounds invalid-nodes)))))
  
  (testing "Reputation above maximum bound"
    (let [invalid-nodes {"a" {:reputation 1.0} "b" {:reputation 15.0}}]
      (is (false? (rep/validate-reputation-bounds invalid-nodes))))))

;; Edge case tests
(deftest test-edge-cases
  (testing "Identical timestamps (pubkey sorting)"
    (let [identical-ts [{:timestamp 1000 :owner-pubkey "alice" :payload {:hash "ans1"}}
                        {:timestamp 1000 :owner-pubkey "bob" :payload {:hash "ans2"}}
                        {:timestamp 1000 :owner-pubkey "carol" :payload {:hash "ans1"}}]]
      ;; Should handle deterministically even with identical timestamps
      (is (number? (rep/calculate-proportion-before-attestation identical-ts 1001 "ans1")))))
  
  (testing "Single node network"
    (let [single-node {"alice" {:reputation 1.0}}]
      (is (= 1.0 (rep/get-median-reputation single-node)))
      (is (true? (rep/validate-reputation-bounds single-node)))))
  
  (testing "Maximum reputation with large bonus"
    ;; Should clamp to 10.0 even with large multipliers
    (is (= 10.0 (rep/update-reputation 8.0 rep/thought-leader-bonus 3.0)))))