(ns pok.performance
  "Performance monitoring and optimization utilities for Phase 5 validation.
   Provides benchmarking tools and metrics collection for bundle size and runtime performance."
  (:require [cljs.core.async :as async :refer [go <! timeout]]))

;; Performance constants from foundational architecture
(def ^:const max-bundle-size-mb 5)
(def ^:const max-operation-time-ms 100)
(def ^:const max-lesson-load-time-ms 100)
(def ^:const target-lesson-size-kb 6)
(def ^:const performance-sample-size 10)

;; Performance monitoring state
(defonce performance-metrics (atom {:measurements []
                                    :bundle-size nil
                                    :memory-usage []
                                    :operation-times []
                                    :load-times []}))

;; Function for timing operations (replacing macro for ClojureScript compatibility)
(defn measure-time*
  "Measures execution time of the given function and returns [result time-ms]."
  [f]
  (let [start (js/performance.now)
        result (f)
        end (js/performance.now)
        duration (- end start)]
    [result duration]))

;; Performance measurement functions

(defn measure-memory-usage
  "Measures current memory usage if available in browser."
  []
  (when (and js/performance (.-memory js/performance))
    (let [memory js/performance.memory]
      {:used-js-heap-size (.-usedJSHeapSize memory)
       :total-js-heap-size (.-totalJSHeapSize memory)
       :js-heap-size-limit (.-jsHeapSizeLimit memory)
       :timestamp (js/Date.now)})))

(defn record-operation-time
  "Records the time taken for a specific operation."
  [operation-name time-ms]
  (swap! performance-metrics update :operation-times conj
         {:operation operation-name
          :time-ms time-ms
          :timestamp (js/Date.now)
          :within-bounds? (<= time-ms max-operation-time-ms)}))

(defn record-load-time
  "Records lesson/resource load time."
  [resource-name size-bytes time-ms]
  (swap! performance-metrics update :load-times conj
         {:resource resource-name
          :size-kb (/ size-bytes 1024)
          :time-ms time-ms
          :timestamp (js/Date.now)
          :within-bounds? (<= time-ms max-lesson-load-time-ms)
          :size-within-target? (<= (/ size-bytes 1024) target-lesson-size-kb)}))

(defn record-memory-snapshot
  "Records a memory usage snapshot."
  []
  (when-let [memory-info (measure-memory-usage)]
    (swap! performance-metrics update :memory-usage conj memory-info)))

;; Bundle size estimation
(defn estimate-bundle-size
  "Estimates current bundle size by examining loaded scripts."
  []
  (go
    (let [scripts (array-seq (js/document.querySelectorAll "script[src]"))
          script-sizes (atom 0)]
      
      ;; For each script, try to estimate size (simplified approach)
      (doseq [script scripts]
        (let [src (.-src script)]
          (when (and src (re-find #"main\.js|test\.js" src))
            ;; Use fetch to get approximate size
            (try
              (let [response (<! (js/fetch src {:method "HEAD"}))
                    content-length (.get (.-headers response) "content-length")]
                (when content-length
                  (swap! script-sizes + (js/parseInt content-length))))
              (catch js/Error _
                ;; Fallback: estimate based on typical ClojureScript output
                (swap! script-sizes + (* 1024 1024)))))))
      
      (let [total-size-mb (/ @script-sizes 1024 1024)]
        (swap! performance-metrics assoc :bundle-size
               {:size-mb total-size-mb
                :within-bounds? (<= total-size-mb max-bundle-size-mb)
                :timestamp (js/Date.now)})
        total-size-mb))))

;; Performance testing utilities

(defn benchmark-consensus-operations
  "Benchmarks consensus calculation performance with varying loads."
  [test-data-sizes consensus-fn]
  (go
    (let [results (atom [])]
      (doseq [size test-data-sizes]
        ;; Generate test data
        (let [test-attestations (for [i (range size)]
                                  {:timestamp (+ 1000 (* i 100))
                                   :owner-pubkey (str "node-" (mod i 10))
                                   :payload {:hash (str "hash-" (mod i 3))}
                                   :type "attestation"})
              
              ;; Measure consensus calculation time
              [_ time-ms] (measure-time* #(consensus-fn test-attestations))
              
              measurement {:size size
                          :time-ms time-ms
                          :within-bounds? (<= time-ms max-operation-time-ms)
                          :operations-per-ms (/ size time-ms)}]
          
          (swap! results conj measurement)
          (record-operation-time (str "consensus-" size) time-ms)))
      
      @results)))

(defn benchmark-reputation-operations
  "Benchmarks reputation calculation performance."
  [test-scenarios reputation-fn]
  (go
    (let [results (atom [])]
      (doseq [{:keys [name attestations nodes final-hash]} test-scenarios]
        (let [[_ time-ms] (measure-time* #(<! (reputation-fn attestations final-hash nodes)))
              
              measurement {:scenario name
                          :attestation-count (count attestations)
                          :node-count (count nodes)
                          :time-ms time-ms
                          :within-bounds? (<= time-ms max-operation-time-ms)}]
          
          (swap! results conj measurement)
          (record-operation-time (str "reputation-" name) time-ms)))
      
      @results)))

(defn benchmark-memory-efficiency
  "Monitors memory usage during operations."
  [operation-fn operation-name iterations]
  (go
    (record-memory-snapshot) ; Baseline
    
    (dotimes [i iterations]
      (operation-fn)
      (when (zero? (mod i (/ iterations 10)))
        (record-memory-snapshot)))
    
    (record-memory-snapshot) ; Final
    
    ;; Calculate memory efficiency metrics
    (let [measurements (filter #(>= (:timestamp %) 
                                   (- (js/Date.now) (* 60 1000))) ; Last minute
                              (:memory-usage @performance-metrics))
          initial-memory (:used-js-heap-size (first measurements))
          final-memory (:used-js-heap-size (last measurements))
          memory-delta (- final-memory initial-memory)]
      
      {:operation operation-name
       :iterations iterations
       :memory-delta-mb (/ memory-delta 1024 1024)
       :memory-per-operation-kb (/ memory-delta iterations 1024)
       :measurements (count measurements)})))

;; Performance validation functions

(defn validate-performance-bounds
  "Validates that all performance metrics are within acceptable bounds."
  []
  (let [metrics @performance-metrics
        bundle-ok? (or (nil? (:bundle-size metrics))
                      (:within-bounds? (:bundle-size metrics)))
        
        recent-operations (take-last 50 (:operation-times metrics))
        operations-ok? (every? :within-bounds? recent-operations)
        
        recent-loads (take-last 20 (:load-times metrics))
        loads-ok? (every? :within-bounds? recent-loads)
        
        avg-operation-time (if (seq recent-operations)
                            (/ (reduce + (map :time-ms recent-operations))
                               (count recent-operations))
                            0)
        
        avg-load-time (if (seq recent-loads)
                       (/ (reduce + (map :time-ms recent-loads))
                          (count recent-loads))
                       0)]
    
    {:bundle-size-ok? bundle-ok?
     :operations-ok? operations-ok?
     :loads-ok? loads-ok?
     :avg-operation-time-ms avg-operation-time
     :avg-load-time-ms avg-load-time
     :total-measurements (+ (count recent-operations) (count recent-loads))
     :all-bounds-met? (and bundle-ok? operations-ok? loads-ok?)}))

(defn generate-performance-recommendations
  "Generates optimization recommendations based on performance data."
  [validation operation-summary load-summary]
  (let [recommendations (atom [])]
    
    ;; Bundle size recommendations
    (when-not (:bundle-size-ok? validation)
      (swap! recommendations conj
             "Bundle size exceeds 5MB limit. Enable advanced optimizations and tree-shaking."))
    
    ;; Operation time recommendations
    (when (and operation-summary (> (:avg-ms operation-summary) (* 0.8 max-operation-time-ms)))
      (swap! recommendations conj
             "Operation times approaching limit. Consider optimizing consensus algorithms or caching."))
    
    ;; Load time recommendations
    (when (and load-summary (> (:avg-time-ms load-summary) (* 0.8 max-lesson-load-time-ms)))
      (swap! recommendations conj
             "Lesson load times high. Implement EDN lazy-loading and pre-computation."))
    
    ;; Memory recommendations
    (when (and validation (< (:avg-operation-time-ms validation) 10))
      (swap! recommendations conj
             "Excellent performance! Consider enabling additional features."))
    
    (if (empty? @recommendations)
      ["All performance metrics within acceptable bounds."]
      @recommendations)))

(defn generate-performance-report
  "Generates comprehensive performance report for Phase 5 validation."
  []
  (let [metrics @performance-metrics
        validation (validate-performance-bounds)
        
        ;; Bundle analysis
        bundle-info (:bundle-size metrics)
        
        ;; Operation analysis
        operations (:operation-times metrics)
        operation-summary (when (seq operations)
                           (let [times (map :time-ms operations)]
                             {:count (count operations)
                              :min-ms (apply min times)
                              :max-ms (apply max times)
                              :avg-ms (/ (reduce + times) (count times))
                              :within-bounds-pct (* 100 (/ (count (filter :within-bounds? operations))
                                                           (count operations)))}))
        
        ;; Load analysis
        loads (:load-times metrics)
        load-summary (when (seq loads)
                      (let [times (map :time-ms loads)
                            sizes (map :size-kb loads)]
                        {:count (count loads)
                         :avg-time-ms (/ (reduce + times) (count times))
                         :avg-size-kb (/ (reduce + sizes) (count sizes))
                         :within-bounds-pct (* 100 (/ (count (filter :within-bounds? loads))
                                                      (count loads)))
                         :size-efficiency-pct (* 100 (/ (count (filter :size-within-target? loads))
                                                        (count loads)))}))
        
        ;; Memory analysis
        memory-snapshots (:memory-usage metrics)
        memory-summary (when (seq memory-snapshots)
                        (let [heap-sizes (map :used-js-heap-size memory-snapshots)]
                          {:snapshots (count memory-snapshots)
                           :min-heap-mb (/ (apply min heap-sizes) 1024 1024)
                           :max-heap-mb (/ (apply max heap-sizes) 1024 1024)
                           :avg-heap-mb (/ (reduce + heap-sizes) (count heap-sizes) 1024 1024)}))]
    
    {:timestamp (js/Date.now)
     :validation validation
     :bundle bundle-info
     :operations operation-summary
     :loads load-summary
     :memory memory-summary
     :recommendations (generate-performance-recommendations validation operation-summary load-summary)}))

;; Performance testing suite for Phase 5
(defn run-performance-test-suite
  "Runs comprehensive performance test suite for Phase 5 validation."
  [consensus-fn _reputation-fn]
  (go
    (println "Starting Phase 5 Performance Test Suite...")
    
    ;; Test 1: Bundle size analysis
    (<! (estimate-bundle-size))
    (println "✓ Bundle size analysis complete")
    
    ;; Test 2: Consensus performance benchmarks
    (let [consensus-results (<! (benchmark-consensus-operations 
                                  [10 50 100 500] 
                                  consensus-fn))]
      (println "✓ Consensus benchmarks complete:" (count consensus-results) "tests"))
    
    ;; Test 3: Memory efficiency testing
    (<! (benchmark-memory-efficiency 
          #(consensus-fn (repeat 100 {:payload {:hash "test"}}))
          "consensus-stress"
          100))
    (println "✓ Memory efficiency testing complete")
    
    ;; Test 4: Generate final report
    (let [report (generate-performance-report)]
      (println "✓ Performance test suite complete")
      (println "Final validation status:" (:all-bounds-met? (:validation report)))
      report)))

;; Export performance data for analysis
(defn export-performance-data
  "Exports performance data in JSON format for external analysis."
  []
  (let [data @performance-metrics
        json-str (js/JSON.stringify (clj->js data) nil 2)]
    json-str))

;; Reset performance metrics
(defn reset-performance-metrics!
  "Resets all performance metrics (useful for testing)."
  []
  (reset! performance-metrics {:measurements []
                               :bundle-size nil
                               :memory-usage []
                               :operation-times []
                               :load-times []}))
