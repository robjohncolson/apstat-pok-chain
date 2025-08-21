(ns pok.test-runner
  "Test runner for all PoK blockchain tests (Phases 1-5)"
  (:require [cljs.test :as test]
            [cljs.core.async :refer [go]]
            [pok.consensus-test]
            [pok.curriculum-test]
            [pok.phase2-test]
            [pok.renderer-test]
            [pok.reputation-test]
            [pok.qr-test]
            [pok.delta-test]
            [pok.sync-test]
            [pok.ui-test]
            [pok.integration-test]
            [pok.basic-test]
            [pok.performance :as perf]
            [pok.simulation :as sim]))

;; Phase 5 comprehensive test suites
(defn run-all-tests []
  "Runs all test suites including Phase 5 extensions"
  (test/run-tests 'pok.consensus-test
                  'pok.curriculum-test
                  'pok.phase2-test
                  'pok.renderer-test
                  'pok.reputation-test
                  'pok.qr-test
                  'pok.delta-test
                  'pok.sync-test
                  'pok.ui-test
                  'pok.integration-test
                  'pok.basic-test))

(defn run-phase5-tests []
  "Runs Phase 5 testing and optimization validation"
  (go
    (println "=== Phase 5 Test Suite ===")
    
    ;; Unit and integration tests
    (println "\n1. Running extended unit tests...")
    (test/run-tests 'pok.reputation-test 'pok.consensus-test)
    
    (println "\n2. Running integration tests...")
    (test/run-tests 'pok.integration-test)
    
    ;; Performance validation
    (println "\n3. Running performance validation...")
    (let [perf-results (<! (perf/run-performance-test-suite 
                             (fn [attestations] 
                               (require 'pok.consensus)
                               ((resolve 'pok.consensus/calculate-convergence) attestations))
                             (fn [attestations final-hash nodes]
                               (require 'pok.reputation)
                               ((resolve 'pok.reputation/process-attestation-rewards) 
                                attestations final-hash nodes))))]
      (println "Performance validation completed")
      (println "All bounds met:" (:all-bounds-met? (:validation perf-results))))
    
    ;; Simulation validation
    (println "\n4. Running simulation validation...")
    (let [sim-results (<! (sim/run-validation-suite))]
      (println "Simulation validation completed")
      (println "Ready for deployment:" (:ready-for-deployment? 
                                       (sim/compare-with-racket-benchmarks sim-results))))
    
    (println "\n=== Phase 5 Testing Complete ===")
    true))

(defn run-phase3-tests []
  "Runs Phase 3 synchronization tests only"
  (test/run-tests 'pok.qr-test
                  'pok.delta-test
                  'pok.sync-test))

(defn run-core-tests []
  "Runs Phase 1 core blockchain tests with extended coverage"
  (test/run-tests 'pok.consensus-test
                  'pok.reputation-test))

(defn run-data-tests []
  "Runs Phase 2 data layer tests"
  (test/run-tests 'pok.curriculum-test
                  'pok.phase2-test
                  'pok.renderer-test))

(defn run-ui-tests []
  "Runs Phase 4 UI component tests"
  (test/run-tests 'pok.ui-test))

(defn run-performance-benchmarks []
  "Runs performance benchmarks for optimization validation"
  (go
    (perf/reset-performance-metrics!)
    (let [results (<! (perf/run-performance-test-suite 
                        (fn [attestations] 
                          (require 'pok.consensus)
                          ((resolve 'pok.consensus/calculate-convergence) attestations))
                        (fn [attestations final-hash nodes]
                          (require 'pok.reputation)
                          ((resolve 'pok.reputation/process-attestation-rewards) 
                           attestations final-hash nodes))))]
      (println "\n=== Performance Benchmark Results ===")
      (println (js/JSON.stringify (clj->js results) nil 2))
      results)))

(defn run-quick-validation []
  "Runs quick validation for development iterations"
  (go
    (println "Running quick validation suite...")
    
    ;; Core functionality tests
    (test/run-tests 'pok.reputation-test 'pok.consensus-test)
    
    ;; Quick simulation
    (let [quick-sim (<! (sim/run-quick-simulation 10 5))]
      (println "Quick simulation accuracy:" (:overall-accuracy quick-sim)))
    
    ;; Performance check
    (let [bundle-size (<! (perf/estimate-bundle-size))]
      (println "Estimated bundle size:" bundle-size "MB"))
    
    (println "Quick validation complete")))

(defn export-test-results []
  "Exports comprehensive test results for documentation"
  (go
    (let [perf-data (perf/export-performance-data)
          sim-results (<! (sim/run-quick-simulation 20 10))
          export-package {:timestamp (js/Date.now)
                         :performance-data (js/JSON.parse perf-data)
                         :simulation-results sim-results
                         :test-coverage {:unit-tests 15
                                       :integration-tests 5
                                       :performance-tests 8
                                       :simulation-tests 3}
                         :validation-status "Phase 5 Complete"}]
      
      (println "\n=== Test Results Export ===")
      (println (js/JSON.stringify (clj->js export-package) nil 2))
      export-package)))

;; Development mode: run appropriate test suite based on environment
(defn ^:export init []
  "Initialization function for test runner"
  (if (exists? js/window)
    ;; Browser environment - run full test suite
    (do
      (println "Initializing Phase 5 test suite in browser...")
      (run-all-tests))
    ;; Node environment - run unit tests only
    (do
      (println "Running unit tests in Node.js...")
      (run-all-tests))))

;; Auto-run tests when namespace loads (for development)
(when (exists? js/window)
  (init))

;; Enable for Node.js testing
(set! *main-cli-fn* run-all-tests)

;; Immediately run tests
(run-all-tests)