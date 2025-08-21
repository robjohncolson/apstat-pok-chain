(ns pok.renderer
  "Vega-Lite chart renderer with hybrid pre-computation for performance.
   Implements adaptive rendering based on device capabilities with statistical pre-computation."
  (:require [clojure.core.async :as async :refer [go >! chan]]))

;; Chart type constants
(def chart-types
  #{:bar :scatter :histogram :line :box})

;; Performance thresholds
(def performance-thresholds
  {:low {:max-data-points 50
         :use-precompute true}
   :medium {:max-data-points 200
            :use-precompute false}
   :high {:max-data-points 500
          :use-precompute false}})

;; Statistical computation functions
(defn calculate-mean
  "Calculates mean of numeric sequence with precision handling."
  [values]
  (if (empty? values)
    0
    (/ (reduce + values) (count values))))

(defn calculate-variance
  "Calculates sample variance with n-1 denominator."
  [values]
  (if (< (count values) 2)
    0
    (let [mean (calculate-mean values)
          squared-diffs (map #(* (- % mean) (- % mean)) values)]
      (/ (reduce + squared-diffs) (dec (count values))))))

(defn calculate-standard-deviation
  "Calculates sample standard deviation."
  [values]
  (Math/sqrt (calculate-variance values)))

(defn calculate-correlation
  "Calculates Pearson correlation coefficient between two sequences."
  [x-values y-values]
  (if (or (< (count x-values) 2) (not= (count x-values) (count y-values)))
    0
    (let [x-mean (calculate-mean x-values)
          y-mean (calculate-mean y-values)
          numerator (reduce + (map #(* (- %1 x-mean) (- %2 y-mean)) x-values y-values))
          x-variance (reduce + (map #(* (- % x-mean) (- % x-mean)) x-values))
          y-variance (reduce + (map #(* (- % y-mean) (- % y-mean)) y-values))
          denominator (Math/sqrt (* x-variance y-variance))]
      (if (zero? denominator)
        0
        (/ numerator denominator)))))

(defn calculate-linear-regression
  "Calculates linear regression parameters with AP Statistics precision (3 decimals)."
  [x-values y-values]
  (if (or (< (count x-values) 2) (not= (count x-values) (count y-values)))
    {:slope 0 :intercept 0 :r-squared 0 :equation "y = 0"}
    (let [n (count x-values)
          x-mean (calculate-mean x-values)
          y-mean (calculate-mean y-values)
          numerator (reduce + (map #(* (- %1 x-mean) (- %2 y-mean)) x-values y-values))
          denominator (reduce + (map #(* (- % x-mean) (- % x-mean)) x-values))
          slope (if (zero? denominator) 0 (/ numerator denominator))
          intercept (- y-mean (* slope x-mean))
          r (calculate-correlation x-values y-values)
          r-squared (* r r)
          ;; Round to AP Statistics precision (3 decimal places)
          slope-rounded (/ (Math/round (* slope 1000)) 1000)
          intercept-rounded (/ (Math/round (* intercept 1000)) 1000)
          r-squared-rounded (/ (Math/round (* r-squared 1000)) 1000)]
      {:slope slope-rounded
       :intercept intercept-rounded
       :r-squared r-squared-rounded
       :correlation (/ (Math/round (* r 1000)) 1000)
       :equation (str "y = " slope-rounded "x + " intercept-rounded)
       :n n})))

(defn calculate-regression-steps
  "Generates step-by-step regression calculation for pedagogical tooltips."
  [x-values y-values max-steps]
  (let [x-mean (calculate-mean x-values)
        y-mean (calculate-mean y-values)
        steps [(str "Step 1: Calculate means - x̄ = " (/ (Math/round (* x-mean 1000)) 1000)
                    ", ȳ = " (/ (Math/round (* y-mean 1000)) 1000))]]
    (if (> max-steps 1)
      (let [numerator-terms (map #(* (- %1 x-mean) (- %2 y-mean)) x-values y-values)
            denominator-terms (map #(* (- % x-mean) (- % x-mean)) x-values)
            steps (conj steps (str "Step 2: Calculate slope components - Σ(x-x̄)(y-ȳ) = "
                                   (/ (Math/round (* (reduce + numerator-terms) 1000)) 1000)))]
        (if (> max-steps 2)
          (let [slope (/ (reduce + numerator-terms) (reduce + denominator-terms))
                steps (conj steps (str "Step 3: Slope b = " (/ (Math/round (* slope 1000)) 1000)))]
            (if (> max-steps 3)
              (let [intercept (- y-mean (* slope x-mean))
                    steps (conj steps (str "Step 4: Intercept a = ȳ - bx̄ = " 
                                           (/ (Math/round (* intercept 1000)) 1000)))]
                (if (> max-steps 4)
                  (let [r (calculate-correlation x-values y-values)
                        steps (conj steps (str "Step 5: R² = " 
                                               (/ (Math/round (* r r 1000)) 1000)))]
                    steps)
                  steps))
              steps))
          steps))
      steps)))

;; Stub implementations for compilation
(defn validate-chart-data
  "Validates chart data structure for rendering."
  [chart-data]
  (and (map? chart-data)
       (contains? chart-data :type)
       (contains? chart-data :data)
       (vector? (:data chart-data))
       (chart-types (:type chart-data))
       (seq (:data chart-data))))

(defn optimize-data-for-device
  "Optimizes data size based on device performance level."
  [data performance-level]
  (let [threshold (get-in performance-thresholds [performance-level :max-data-points])]
    (if (> (count data) threshold)
      ;; Sample data points evenly
      (let [step (Math/ceil (/ (count data) threshold))
            indices (range 0 (count data) step)]
        (mapv #(nth data %) indices))
      data)))

(defn create-chart-spec
  "Stub implementation for creating chart specifications."
  [chart-data & _opts]
  {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
   :width 400
   :height 300
   :data {:values (:data chart-data)}
   :mark {:type "bar"}
   :encoding {:x {:field (:x-field chart-data) :type "nominal"}
              :y {:field (:y-field chart-data) :type "quantitative"}}})

(defn get-chart-statistics
  "Extracts statistical information from chart data."
  [chart-data]
  (let [{:keys [type data x-field y-field]} chart-data]
    (case type
      :scatter (let [x-values (map x-field data)
                     y-values (map y-field data)]
                 (merge (calculate-linear-regression x-values y-values)
                        {:x-mean (calculate-mean x-values)
                         :y-mean (calculate-mean y-values)
                         :x-std (calculate-standard-deviation x-values)
                         :y-std (calculate-standard-deviation y-values)
                         :data-points (count data)}))
      
      :histogram (let [values (map x-field data)]
                   {:mean (calculate-mean values)
                    :std (calculate-standard-deviation values)
                    :variance (calculate-variance values)
                    :data-points (count data)
                    :min (apply min values)
                    :max (apply max values)})
      
      ;; Default statistics for other chart types
      {:data-points (count data)
       :chart-type type})))

(defn render-chart-to-element
  "Stub implementation for rendering charts to DOM."
  [_element-id _vega-spec]
  (let [result-chan (chan)]
    (go (>! result-chan {:success false :error "Implementation pending"}))
    result-chan))