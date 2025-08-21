(ns pok.test-runner
  "Test runner for all PoK blockchain tests (Phases 1-4)"
  (:require [cljs.test :as test]
            [pok.consensus-test]
            [pok.curriculum-test]
            [pok.phase2-test]
            [pok.renderer-test]
            [pok.reputation-test]
            [pok.qr-test]
            [pok.delta-test]
            [pok.sync-test]
            [pok.ui-test]))

(defn run-all-tests []
  "Runs all test suites"
  (test/run-tests 'pok.consensus-test
                  'pok.curriculum-test
                  'pok.phase2-test
                  'pok.renderer-test
                  'pok.reputation-test
                  'pok.qr-test
                  'pok.delta-test
                  'pok.sync-test
                  'pok.ui-test))

(defn run-phase3-tests []
  "Runs Phase 3 synchronization tests only"
  (test/run-tests 'pok.qr-test
                  'pok.delta-test
                  'pok.sync-test))

(defn run-core-tests []
  "Runs Phase 1 core blockchain tests"
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

;; Run all tests when this namespace is loaded
(run-all-tests)