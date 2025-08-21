(ns pok.basic-test
  "Basic test to verify Phase 5 testing infrastructure works"
  (:require [cljs.test :refer-macros [deftest testing is run-tests]]
            [pok.reputation :as rep]
            [pok.consensus :as cons]))

(deftest test-phase5-infrastructure
  (testing "Phase 5 testing infrastructure is working"
    (is (= 1 1))
    (is (= "test" "test"))
    (is (not (nil? js/Date)))))

(deftest test-clojurescript-features
  (testing "ClojureScript core features work"
    (is (= 3 (+ 1 2)))
    (is (= [1 2 3] (conj [1 2] 3)))
    (is (= {:a 1 :b 2} (assoc {:a 1} :b 2)))))

(deftest test-async-infrastructure
  (testing "Async infrastructure exists"
    (is (not (nil? js/Promise)))
    (is (function? js/setTimeout))))

(deftest test-reputation-functions
  (testing "Reputation functions are available"
    (is (function? rep/clamp))
    (is (= 5.0 (rep/clamp 1.0 5.0 10.0)))
    (is (= 1.0 (rep/clamp 1.0 0.5 10.0)))
    (is (= 10.0 (rep/clamp 1.0 15.0 10.0)))))

(deftest test-consensus-functions
  (testing "Consensus functions are available"
    (is (function? cons/calculate-convergence))
    (is (= 0.0 (cons/calculate-convergence [])))
    (is (function? cons/calculate-dynamic-quorum))
    (is (= 3 (cons/calculate-dynamic-quorum 5)))))

;; Tests are run by test runner
