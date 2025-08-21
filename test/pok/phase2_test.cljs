(ns pok.phase2-test
  "Focused tests for Phase 2 core functionality"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pok.curriculum :as curriculum]
            [pok.renderer :as renderer]))

;; Test data
(def test-lesson-data
  {:id "1-1"
   :name "Introduction to Statistics"
   :content "What can we learn from data?"
   :questions [{:id "q1" :text "What is statistics?"}]})

(def test-chart-data
  {:type :scatter
   :data [{:x 1 :y 2} {:x 2 :y 4} {:x 3 :y 6} {:x 4 :y 8} {:x 5 :y 10}]
   :x-field :x
   :y-field :y
   :title "Perfect Linear Relationship"})

;; Core functionality tests
(deftest test-device-performance
  (testing "Device performance detection"
    (let [performance (curriculum/detect-device-performance)]
      (is (contains? #{:low :medium :high} performance)))))

(deftest test-lesson-validation
  (testing "Valid lesson data"
    (is (true? (curriculum/validate-lesson-data test-lesson-data))))
  (testing "Invalid lesson data"
    (is (false? (curriculum/validate-lesson-data {:id "test"})))))

(deftest test-chart-validation
  (testing "Valid chart data"
    (is (true? (curriculum/validate-chart-data test-chart-data))))
  (testing "Invalid chart data"
    (is (false? (curriculum/validate-chart-data {:type :invalid :data "not-vector"})))))

(deftest test-statistical-functions
  (testing "Mean calculation"
    (is (= 3.0 (renderer/calculate-mean [1 2 3 4 5])))
    (is (= 0 (renderer/calculate-mean []))))
  
  (testing "Linear regression - perfect fit y = 2x"
    (let [regression (renderer/calculate-linear-regression [1 2 3 4 5] [2 4 6 8 10])]
      (is (= 2.0 (:slope regression)))
      (is (= 0.0 (:intercept regression)))
      (is (= 1.0 (:r-squared regression)))))
  
  (testing "AP Statistics precision"
    (let [regression (renderer/calculate-linear-regression [1.234 2.567] [2.456 5.123])]
      ;; Should be rounded to 3 decimal places
      (is (= (:slope regression) (/ (Math/round (* (:slope regression) 1000)) 1000))))))

(deftest test-performance-optimization
  (testing "Data optimization for low-power devices"
    (let [large-data (vec (range 1000))
          optimized (renderer/optimize-data-for-device large-data :low)]
      (is (<= (count optimized) 50))
      (is (>= (count optimized) 1)))))

(deftest test-chart-specs
  (testing "Chart specification generation"
    (let [spec (renderer/create-chart-spec test-chart-data)]
      (is (map? spec))
      (is (contains? spec :$schema))
      (is (= (:data test-chart-data) (get-in spec [:data :values]))))))

;; Run tests
(defn run-phase2-tests []
  (cljs.test/run-tests))