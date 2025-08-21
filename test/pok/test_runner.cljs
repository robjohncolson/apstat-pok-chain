(ns pok.test-runner
  "Test runner for Phase 2 implementation"
  (:require [cljs.test :as test]
            [pok.phase2-test :as phase2]))

;; Run tests when this namespace is loaded
(test/run-tests 'pok.phase2-test)