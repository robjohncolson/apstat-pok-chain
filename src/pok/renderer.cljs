(ns pok.renderer
  "Vega-Lite chart renderer with hybrid pre-computation for performance.
   Implements adaptive rendering based on device capabilities with statistical pre-computation."
  (:require [clojure.core.async :as async :refer [go chan]]))

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

;; Device performance detection
(defn detect-device-performance
  "Detects device performance level based on navigator.hardwareConcurrency."
  []
  (let [cores (or js/navigator.hardwareConcurrency 2)]
    (cond
      (< cores 4) :low
      (< cores 8) :medium
      :else :high)))

;; Chart data validation and processing
(defn validate-chart-data
  "Validates chart data structure for rendering (legacy function for backward compatibility)."
  [chart-data]
  (and (map? chart-data)
       (contains? chart-data :type)
       (contains? chart-data :data)
       (vector? (:data chart-data))
       (chart-types (:type chart-data))
       (seq (:data chart-data))))

(defn validate-edn-attachments
  "Validates EDN attachment structure for chart rendering."
  [attachments]
  (and (map? attachments)
       (contains? attachments :chart-type)  ; Only render charts when explicitly requested
       (or (contains? attachments :table)
           (contains? attachments :x-labels)
           (contains? attachments :series))))

(defn series-to-chart-data
  "Converts series-based chart format to Vega data structure."
  [x-labels series chart-type]
  (case (keyword chart-type)
    :bar (let [first-series (first series)]
           (if (= (count series) 1)
             ;; Single series bar chart
             (mapv (fn [label value]
                     {:category label :value value})
                   x-labels (:values first-series))
             ;; Multi-series bar chart (grouped)
             (flatten
               (map
                 (fn [series-data]
                   (mapv (fn [label value]
                           {:category label 
                            :value value 
                            :series (:name series-data)})
                         x-labels (:values series-data)))
                 series))))
    :pie (let [pie-series (first series)
               pie-values (:values pie-series)]
           (if (map? (first pie-values))
             ;; Already in name/value format
             pie-values
             ;; Convert from labels + values
             (mapv (fn [label value]
                     {:name label :value value})
                   x-labels pie-values)))
    ;; Default to bar format
    (mapv (fn [label value]
            {:category label :value value})
          x-labels (:values (first series)))))

(defn table-to-chart-data
  "Converts EDN table format to chart data structure."
  [table chart-type]
  (when (and (vector? table) (> (count table) 1))
    (let [raw-headers (first table)
          rows (rest table)
          ;; Clean up headers - replace empty strings and ensure valid field names
          headers (map-indexed (fn [idx header]
                                (if (or (empty? header) (= header ""))
                                  (str "column" idx)
                                  header)) 
                              raw-headers)
          ;; Convert table to key-value pairs, handling the case where this is a cross-tabulation
          data (if (= (keyword chart-type) :table-display)
                 ;; For table display, keep original structure
                 (mapv (fn [row]
                        (zipmap (map keyword headers) row))
                      rows)
                 ;; For charts, convert to category-value pairs for visualization
                 (let [value-headers (rest headers)]
                   (flatten
                     (map
                       (fn [row]
                         (let [row-label (first row)
                               row-values (rest row)]
                           (map-indexed
                             (fn [val-idx val]
                               {:category (nth value-headers val-idx)
                                :value (if (string? val) 
                                        (try (js/parseFloat val) 
                                             (catch js/Error _ val))
                                        val)
                                :series row-label})
                             row-values)))
                       rows))))]
      {:data data
       :headers headers
       :original-headers raw-headers
       :type (keyword chart-type)})))

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

(defn create-bar-chart-spec
  "Creates Vega-Lite bar chart specification."
  [data headers performance-level & {:keys [multi-series?] :or {multi-series? false}}]
  (let [optimized-data (optimize-data-for-device data performance-level)]
    (if multi-series?
      ;; Multi-series bar chart
      {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
       :width 400
       :height 300
       :data {:values optimized-data}
       :mark {:type "bar" :tooltip true}
       :encoding {:x {:field :category :type "nominal" :title (first headers)}
                  :y {:field :value :type "quantitative" :title (second headers)}
                  :color {:field :series :type "nominal" :title "Series"}
                  :xOffset {:field :series}}}
      ;; Single series bar chart
      (let [x-field (if (contains? (first optimized-data) :category) :category (keyword (first headers)))
            y-field (if (contains? (first optimized-data) :value) :value (keyword (second headers)))]
        {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
         :width 400
         :height 300
         :data {:values optimized-data}
         :mark {:type "bar" :tooltip true}
         :encoding {:x {:field x-field :type "nominal" :title (or (first headers) "Category")}
                    :y {:field y-field :type "quantitative" :title (or (second headers) "Value")}}}))))

(defn create-pie-chart-spec
  "Creates Vega-Lite pie chart specification."
  [data headers performance-level]
  (let [optimized-data (optimize-data-for-device data performance-level)
        ;; Check if data uses name/value format or custom field names
        name-field (if (contains? (first optimized-data) :name) :name (keyword (first headers)))
        value-field (if (contains? (first optimized-data) :value) :value (keyword (second headers)))]
    {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
     :width 400
     :height 300
     :data {:values optimized-data}
     :mark {:type "arc"
            :innerRadius 50
            :tooltip true}
     :encoding {:theta {:field value-field :type "quantitative"}
                :color {:field name-field :type "nominal" :title "Category"}}}))

(defn create-histogram-spec
  "Creates Vega-Lite histogram specification."
  [data headers performance-level]
  (let [optimized-data (optimize-data-for-device data performance-level)
        x-field (keyword (first headers))
        y-field (keyword (second headers))]
    {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
     :width 400
     :height 300
     :data {:values optimized-data}
     :mark {:type "bar"
            :tooltip true}
     :encoding {:x {:field x-field
                    :type "ordinal"
                    :title (first headers)}
                :y {:field y-field
                    :type "quantitative"
                    :title (second headers)}}}))

(defn create-chart-spec
  "Creates appropriate Vega-Lite specification based on chart type.
   Supports both legacy format (chart-data map) and new EDN format (table + chart-type)."
  ([chart-data]
   ;; Legacy format: single map with :type, :data, etc.
   (when (validate-chart-data chart-data)
     (let [{:keys [type data x-field y-field]} chart-data
           performance-level (detect-device-performance)
           optimized-data (optimize-data-for-device data performance-level)]
       (js/console.log "Creating chart spec (legacy format):" type "with" (count data) "data points")
       (case type
         :bar {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
               :width 400
               :height 300
               :data {:values optimized-data}
               :mark {:type "bar" :tooltip true}
               :encoding {:x {:field x-field :type "nominal"}
                          :y {:field y-field :type "quantitative"}}}
         :scatter {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
                   :width 400
                   :height 300
                   :data {:values optimized-data}
                   :mark {:type "circle" :tooltip true}
                   :encoding {:x {:field x-field :type "quantitative"}
                              :y {:field y-field :type "quantitative"}}}
         ;; Default bar chart
         {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
          :width 400
          :height 300
          :data {:values optimized-data}
          :mark {:type "bar" :tooltip true}
          :encoding {:x {:field x-field :type "nominal"}
                     :y {:field y-field :type "quantitative"}}}))))
  ([table chart-type & {:keys [performance-level] :or {performance-level :medium}}]
   ;; New EDN format: table array + chart-type string
   (when-let [chart-data (table-to-chart-data table chart-type)]
     (let [{:keys [data headers]} chart-data]
       (js/console.log "Creating chart spec (EDN format):" chart-type "with" (count data) "data points")
       (js/console.log "Chart data sample:" (clj->js (take 3 data)))
       (js/console.log "Chart headers:" (clj->js headers))
       (case (keyword chart-type)
         :bar (create-bar-chart-spec data headers performance-level :multi-series? true)
         :pie (create-pie-chart-spec data headers performance-level)
         :histogram (create-histogram-spec data headers performance-level)
         ;; Default to bar chart with multi-series support
         (create-bar-chart-spec data headers performance-level :multi-series? true))))))

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
  "Renders Vega-Lite chart to DOM element with performance logging."
  [element-id vega-spec]
  (let [result-chan (chan)
        start-time (js/Date.now)]
    (go
      (try
        (js/console.log "Rendering chart to element:" element-id)
        (js/console.log "Vega spec:" (clj->js vega-spec))
        
        ;; Check if element exists  
        (js/console.log "Searching for element:" element-id)
        (let [element (.getElementById js/document element-id)]
          (js/console.log "Element search result:" element)
          (if element
            (do
              (js/console.log "DOM element found! Element details:")
              (js/console.log "  ID:" (.-id element))
              (js/console.log "  Tag:" (.-tagName element))
              (js/console.log "  Classes:" (.-className element))
              (js/console.log "Starting Vega embed...")
              ;; Use vegaEmbed to render the chart
              (.then (js/vegaEmbed element (clj->js vega-spec) #js {:actions false})
                     (fn [_result]
                       (let [render-time (- (js/Date.now) start-time)]
                         (js/console.log "Chart rendered successfully in" render-time "ms")
                         (async/put! result-chan {:success true 
                                                :render-time render-time 
                                                :element-id element-id})))
                     (fn [error]
                       (let [render-time (- (js/Date.now) start-time)]
                         (js/console.error "Chart render failed:" error)
                         (async/put! result-chan {:success false 
                                                :error (str error)
                                                :render-time render-time
                                                :element-id element-id})))))
            (do
              (js/console.error "Element not found:" element-id)
              (js/console.log "Document ready state:" (.-readyState js/document))
              (js/console.log "Available elements with 'chart' in ID:")
              (let [all-elements (.querySelectorAll js/document "[id*='chart']")
                    div-elements (.querySelectorAll js/document "div")]
                (js/console.log "Total divs in document:" (.-length div-elements))
                (js/console.log "Chart elements found:" (.-length all-elements))
                (dotimes [i (.-length all-elements)]
                  (let [el (aget all-elements i)]
                    (js/console.log "Found chart element:" (.-id el) "visible:" (not (.-hidden el))))))
              (async/put! result-chan {:success false 
                                     :error (str "Element not found: " element-id)
                                     :render-time 0
                                     :element-id element-id}))))
        (catch js/Error e
          (js/console.error "Chart rendering exception:" e)
          (async/put! result-chan {:success false 
                                 :error (str "Exception: " e)
                                 :render-time (- (js/Date.now) start-time)
                                 :element-id element-id}))))
    result-chan))

;; High-level chart rendering function for EDN attachments
(defn render-edn-chart
  "Renders chart from EDN attachment data to DOM element."
  [element-id attachments]
  (let [result-chan (chan)]
    (go
      (try
        (if (validate-edn-attachments attachments)
          (let [performance-level (detect-device-performance)
                vega-spec (cond
                            ;; Table-based format: {:table [...] :chart-type "..."}
                            (and (:table attachments) (:chart-type attachments))
                            (do
                              (js/console.log "Creating table-based chart:" (:chart-type attachments))
                              (create-chart-spec (:table attachments) (:chart-type attachments)
                                               :performance-level performance-level))
                            
                            ;; Series-based format: {:chart-type "..." :x-labels [...] :series [...]}
                            (and (:chart-type attachments) (:series attachments))
                            (let [chart-type (:chart-type attachments)
                                  x-labels (:x-labels attachments)
                                  series (:series attachments)
                                  chart-data (series-to-chart-data x-labels series chart-type)
                                  multi-series? (> (count series) 1)]
                              (js/console.log "Creating series-based chart:" chart-type "with series count:" (count series))
                              (case (keyword chart-type)
                                :bar (create-bar-chart-spec chart-data ["Category" "Value"] performance-level
                                                          :multi-series? multi-series?)
                                :pie (create-pie-chart-spec chart-data ["Name" "Value"] performance-level)
                                :histogram (create-histogram-spec chart-data ["Category" "Frequency"] performance-level)
                                ;; Default
                                (create-bar-chart-spec chart-data ["Category" "Value"] performance-level
                                                     :multi-series? multi-series?)))
                            
                            :else nil)]
            (if vega-spec
              (let [render-result (async/<! (render-chart-to-element element-id vega-spec))]
                (async/>! result-chan render-result))
              (async/>! result-chan {:success false 
                                   :error "Failed to create chart specification - unsupported format"
                                   :element-id element-id})))
          (async/>! result-chan {:success false 
                                :error "Invalid EDN attachments structure"
                                :element-id element-id}))
        (catch js/Error e
          (js/console.error "EDN chart rendering exception:" e)
          (async/>! result-chan {:success false 
                                :error (str "Exception: " e)
                                :element-id element-id}))))
    result-chan))