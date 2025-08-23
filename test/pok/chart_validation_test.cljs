(ns pok.chart-validation-test
  "Phase 8 chart and table validation tests for performance and rendering fidelity."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.core.async :as async :refer [go <! chan timeout]]
            [pok.renderer :as renderer]
            [pok.curriculum :as curriculum]))

;; Chart type validation data
(def test-chart-types
  {:bar {:x-labels ["A" "B" "C"] 
         :series [{:name "Series1" :values [10 20 15]}]
         :chart-type "bar"}
   :pie {:x-labels ["Red" "Blue" "Green"] 
         :series [{:name "Colors" :values [30 45 25]}]
         :chart-type "pie"}
   :histogram {:x-labels ["0-10" "10-20" "20-30"] 
               :series [{:name "Frequency" :values [5 12 8]}]
               :chart-type "histogram"}})

(def test-table-data
  {:table [["Name" "Score" "Grade"]
           ["Alice" "95" "A"]
           ["Bob" "87" "B"]
           ["Carol" "92" "A"]]})

(deftest validate-chart-rendering-performance
  "Tests all chart types for <25ms render performance."
  (testing "Bar chart performance"
    (async done
      (go
        (let [start-time (js/Date.now)
              chart-spec (renderer/create-chart-spec 
                           (:table test-table-data) "bar")
              render-time (- (js/Date.now) start-time)]
          (is (< render-time 25) 
              (str "Bar chart spec creation took " render-time "ms, expected <25ms"))
          (is (some? chart-spec) "Bar chart spec should be created")
          (done)))))

  (testing "Pie chart performance"
    (async done
      (go
        (let [start-time (js/Date.now)
              chart-spec (renderer/create-chart-spec 
                           [["Category" "Value"] ["A" 30] ["B" 70]] "pie")
              render-time (- (js/Date.now) start-time)]
          (is (< render-time 25) 
              (str "Pie chart spec creation took " render-time "ms, expected <25ms"))
          (is (some? chart-spec) "Pie chart spec should be created")
          (done)))))

  (testing "Histogram performance"
    (async done
      (go
        (let [start-time (js/Date.now)
              chart-spec (renderer/create-chart-spec 
                           [["Range" "Frequency"] ["0-10" 5] ["10-20" 12]] "histogram")
              render-time (- (js/Date.now) start-time)]
          (is (< render-time 25) 
              (str "Histogram spec creation took " render-time "ms, expected <25ms"))
          (is (some? chart-spec) "Histogram spec should be created")
          (done))))))

(deftest validate-table-rendering-performance
  "Tests table rendering performance for <25ms."
  (testing "Table spec creation performance"
    (async done
      (go
        (let [start-time (js/Date.now)
              table-spec (renderer/create-vega-table-spec 
                           (:table test-table-data) :medium)
              render-time (- (js/Date.now) start-time)]
          (is (< render-time 25) 
              (str "Table spec creation took " render-time "ms, expected <25ms"))
          (is (some? table-spec) "Table spec should be created")
          (done))))))

(deftest validate-edn-attachment-parsing
  "Tests EDN attachment structure validation."
  (testing "Chart attachment validation"
    (let [valid-chart-attachment {:chart-type "bar"
                                  :x-labels ["A" "B" "C"]
                                  :series [{:name "Test" :values [1 2 3]}]}]
      (is (renderer/validate-edn-attachments valid-chart-attachment)
          "Valid chart attachments should pass validation")))

  (testing "Table attachment validation"
    (let [valid-table-attachment {:table [["Name" "Value"] ["A" "10"] ["B" "20"]]}]
      (is (renderer/validate-table-attachments valid-table-attachment)
          "Valid table attachments should pass validation")))

  (testing "Invalid attachment structures"
    (is (not (renderer/validate-edn-attachments {}))
        "Empty attachments should fail validation")
    (is (not (renderer/validate-table-attachments {:table []}))
        "Empty table should fail validation")))

(deftest validate-edge-cases
  "Tests edge cases in chart and table rendering."
  (testing "Large dataset optimization"
    (let [large-data (vec (repeatedly 1000 #(hash-map :x (rand-int 100) :y (rand-int 100))))
          optimized (renderer/optimize-data-for-device large-data :low)]
      (is (<= (count optimized) 50) "Large datasets should be optimized for low-performance devices")))

  (testing "Empty data handling"
    (is (nil? (renderer/create-chart-spec [] "bar"))
        "Empty data should return nil chart spec"))

  (testing "Statistical calculations with edge cases"
    (is (= 0 (renderer/calculate-mean [])) "Mean of empty sequence should be 0")
    (is (= 0 (renderer/calculate-variance [5])) "Variance of single value should be 0")
    (is (= 0 (renderer/calculate-correlation [] [])) "Correlation of empty sequences should be 0")))

(defn run-chart-validation-tests
  "Runs all chart validation tests and returns performance metrics."
  []
  (js/console.log "Running Phase 8 Chart Validation Tests...")
  (let [start-time (js/Date.now)]
    ;; Run test suite
    (cljs.test/run-tests 'pok.chart-validation-test)
    (let [total-time (- (js/Date.now) start-time)]
      (js/console.log "Chart validation tests completed in" total-time "ms")
      {:total-time total-time
       :status "complete"
       :tests-run 4})))
