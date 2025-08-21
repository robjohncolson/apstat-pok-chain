(ns pok.renderer-test
  "Tests for Vega-Lite renderer with hybrid pre-computation."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pok.renderer :as renderer]
            [clojure.core.async :as async :refer [go <! >! chan]]))

;; Test data sets
(def perfect-linear-data
  [{:x 1 :y 2} {:x 2 :y 4} {:x 3 :y 6} {:x 4 :y 8} {:x 5 :y 10}])

(def noisy-linear-data
  [{:x 1 :y 2.1} {:x 2 :y 3.9} {:x 3 :y 6.2} {:x 4 :y 7.8} {:x 5 :y 10.1}])

(def categorical-data
  [{:category "A" :value 10}
   {:category "B" :value 25}
   {:category "C" :value 15}
   {:category "D" :value 30}])

(def histogram-data
  [{:score 85} {:score 92} {:score 78} {:score 95} {:score 88}
   {:score 91} {:score 76} {:score 89} {:score 94} {:score 82}])

;; Statistical calculation tests
(deftest test-mean-calculation
  (testing "Mean of simple dataset"
    (is (= 3.0 (renderer/calculate-mean [1 2 3 4 5]))))
  
  (testing "Mean of empty dataset"
    (is (= 0 (renderer/calculate-mean []))))
  
  (testing "Mean of single value"
    (is (= 7.5 (renderer/calculate-mean [7.5]))))
  
  (testing "Mean with decimals"
    (let [values [1.5 2.5 3.5 4.5]
          result (renderer/calculate-mean values)]
      (is (> result 2.9))
      (is (< result 3.1)))))

(deftest test-variance-calculation
  (testing "Variance of dataset with known result"
    (let [values [2 4 6 8 10]
          variance (renderer/calculate-variance values)]
      (is (> variance 9.5))
      (is (< variance 10.5))))
  
  (testing "Variance of empty dataset"
    (is (= 0 (renderer/calculate-variance []))))
  
  (testing "Variance of single value"
    (is (= 0 (renderer/calculate-variance [5]))))
  
  (testing "Variance of identical values"
    (is (= 0.0 (renderer/calculate-variance [3 3 3 3])))))

(deftest test-correlation-calculation
  (testing "Perfect positive correlation"
    (let [x-vals [1 2 3 4 5]
          y-vals [2 4 6 8 10]
          corr (renderer/calculate-correlation x-vals y-vals)]
      (is (> corr 0.99))
      (is (<= corr 1.0))))
  
  (testing "Perfect negative correlation"
    (let [x-vals [1 2 3 4 5]
          y-vals [10 8 6 4 2]
          corr (renderer/calculate-correlation x-vals y-vals)]
      (is (< corr -0.99))
      (is (>= corr -1.0))))
  
  (testing "No correlation (random)"
    (let [x-vals [1 2 3 4 5]
          y-vals [5 2 8 1 9]
          corr (renderer/calculate-correlation x-vals y-vals)]
      (is (>= corr -1.0))
      (is (<= corr 1.0))))
  
  (testing "Insufficient data"
    (is (= 0 (renderer/calculate-correlation [] [])))
    (is (= 0 (renderer/calculate-correlation [1] [2])))))

(deftest test-linear-regression
  (testing "Perfect linear fit y = 2x"
    (let [x-vals [1 2 3 4 5]
          y-vals [2 4 6 8 10]
          regression (renderer/calculate-linear-regression x-vals y-vals)]
      (is (= 2.0 (:slope regression)))
      (is (= 0.0 (:intercept regression)))
      (is (= 1.0 (:r-squared regression)))
      (is (= "y = 2.0x + 0.0" (:equation regression)))))
  
  (testing "Linear fit with intercept y = 2x + 1"
    (let [x-vals [1 2 3 4 5]
          y-vals [3 5 7 9 11]
          regression (renderer/calculate-linear-regression x-vals y-vals)]
      (is (= 2.0 (:slope regression)))
      (is (= 1.0 (:intercept regression)))
      (is (= 1.0 (:r-squared regression)))))
  
  (testing "AP Statistics precision (3 decimal places)"
    (let [x-vals [1.234 2.567 3.891 4.123]
          y-vals [2.456 5.123 7.789 8.456]
          regression (renderer/calculate-linear-regression x-vals y-vals)]
      ;; Check that all values are rounded to 3 decimal places
      (is (= (:slope regression) 
             (/ (Math/round (* (:slope regression) 1000)) 1000)))
      (is (= (:intercept regression) 
             (/ (Math/round (* (:intercept regression) 1000)) 1000)))
      (is (= (:r-squared regression) 
             (/ (Math/round (* (:r-squared regression) 1000)) 1000)))))
  
  (testing "Insufficient data handling"
    (let [regression (renderer/calculate-linear-regression [] [])]
      (is (= 0 (:slope regression)))
      (is (= 0 (:intercept regression)))
      (is (= 0 (:r-squared regression))))))

(deftest test-regression-steps
  (testing "Step generation for pedagogical tooltips"
    (let [x-vals [1 2 3 4 5]
          y-vals [2 4 6 8 10]
          steps-3 (renderer/calculate-regression-steps x-vals y-vals 3)
          steps-5 (renderer/calculate-regression-steps x-vals y-vals 5)]
      (is (= 3 (count steps-3)))
      (is (= 5 (count steps-5)))
      (is (every? string? steps-3))
      (is (re-find #"Step 1.*means" (first steps-3)))
      (is (re-find #"Step 2.*slope" (second steps-3)))
      (is (re-find #"Step 5.*R²" (last steps-5)))))
  
  (testing "Step content accuracy"
    (let [x-vals [1 2 3]
          y-vals [2 4 6]
          steps (renderer/calculate-regression-steps x-vals y-vals 5)]
      ;; Verify means are calculated correctly
      (is (re-find #"x̄ = 2" (first steps)))
      (is (re-find #"ȳ = 4" (first steps))))))

;; Vega-Lite specification tests
(deftest test-base-spec-creation
  (testing "Base specification structure"
    (let [spec (renderer/create-base-spec 400 300)]
      (is (= "https://vega.github.io/schema/vega-lite/v5.json" (:$schema spec)))
      (is (= 400 (:width spec)))
      (is (= 300 (:height spec)))
      (is (map? (:config spec))))))

(deftest test-bar-chart-spec
  (testing "Bar chart specification generation"
    (let [spec (renderer/create-bar-chart-spec 
                categorical-data :category :value "Test Bar Chart")]
      (is (= "Test Bar Chart" (:title spec)))
      (is (= categorical-data (get-in spec [:data :values])))
      (is (= "bar" (get-in spec [:mark :type])))
      (is (= :category (get-in spec [:encoding :x :field])))
      (is (= :value (get-in spec [:encoding :y :field])))
      (is (= "nominal" (get-in spec [:encoding :x :type])))
      (is (= "quantitative" (get-in spec [:encoding :y :type])))))
  
  (testing "Bar chart with custom color and dimensions"
    (let [spec (renderer/create-bar-chart-spec 
                categorical-data :category :value "Custom Chart"
                :color "red" :width 500 :height 400)]
      (is (= "red" (get-in spec [:mark :color])))
      (is (= 500 (:width spec)))
      (is (= 400 (:height spec))))))

(deftest test-scatter-plot-spec
  (testing "Basic scatter plot specification"
    (let [spec (renderer/create-scatter-plot-spec 
                perfect-linear-data :x :y "Scatter Plot")]
      (is (= "Scatter Plot" (:title spec)))
      (is (vector? (:layer spec)))
      (is (= "circle" (get-in spec [:layer 0 :mark :type])))
      (is (= :x (get-in spec [:layer 0 :encoding :x :field])))
      (is (= :y (get-in spec [:layer 0 :encoding :y :field])))))
  
  (testing "Scatter plot with regression line"
    (let [spec (renderer/create-scatter-plot-spec 
                perfect-linear-data :x :y "With Regression"
                :regression-line true)]
      (is (= 2 (count (:layer spec))))
      (is (= "line" (get-in spec [:layer 1 :mark :type])))
      (is (= "red" (get-in spec [:layer 1 :mark :color])))))
  
  (testing "Scatter plot with tooltip steps"
    (let [tooltip-steps ["Step 1: Calculate means" "Step 2: Find slope"]
          spec (renderer/create-scatter-plot-spec 
                perfect-linear-data :x :y "With Steps"
                :tooltip-steps tooltip-steps)]
      (is (vector? (get-in spec [:layer 0 :encoding :tooltip])))
      (is (> (count (get-in spec [:layer 0 :encoding :tooltip])) 2)))))

(deftest test-histogram-spec
  (testing "Histogram specification generation"
    (let [spec (renderer/create-histogram-spec 
                histogram-data :score "Score Distribution")]
      (is (= "Score Distribution" (:title spec)))
      (is (= histogram-data (get-in spec [:data :values])))
      (is (= "bar" (get-in spec [:mark :type])))
      (is (= :score (get-in spec [:encoding :x :field])))
      (is (map? (get-in spec [:encoding :x :bin])))
      (is (= "count" (get-in spec [:encoding :y :aggregate])))))
  
  (testing "Histogram with custom bins"
    (let [spec (renderer/create-histogram-spec 
                histogram-data :score "Custom Bins" :bins 15)]
      (is (= 15 (get-in spec [:encoding :x :bin :maxbins]))))))

(deftest test-box-plot-spec
  (testing "Box plot specification generation"
    (let [grouped-data [{:group "A" :value 10} {:group "B" :value 20}]
          spec (renderer/create-box-plot-spec 
                grouped-data :group :value "Box Plot")]
      (is (= "Box Plot" (:title spec)))
      (is (= "boxplot" (get-in spec [:mark :type])))
      (is (= "min-max" (get-in spec [:mark :extent])))
      (is (= :group (get-in spec [:encoding :x :field])))
      (is (= :value (get-in spec [:encoding :y :field]))))))

;; Performance optimization tests
(deftest test-data-optimization
  (testing "Data optimization for low-power devices"
    (let [large-data (vec (range 1000))
          optimized (renderer/optimize-data-for-device large-data :low)]
      (is (<= (count optimized) 50))
      (is (>= (count optimized) 1))))
  
  (testing "Data optimization for medium-power devices"
    (let [large-data (vec (range 1000))
          optimized (renderer/optimize-data-for-device large-data :medium)]
      (is (<= (count optimized) 200))
      (is (>= (count optimized) 1))))
  
  (testing "Data optimization for high-power devices"
    (let [large-data (vec (range 600))
          optimized (renderer/optimize-data-for-device large-data :high)]
      (is (<= (count optimized) 500))
      (is (>= (count optimized) 1))))
  
  (testing "No optimization needed for small datasets"
    (let [small-data (vec (range 20))
          optimized-low (renderer/optimize-data-for-device small-data :low)
          optimized-high (renderer/optimize-data-for-device small-data :high)]
      (is (= small-data optimized-low))
      (is (= small-data optimized-high)))))

(deftest test-precomputed-regression-layer
  (testing "Pre-computed regression layer generation"
    (let [x-vals [1 2 3 4 5]
          y-vals [2 4 6 8 10]
          layer (renderer/precompute-regression-layer x-vals y-vals :x :y)]
      (is (= "line" (get-in layer [:mark :type])))
      (is (= "red" (get-in layer [:mark :color])))
      (is (vector? (get-in layer [:data :values])))
      (is (= 2 (count (get-in layer [:data :values]))))
      ;; Check that line points are correct for y = 2x
      (let [points (get-in layer [:data :values])]
        (is (= 2 (:y (first points))))   ; y = 2*1
        (is (= 10 (:y (second points))))))))  ; y = 2*5

;; Hybrid rendering strategy tests
(deftest test-chart-data-to-vega-spec
  (testing "Bar chart conversion"
    (let [chart-data {:type :bar
                      :data categorical-data
                      :x-field :category
                      :y-field :value
                      :title "Bar Chart Test"}
          spec (renderer/chart-data->vega-spec chart-data)]
      (is (map? spec))
      (is (= "Bar Chart Test" (:title spec)))
      (is (= "bar" (get-in spec [:mark :type])))))
  
  (testing "Scatter plot conversion with performance optimization"
    (let [chart-data {:type :scatter
                      :data perfect-linear-data
                      :x-field :x
                      :y-field :y
                      :title "Scatter Test"}
          spec-low (renderer/chart-data->vega-spec chart-data :performance-level :low)
          spec-high (renderer/chart-data->vega-spec chart-data :performance-level :high)]
      (is (map? spec-low))
      (is (map? spec-high))
      (is (= "Scatter Test" (:title spec-low)))
      (is (= "Scatter Test" (:title spec-high)))
      (is (vector? (:layer spec-low)))
      (is (vector? (:layer spec-high)))))
  
  (testing "Histogram conversion"
    (let [chart-data {:type :histogram
                      :data histogram-data
                      :x-field :score
                      :title "Histogram Test"}
          spec (renderer/chart-data->vega-spec chart-data)]
      (is (map? spec))
      (is (= "Histogram Test" (:title spec)))
      (is (map? (get-in spec [:encoding :x :bin])))))
  
  (testing "Box plot conversion"
    (let [chart-data {:type :box
                      :data [{:group "A" :value 10} {:group "B" :value 20}]
                      :x-field :group
                      :y-field :value
                      :title "Box Plot Test"}
          spec (renderer/chart-data->vega-spec chart-data)]
      (is (map? spec))
      (is (= "Box Plot Test" (:title spec)))
      (is (= "boxplot" (get-in spec [:mark :type])))))
  
  (testing "Unknown chart type fallback"
    (let [chart-data {:type :unknown
                      :data categorical-data
                      :x-field :category
                      :y-field :value
                      :title "Unknown Type"}
          spec (renderer/chart-data->vega-spec chart-data)]
      ;; Should fallback to bar chart
      (is (= "bar" (get-in spec [:mark :type]))))))

;; Chart statistics tests
(deftest test-chart-statistics
  (testing "Scatter plot statistics"
    (let [chart-data {:type :scatter
                      :data perfect-linear-data
                      :x-field :x
                      :y-field :y}
          stats (renderer/get-chart-statistics chart-data)]
      (is (= 5 (:data-points stats)))
      (is (= 2.0 (:slope stats)))
      (is (= 0.0 (:intercept stats)))
      (is (= 1.0 (:r-squared stats)))
      (is (= 3.0 (:x-mean stats)))
      (is (= 6.0 (:y-mean stats)))))
  
  (testing "Histogram statistics"
    (let [chart-data {:type :histogram
                      :data histogram-data
                      :x-field :score}
          stats (renderer/get-chart-statistics chart-data)]
      (is (= 10 (:data-points stats)))
      (is (number? (:mean stats)))
      (is (number? (:std stats)))
      (is (number? (:variance stats)))
      (is (number? (:min stats)))
      (is (number? (:max stats)))))
  
  (testing "Default statistics for other chart types"
    (let [chart-data {:type :bar
                      :data categorical-data}
          stats (renderer/get-chart-statistics chart-data)]
      (is (= 4 (:data-points stats)))
      (is (= :bar (:chart-type stats))))))

;; Validation tests
(deftest test-chart-data-validation
  (testing "Valid chart data structures"
    (let [valid-chart {:type :bar
                       :data categorical-data
                       :x-field :category
                       :y-field :value}]
      (is (true? (renderer/validate-chart-data valid-chart))))
    
    (let [valid-scatter {:type :scatter
                         :data perfect-linear-data
                         :x-field :x
                         :y-field :y}]
      (is (true? (renderer/validate-chart-data valid-scatter)))))
  
  (testing "Invalid chart data structures"
    ;; Missing required fields
    (is (false? (renderer/validate-chart-data {:type :bar})))
    (is (false? (renderer/validate-chart-data {:data []})))
    
    ;; Invalid types
    (is (false? (renderer/validate-chart-data {:type :invalid :data []})))
    (is (false? (renderer/validate-chart-data "not-a-map")))
    (is (false? (renderer/validate-chart-data nil)))
    
    ;; Empty data
    (is (false? (renderer/validate-chart-data {:type :bar :data []})))
    
    ;; Wrong data format
    (is (false? (renderer/validate-chart-data {:type :bar :data "not-vector"})))))

;; Performance benchmarks
(deftest test-performance-benchmarks
  (testing "Regression calculation performance with large dataset"
    (let [large-x (vec (range 1000))
          large-y (mapv #(+ (* 2 %) (rand 10)) large-x)
          start-time (js/Date.now)]
      (renderer/calculate-linear-regression large-x large-y)
      (let [duration (- (js/Date.now) start-time)]
        ;; Should complete within 100ms
        (is (< duration 100) (str "Large regression took " duration "ms")))))
  
  (testing "Chart specification generation performance"
    (let [large-data (mapv #(hash-map :x % :y (rand 100)) (range 500))
          chart-data {:type :scatter :data large-data :x-field :x :y-field :y}
          start-time (js/Date.now)]
      (renderer/chart-data->vega-spec chart-data)
      (let [duration (- (js/Date.now) start-time)]
        ;; Should complete within 50ms
        (is (< duration 50) (str "Large chart spec took " duration "ms")))))
  
  (testing "Data optimization performance"
    (let [huge-data (vec (range 10000))
          start-time (js/Date.now)]
      (renderer/optimize-data-for-device huge-data :low)
      (let [duration (- (js/Date.now) start-time)]
        ;; Should complete within 20ms
        (is (< duration 20) (str "Data optimization took " duration "ms"))))))

;; Edge case tests
(deftest test-edge-cases
  (testing "Regression with identical x-values"
    (let [x-vals [2 2 2 2]
          y-vals [1 2 3 4]
          regression (renderer/calculate-linear-regression x-vals y-vals)]
      ;; Should handle division by zero gracefully
      (is (= 0 (:slope regression)))
      (is (= 0 (:r-squared regression)))))
  
  (testing "Regression with identical y-values"
    (let [x-vals [1 2 3 4]
          y-vals [5 5 5 5]
          regression (renderer/calculate-linear-regression x-vals y-vals)]
      (is (= 0 (:slope regression)))
      (is (= 5.0 (:intercept regression)))))
  
  (testing "Chart with single data point"
    (let [chart-data {:type :scatter
                      :data [{:x 1 :y 2}]
                      :x-field :x
                      :y-field :y}]
      ;; Should not crash validation
      (is (true? (renderer/validate-chart-data chart-data)))
      ;; Statistics should handle single point
      (let [stats (renderer/get-chart-statistics chart-data)]
        (is (= 1 (:data-points stats))))))
  
  (testing "Very large numbers in calculations"
    (let [large-x [1e6 2e6 3e6]
          large-y [2e6 4e6 6e6]
          regression (renderer/calculate-linear-regression large-x large-y)]
      (is (number? (:slope regression)))
      (is (number? (:intercept regression)))
      (is (>= (:r-squared regression) 0))
      (is (<= (:r-squared regression) 1)))))

;; Integration tests
(deftest test-public-api
  (testing "Public create-chart-spec function"
    (let [chart-data {:type :bar
                      :data categorical-data
                      :x-field :category
                      :y-field :value}
          spec (renderer/create-chart-spec chart-data)]
      (is (map? spec))
      (is (contains? spec :$schema))
      (is (contains? spec :data))))
  
  (testing "Chart spec with custom options"
    (let [chart-data {:type :scatter
                      :data perfect-linear-data
                      :x-field :x
                      :y-field :y}
          spec (renderer/create-chart-spec chart-data 
                                           :performance-level :high
                                           :width 600
                                           :height 400)]
      (is (= 600 (:width spec)))
      (is (= 400 (:height spec))))))

;; Run all renderer tests
(defn run-tests []
  (cljs.test/run-tests))