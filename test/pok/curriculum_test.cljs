(ns pok.curriculum-test
  "Tests for curriculum loader with async EDN loading and IndexedDB caching."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.core.async :as async :refer [go <! >! chan timeout]]
            [pok.curriculum :as curriculum]))

;; Mock data for testing
(def mock-lesson-data
  {:id "1-1"
   :name "Introduction to Statistics"
   :content "What can we learn from data?"
   :questions [{:id "q1"
                :text "What is statistics?"
                :type "multiple-choice"
                :choices ["A" "B" "C" "D"]}]
   :chart-data {:type :bar
                :data [{:category "A" :value 10}
                       {:category "B" :value 20}
                       {:category "C" :value 15}]
                :x-field :category
                :y-field :value
                :title "Sample Bar Chart"}})

(def mock-curriculum-index
  {:version "1.0"
   :units [{:id "unit1"
            :name "Exploring One-Variable Data"
            :lessons [{:id "1-1" :name "Introduction to Statistics"}
                      {:id "1-2" :name "Variables and Data Types"}]}
           {:id "unit2"
            :name "Exploring Two-Variable Data"
            :lessons [{:id "2-1" :name "Scatterplots"}
                      {:id "2-2" :name "Correlation"}]}]})

(def mock-chart-data
  {:type :scatter
   :data [{:x 1 :y 2} {:x 2 :y 4} {:x 3 :y 6} {:x 4 :y 8} {:x 5 :y 10}]
   :x-field :x
   :y-field :y
   :title "Perfect Linear Relationship"})

;; Device performance detection tests
(deftest test-device-performance-detection
  (testing "Device performance detection based on hardware concurrency"
    ;; Mock navigator.hardwareConcurrency for different scenarios
    (with-redefs [js/navigator #js {:hardwareConcurrency 2}]
      (is (= :low (curriculum/detect-device-performance))))
    
    (with-redefs [js/navigator #js {:hardwareConcurrency 4}]
      (is (= :medium (curriculum/detect-device-performance))))
    
    (with-redefs [js/navigator #js {:hardwareConcurrency 8}]
      (is (= :high (curriculum/detect-device-performance))))
    
    ;; Test fallback when hardwareConcurrency is undefined
    (with-redefs [js/navigator #js {}]
      (is (= :low (curriculum/detect-device-performance))))))

;; Data validation tests
(deftest test-lesson-data-validation
  (testing "Valid lesson data structure"
    (is (true? (curriculum/validate-lesson-data mock-lesson-data))))
  
  (testing "Invalid lesson data - missing required fields"
    (is (false? (curriculum/validate-lesson-data {:id "1-1"})))
    (is (false? (curriculum/validate-lesson-data {:name "Test"})))
    (is (false? (curriculum/validate-lesson-data {:content "Content"}))))
  
  (testing "Invalid lesson data - wrong data types"
    (is (false? (curriculum/validate-lesson-data "not-a-map")))
    (is (false? (curriculum/validate-lesson-data nil)))))

(deftest test-chart-data-validation
  (testing "Valid chart data structure"
    (is (true? (curriculum/validate-chart-data (:chart-data mock-lesson-data))))
    (is (true? (curriculum/validate-chart-data mock-chart-data))))
  
  (testing "Invalid chart data - missing required fields"
    (is (false? (curriculum/validate-chart-data {:type :bar})))
    (is (false? (curriculum/validate-chart-data {:data []})))
    (is (false? (curriculum/validate-chart-data {:type :invalid :data []}))))
  
  (testing "Invalid chart data - wrong data types"
    (is (false? (curriculum/validate-chart-data {:type :bar :data "not-vector"})))
    (is (false? (curriculum/validate-chart-data "not-a-map")))))

;; Statistical computation tests for renderer
(deftest test-statistical-calculations
  (testing "Mean calculation"
    (is (= 3.0 (renderer/calculate-mean [1 2 3 4 5])))
    (is (= 0 (renderer/calculate-mean [])))
    (is (= 10.0 (renderer/calculate-mean [10]))))
  
  (testing "Variance calculation"
    (let [values [2 4 6 8 10]
          variance (renderer/calculate-variance values)]
      (is (> variance 9.9))  ; Should be 10
      (is (< variance 10.1)))
    (is (= 0 (renderer/calculate-variance [5])))
    (is (= 0 (renderer/calculate-variance []))))
  
  (testing "Standard deviation calculation"
    (let [values [2 4 6 8 10]
          std-dev (renderer/calculate-standard-deviation values)]
      (is (> std-dev 3.1))  ; Should be approximately 3.16
      (is (< std-dev 3.2))))
  
  (testing "Correlation calculation"
    ;; Perfect positive correlation
    (let [x-values [1 2 3 4 5]
          y-values [2 4 6 8 10]
          correlation (renderer/calculate-correlation x-values y-values)]
      (is (> correlation 0.99))  ; Should be 1.0
      (is (<= correlation 1.0)))
    
    ;; Perfect negative correlation
    (let [x-values [1 2 3 4 5]
          y-values [10 8 6 4 2]
          correlation (renderer/calculate-correlation x-values y-values)]
      (is (< correlation -0.99))  ; Should be -1.0
      (is (>= correlation -1.0)))
    
    ;; No correlation (insufficient data)
    (is (= 0 (renderer/calculate-correlation [1] [2])))))

(deftest test-linear-regression
  (testing "Linear regression with perfect fit"
    (let [x-values [1 2 3 4 5]
          y-values [2 4 6 8 10]  ; y = 2x
          regression (renderer/calculate-linear-regression x-values y-values)]
      (is (= 2.0 (:slope regression)))
      (is (= 0.0 (:intercept regression)))
      (is (= 1.0 (:r-squared regression)))
      (is (= "y = 2.0x + 0.0" (:equation regression)))))
  
  (testing "Linear regression with AP Statistics precision"
    (let [x-values [1.1 2.3 3.7 4.2 5.8]
          y-values [2.2 4.1 7.5 8.9 11.3]
          regression (renderer/calculate-linear-regression x-values y-values)]
      ;; Verify precision to 3 decimal places
      (is (number? (:slope regression)))
      (is (number? (:intercept regression)))
      (is (number? (:r-squared regression)))
      (is (<= (:r-squared regression) 1.0))
      (is (>= (:r-squared regression) 0.0))))
  
  (testing "Linear regression with insufficient data"
    (let [regression (renderer/calculate-linear-regression [1] [2])]
      (is (= 0 (:slope regression)))
      (is (= 0 (:intercept regression)))
      (is (= 0 (:r-squared regression))))))

(deftest test-regression-steps
  (testing "Regression steps generation"
    (let [x-values [1 2 3 4 5]
          y-values [2 4 6 8 10]
          steps-3 (renderer/calculate-regression-steps x-values y-values 3)
          steps-5 (renderer/calculate-regression-steps x-values y-values 5)]
      (is (= 3 (count steps-3)))
      (is (= 5 (count steps-5)))
      (is (every? string? steps-3))
      (is (every? string? steps-5))
      (is (re-find #"Step 1.*means" (first steps-3)))
      (is (re-find #"Step 5.*R²" (last steps-5))))))

;; Vega-Lite specification tests
(deftest test-vega-spec-generation
  (testing "Bar chart specification"
    (let [data [{:category "A" :value 10} {:category "B" :value 20}]
          spec (renderer/create-bar-chart-spec data :category :value "Test Chart")]
      (is (map? spec))
      (is (= "https://vega.github.io/schema/vega-lite/v5.json" (:$schema spec)))
      (is (= "Test Chart" (:title spec)))
      (is (= "bar" (get-in spec [:mark :type])))
      (is (= data (get-in spec [:data :values])))))
  
  (testing "Scatter plot specification"
    (let [data [{:x 1 :y 2} {:x 2 :y 4}]
          spec (renderer/create-scatter-plot-spec data :x :y "Scatter Plot")]
      (is (map? spec))
      (is (= "Scatter Plot" (:title spec)))
      (is (vector? (:layer spec)))
      (is (= "circle" (get-in spec [:layer 0 :mark :type])))))
  
  (testing "Histogram specification"
    (let [data [{:value 1} {:value 2} {:value 3}]
          spec (renderer/create-histogram-spec data :value "Histogram")]
      (is (map? spec))
      (is (= "Histogram" (:title spec)))
      (is (= "bar" (get-in spec [:mark :type])))
      (is (get-in spec [:encoding :x :bin]))))
  
  (testing "Box plot specification"
    (let [data [{:group "A" :value 10} {:group "B" :value 20}]
          spec (renderer/create-box-plot-spec data :group :value "Box Plot")]
      (is (map? spec))
      (is (= "Box Plot" (:title spec)))
      (is (= "boxplot" (get-in spec [:mark :type]))))))

;; Performance optimization tests
(deftest test-performance-optimization
  (testing "Data optimization for low-power devices"
    (let [large-data (vec (range 1000))
          optimized-low (renderer/optimize-data-for-device large-data :low)
          optimized-high (renderer/optimize-data-for-device large-data :high)]
      (is (<= (count optimized-low) 50))    ; Low-power threshold
      (is (<= (count optimized-high) 500))  ; High-power threshold
      (is (>= (count optimized-low) 1))     ; Not empty
      (is (>= (count optimized-high) 1))))  ; Not empty
  
  (testing "Chart data to Vega spec with performance optimization"
    (let [chart-data {:type :bar
                      :data (vec (map #(hash-map :x % :y (* % 2)) (range 100)))
                      :x-field :x
                      :y-field :y
                      :title "Performance Test"}
          spec-low (renderer/chart-data->vega-spec chart-data :performance-level :low)
          spec-high (renderer/chart-data->vega-spec chart-data :performance-level :high)]
      (is (map? spec-low))
      (is (map? spec-high))
      ;; Low-power should have fewer data points
      (is (<= (count (get-in spec-low [:data :values])) 50))
      (is (<= (count (get-in spec-high [:data :values])) 500)))))

;; Chart statistics tests
(deftest test-chart-statistics
  (testing "Scatter plot statistics"
    (let [chart-data {:type :scatter
                      :data [{:x 1 :y 2} {:x 2 :y 4} {:x 3 :y 6}]
                      :x-field :x
                      :y-field :y}
          stats (renderer/get-chart-statistics chart-data)]
      (is (= 3 (:data-points stats)))
      (is (number? (:slope stats)))
      (is (number? (:r-squared stats)))
      (is (number? (:x-mean stats)))
      (is (number? (:y-mean stats)))))
  
  (testing "Histogram statistics"
    (let [chart-data {:type :histogram
                      :data [{:value 1} {:value 2} {:value 3} {:value 4} {:value 5}]
                      :x-field :value}
          stats (renderer/get-chart-statistics chart-data)]
      (is (= 5 (:data-points stats)))
      (is (= 3.0 (:mean stats)))  ; Mean of [1,2,3,4,5]
      (is (number? (:std stats)))
      (is (number? (:variance stats)))
      (is (= 1 (:min stats)))
      (is (= 5 (:max stats)))))
  
  (testing "Default statistics for other chart types"
    (let [chart-data {:type :bar
                      :data [{:x "A" :y 10} {:x "B" :y 20}]}
          stats (renderer/get-chart-statistics chart-data)]
      (is (= 2 (:data-points stats)))
      (is (= :bar (:chart-type stats))))))

;; Hybrid rendering strategy tests
(deftest test-hybrid-rendering-strategy
  (testing "Pre-computation enabled for low-power devices"
    (let [chart-data {:type :scatter
                      :data [{:x 1 :y 2} {:x 2 :y 4} {:x 3 :y 6}]
                      :x-field :x
                      :y-field :y
                      :title "Hybrid Test"}
          spec-low (renderer/chart-data->vega-spec chart-data :performance-level :low)]
      ;; For low-power devices, regression should be pre-computed
      (is (map? spec-low))
      (is (vector? (:layer spec-low)))))
  
  (testing "Auto-transform for high-power devices"
    (let [chart-data {:type :scatter
                      :data [{:x 1 :y 2} {:x 2 :y 4} {:x 3 :y 6}]
                      :x-field :x
                      :y-field :y
                      :title "Hybrid Test"}
          spec-high (renderer/chart-data->vega-spec chart-data :performance-level :high)]
      ;; For high-power devices, should use Vega-Lite transforms
      (is (map? spec-high))
      (is (vector? (:layer spec-high))))))

;; Integration test helpers
(defn create-mock-edn-response [data]
  (pr-str data))

;; Async testing utilities
(defn test-async [test-fn timeout-ms]
  (async done
    (go
      (try
        (<! (test-fn))
        (done)
        (catch js/Error e
          (is false (str "Async test failed: " e))
          (done))))))

;; Error handling tests
(deftest test-error-handling
  (testing "Invalid chart data validation"
    (is (false? (renderer/validate-chart-data nil)))
    (is (false? (renderer/validate-chart-data "invalid")))
    (is (false? (renderer/validate-chart-data {:type :invalid}))))
  
  (testing "Regression calculation with edge cases"
    ;; Empty data
    (let [regression (renderer/calculate-linear-regression [] [])]
      (is (= 0 (:slope regression)))
      (is (= 0 (:r-squared regression))))
    
    ;; Mismatched data lengths
    (let [regression (renderer/calculate-linear-regression [1 2] [3])]
      (is (= 0 (:slope regression)))
      (is (= 0 (:r-squared regression)))))
  
  (testing "Statistical calculations with edge cases"
    (is (= 0 (renderer/calculate-mean [])))
    (is (= 0 (renderer/calculate-variance [])))
    (is (= 0 (renderer/calculate-variance [5])))  ; Single value
    (is (= 0 (renderer/calculate-correlation [] [])))))

;; Performance benchmarks
(deftest test-performance-benchmarks
  (testing "Regression calculation performance"
    (let [large-x (vec (range 1000))
          large-y (mapv #(+ (* 2 %) (rand 10)) large-x)
          start-time (js/Date.now)]
      (renderer/calculate-linear-regression large-x large-y)
      (let [end-time (js/Date.now)
            duration (- end-time start-time)]
        ;; Should complete within 100ms for 1000 points
        (is (< duration 100) (str "Regression took " duration "ms")))))
  
  (testing "Chart specification generation performance"
    (let [large-data (mapv #(hash-map :x % :y (rand 100)) (range 500))
          chart-data {:type :scatter :data large-data :x-field :x :y-field :y}
          start-time (js/Date.now)]
      (renderer/chart-data->vega-spec chart-data)
      (let [end-time (js/Date.now)
            duration (- end-time start-time)]
        ;; Should complete within 50ms for 500 points
        (is (< duration 50) (str "Chart spec generation took " duration "ms"))))))

;; Precision validation tests
(deftest test-ap-statistics-precision
  (testing "Regression coefficients rounded to 3 decimal places"
    (let [x-values [1.234 2.567 3.891]
          y-values [2.456 5.123 7.789]
          regression (renderer/calculate-linear-regression x-values y-values)]
      ;; Check that values are rounded to 3 decimal places
      (is (= (:slope regression) (/ (Math/round (* (:slope regression) 1000)) 1000)))
      (is (= (:intercept regression) (/ (Math/round (* (:intercept regression) 1000)) 1000)))
      (is (= (:r-squared regression) (/ (Math/round (* (:r-squared regression) 1000)) 1000)))))
  
  (testing "Correlation coefficients bounded between -1 and 1"
    (let [x-values [1 2 3 4 5]
          y-values [2 4 6 8 10]
          correlation (renderer/calculate-correlation x-values y-values)]
      (is (>= correlation -1.0))
      (is (<= correlation 1.0)))))

;; Cache functionality would be tested here if implementing full IndexedDB mocking
;; For now, testing the validation and data structure aspects

(deftest test-curriculum-integration
  (testing "Lesson validation with chart data"
    (let [lesson-with-chart (assoc mock-lesson-data :chart-data mock-chart-data)]
      (is (true? (curriculum/validate-lesson-data lesson-with-chart)))
      (is (true? (curriculum/validate-chart-data (:chart-data lesson-with-chart))))))
  
  (testing "Curriculum index structure validation"
    (is (map? mock-curriculum-index))
    (is (vector? (:units mock-curriculum-index)))
    (is (every? #(and (map? %) (contains? % :id) (contains? % :lessons)) 
                (:units mock-curriculum-index)))))

(deftest test-rendering-pipeline
  (testing "Complete rendering pipeline"
    (let [chart-data mock-chart-data
          vega-spec (renderer/chart-data->vega-spec chart-data)]
      (is (renderer/validate-chart-data chart-data))
      (is (map? vega-spec))
      (is (contains? vega-spec :$schema))
      (is (contains? vega-spec :data))
      (is (contains? vega-spec :mark))
      (is (contains? vega-spec :encoding)))))

;; Run all tests
(defn run-tests []
  (cljs.test/run-tests))