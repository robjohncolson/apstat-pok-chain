(ns pok.core
  "Core namespace for PoK blockchain application initialization."
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [pok.state :as state]
            [pok.reputation :as rep]
            [pok.consensus :as consensus]
            [pok.curriculum :as curriculum]
            [pok.renderer :as renderer]
            [clojure.core.async :as async :refer [go <!]]))

(defn init-phase2-data-layer
  "Initialize Phase 2 data layer components"
  []
  ;; Load curriculum index on startup
  (rf/dispatch [::state/load-curriculum-index])
  
  ;; Log device performance for optimization
  (js/console.log "Device performance level:" 
                  (name (curriculum/detect-device-performance)))
  
  ;; Set up cache statistics monitoring
  (go
    (let [cache-stats (<! (curriculum/get-cache-stats))]
      (js/console.log "Cache status:" (clj->js cache-stats)))))

(defn mount-root
  "Placeholder mount function - UI components will be implemented in Phase 4"
  []
  (rdom/render [:div 
                [:h1 "PoK Chain - Phase 2 Complete"]
                [:p "Data Layer: EDN curriculum loading and Vega-Lite rendering implemented"]
                [:div#chart-container "Chart rendering area"]
                [:div#curriculum-status "Curriculum status area"]]
               (.getElementById js/document "app")))

(defn ^:export init
  "Application initialization function"
  []
  (rf/dispatch-sync [::state/initialize-db])
  (mount-root)
  ;; Initialize Phase 2 data layer
  (init-phase2-data-layer)
  (js/console.log "PoK Chain Phase 2 initialized - Data layer ready"))

;; Development helpers for REPL testing

(defn create-test-node
  "Creates a test node for REPL validation"
  [pubkey archetype]
  (rf/dispatch [::state/add-node pubkey archetype])
  (rf/dispatch [::state/set-current-user pubkey]))

(defn test-reputation-calculation
  "Tests reputation calculation with mock data"
  []
  (let [attestations [{:timestamp 1000 :owner-pubkey "alice" :payload {:hash "A"}}
                      {:timestamp 2000 :owner-pubkey "bob" :payload {:hash "A"}}
                      {:timestamp 3000 :owner-pubkey "charlie" :payload {:hash "B"}}
                      {:timestamp 4000 :owner-pubkey "diana" :payload {:hash "A"}}]
        target-timestamp 2500
        target-hash "A"]
    (rep/calculate-proportion-before-attestation attestations target-timestamp target-hash)))

(defn test-consensus-calculation
  "Tests consensus calculation with mock attestations"
  []
  (let [attestations [{:type "attestation" :payload {:hash "A"}}
                      {:type "attestation" :payload {:hash "A"}}
                      {:type "attestation" :payload {:hash "B"}}
                      {:type "ap_reveal" :payload {:hash "A"}}]]
    (consensus/calculate-convergence attestations)))

;; Phase 2 testing functions

(defn test-lesson-loading
  "Tests lesson loading with cache performance"
  []
  (go
    (js/console.log "Testing lesson loading...")
    (let [result (<! (curriculum/load-lesson "unit1" "1-1"))]
      (if (:success result)
        (js/console.log "Lesson loaded successfully:" (clj->js (:data result)))
        (js/console.error "Lesson loading failed:" (:error result))))))

(defn test-chart-rendering
  "Tests chart rendering with sample data"
  []
  (let [sample-chart-data {:type :scatter
                           :data [{:x 1 :y 2} {:x 2 :y 4} {:x 3 :y 6} {:x 4 :y 8} {:x 5 :y 10}]
                           :x-field :x
                           :y-field :y
                           :title "Test Linear Relationship"}]
    (if (renderer/validate-chart-data sample-chart-data)
      (let [vega-spec (renderer/create-chart-spec sample-chart-data)
            stats (renderer/get-chart-statistics sample-chart-data)]
        (js/console.log "Chart spec generated:" (clj->js vega-spec))
        (js/console.log "Chart statistics:" (clj->js stats))
        vega-spec)
      (js/console.error "Invalid chart data"))))

(defn test-device-performance
  "Tests device performance detection and optimization"
  []
  (let [performance-level (curriculum/detect-device-performance)
        large-dataset (mapv #(hash-map :x % :y (rand 100)) (range 1000))
        optimized-data (renderer/optimize-data-for-device large-dataset performance-level)]
    (js/console.log "Device performance:" (name performance-level))
    (js/console.log "Original data points:" (count large-dataset))
    (js/console.log "Optimized data points:" (count optimized-data))
    {:performance-level performance-level
     :original-size (count large-dataset)
     :optimized-size (count optimized-data)}))

(defn test-regression-calculation
  "Tests statistical regression calculation with AP precision"
  []
  (let [x-values [1.234 2.567 3.891 4.123 5.456]
        y-values [2.456 5.123 7.789 8.456 11.123]
        regression (renderer/calculate-linear-regression x-values y-values)
        steps (renderer/calculate-regression-steps x-values y-values 5)]
    (js/console.log "Regression results:" (clj->js regression))
    (js/console.log "Calculation steps:" (clj->js steps))
    regression))

(defn test-data-validation
  "Tests data validation for curriculum and charts"
  []
  (let [valid-lesson {:id "test" :name "Test" :content "Content" :questions []}
        invalid-lesson {:id "test"}
        valid-chart {:type :bar :data [{:x "A" :y 10}] :x-field :x :y-field :y}
        invalid-chart {:type :invalid}]
    (js/console.log "Valid lesson validation:" (curriculum/validate-lesson-data valid-lesson))
    (js/console.log "Invalid lesson validation:" (curriculum/validate-lesson-data invalid-lesson))
    (js/console.log "Valid chart validation:" (renderer/validate-chart-data valid-chart))
    (js/console.log "Invalid chart validation:" (renderer/validate-chart-data invalid-chart))))

(defn test-cache-operations
  "Tests IndexedDB cache operations"
  []
  (go
    (js/console.log "Testing cache operations...")
    ;; Get initial cache stats
    (let [initial-stats (<! (curriculum/get-cache-stats))]
      (js/console.log "Initial cache stats:" (clj->js initial-stats)))
    
    ;; Clear cache
    (let [clear-result (<! (curriculum/clear-cache))]
      (js/console.log "Cache clear result:" clear-result))
    
    ;; Get updated stats
    (let [final-stats (<! (curriculum/get-cache-stats))]
      (js/console.log "Final cache stats:" (clj->js final-stats)))))

(defn demo-phase2-features
  "Demonstrates Phase 2 features with sample data"
  []
  (js/console.log "=== Phase 2 Demo ===")
  
  ;; Test device performance detection
  (test-device-performance)
  
  ;; Test statistical calculations
  (test-regression-calculation)
  
  ;; Test data validation
  (test-data-validation)
  
  ;; Test chart rendering
  (test-chart-rendering)
  
  ;; Test lesson loading (async)
  (test-lesson-loading)
  
  ;; Test cache operations (async)
  (test-cache-operations)
  
  (js/console.log "=== Demo Complete ==="))

;; Export for global access in development
(set! js/pokCore #js {:createTestNode create-test-node
                      :testReputation test-reputation-calculation
                      :testConsensus test-consensus-calculation
                      ;; Phase 2 exports
                      :testLessonLoading test-lesson-loading
                      :testChartRendering test-chart-rendering
                      :testDevicePerformance test-device-performance
                      :testRegression test-regression-calculation
                      :testValidation test-data-validation
                      :testCache test-cache-operations
                      :demoPhase2 demo-phase2-features})
