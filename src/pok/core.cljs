(ns pok.core
  "Core namespace for PoK blockchain application initialization."
  (:require [reagent.dom.client :as rdom]
            [re-frame.core :as rf]
            [pok.state :as state]
            [pok.reputation :as rep]
            [pok.consensus :as consensus]
            [pok.curriculum :as curriculum]
            [pok.renderer :as renderer]
            [pok.qr :as qr]
            [pok.delta :as delta]
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

(defn init-phase3-sync-layer
  "Initialize Phase 3 synchronization layer components"
  []
  (js/console.log "Phase 3 sync layer initialized")
  ;; Check for QR scanning capabilities
  (if (and js/navigator (.-mediaDevices js/navigator))
    (js/console.log "Camera access available for QR scanning")
    (js/console.warn "Camera access not available - QR sync will be disabled"))
  
  ;; Log sync layer configuration
  (js/console.log "Delta size limit:" delta/max-delta-size "bytes")
  (js/console.log "QR chunk size limit:" qr/max-chunk-size "bytes")
  (js/console.log "Fork decay factor:" delta/fork-decay-factor))

(defonce root (rdom/create-root (.getElementById js/document "app")))

(defn mount-root
  "Placeholder mount function - UI components will be implemented in Phase 4"
  []
  (rdom/render root [:div 
                     [:h1 "PoK Chain - Phase 3 Complete"]
                     [:p "Synchronization Layer: QR scanning and delta merging implemented"]
                     [:div#chart-container "Chart rendering area"]
                     [:div#curriculum-status "Curriculum status area"]
                     [:div#sync-status "Synchronization status area"]
                     [:div#qr-scanner "QR scanner area (Phase 4 UI pending)"]]))

(defn ^:export init
  "Application initialization function"
  []
  (rf/dispatch-sync [::state/initialize-db])
  (mount-root)
  ;; Initialize Phase 2 data layer
  (init-phase2-data-layer)
  ;; Initialize Phase 3 sync layer
  (init-phase3-sync-layer)
  (js/console.log "PoK Chain Phase 3 initialized - Synchronization layer ready"))

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

;; Phase 3 testing functions

(defn test-delta-creation
  "Tests delta creation with mock state"
  []
  (let [mock-state {:nodes {"alice" {:mempool [{:id "tx1" :timestamp 1000 :question-id "q1"}]
                                     :chain [{:hash "block1" :timestamp 2000}]}
                            "bob" {:mempool []
                                   :chain []}}}
        peer-timestamp 500
        ;; Debug intermediate steps
        delta-payload (delta/calculate-delta mock-state peer-timestamp)
        _ (js/console.log "Debug - Raw delta payload:" (clj->js delta-payload))
        delta-result (delta/create-sync-delta mock-state peer-timestamp)
        _ (js/console.log "Debug - Full delta result:" (clj->js delta-result))]
    (js/console.log "Delta creation test:")
    (js/console.log "- Success:" (:success delta-result))
    (js/console.log "- Error:" (:error delta-result))
    (js/console.log "- Size:" (get-in delta-result [:size-validation :size]) "bytes")
    (js/console.log "- Within limits:" (get-in delta-result [:size-validation :valid?]))
    (js/console.log "- Delta payload:" (clj->js (:delta delta-result)))
    delta-result))

(defn test-conflict-resolution
  "Tests 4-level conflict resolution with mock conflicts"
  []
  (let [mock-nodes {"alice" {:reputation 2.0} "bob" {:reputation 1.5} "charlie" {:reputation 1.0}}
        conflicting-txns [{:id "tx1" :timestamp 1000 :owner-pubkey "alice" :question-id "q1"}
                          {:id "tx1" :timestamp 1001 :owner-pubkey "bob" :question-id "q1"}
                          {:id "tx2" :timestamp 2000 :owner-pubkey "alice" :question-id "q1"}
                          {:id "tx3" :timestamp 2000 :owner-pubkey "charlie" :question-id "q2"}]
        
        ;; Test ID conflicts
        id-resolved (delta/resolve-id-conflicts conflicting-txns mock-nodes)
        
        ;; Test timestamp clustering
        clusters (delta/cluster-transactions-by-timestamp conflicting-txns)
        
        ;; Test logical latest
        latest-resolved (delta/resolve-logical-latest conflicting-txns)]
    
    (js/console.log "Conflict resolution test:")
    (js/console.log "- Original transactions:" (count conflicting-txns))
    (js/console.log "- After ID resolution:" (count id-resolved))
    (js/console.log "- Timestamp clusters:" (count clusters))
    (js/console.log "- After latest resolution:" (count latest-resolved))
    {:original (count conflicting-txns)
     :id-resolved (count id-resolved)
     :clusters (count clusters)
     :latest-resolved (count latest-resolved)}))

(defn test-qr-chunking
  "Tests QR payload chunking and reassembly"
  []
  (try
    (let [test-payload "This is a test payload for QR chunking that should be split into multiple chunks"
          chunks (qr/chunk-payload-for-qr test-payload 50) ; Small chunk size for testing
          first-chunk (first chunks)
          payload-hash (get-in first-chunk [:metadata :payload-hash])
          reassembly-result (qr/reassemble-chunks chunks payload-hash)]
      
      (js/console.log "QR chunking test:")
      (js/console.log "- Original payload length:" (count test-payload))
      (js/console.log "- Number of chunks:" (count chunks))
      (js/console.log "- Reassembly success:" (:success reassembly-result))
      (js/console.log "- Payload match:" (= (:payload reassembly-result) test-payload))
      (js/console.log "- Hash verification:" (= (:hash reassembly-result) payload-hash))
      (when-not (:success reassembly-result)
        (js/console.log "- Reassembly error:" (:error reassembly-result)))
      {:original-size (count test-payload)
       :chunk-count (count chunks)
       :reassembly-success (:success reassembly-result)
       :payload-match (= (:payload reassembly-result) test-payload)})
    (catch js/Error e
      (js/console.error "QR chunking test error:" e)
      {:error (str e)})))

(defn test-fork-resolution
  "Tests hybrid fork resolution with reputation weighting"
  []
  (let [mock-nodes {"alice" {:reputation 3.0} "bob" {:reputation 2.0} "charlie" {:reputation 1.0}}
        fork-a [{:proposer "alice" :txns []} {:proposer "alice" :txns []}] ; Alice's longer fork
        fork-b [{:proposer "bob" :txns []} {:proposer "charlie" :txns []} {:proposer "bob" :txns []}] ; Diverse fork
        competing-forks [fork-a fork-b]
        winner (delta/resolve-fork-conflicts competing-forks [] mock-nodes)
        
        ;; Calculate weights for analysis
        weight-a (delta/calculate-fork-weight fork-a [] mock-nodes)
        weight-b (delta/calculate-fork-weight fork-b [] mock-nodes)]
    
    (js/console.log "Fork resolution test:")
    (js/console.log "- Fork A weight:" (:total-weight weight-a))
    (js/console.log "- Fork B weight:" (:total-weight weight-b))
    (js/console.log "- Winner fork:" (if (= winner fork-a) "A" "B"))
    (js/console.log "- Fork A components:" (clj->js (:components weight-a)))
    (js/console.log "- Fork B components:" (clj->js (:components weight-b)))
    {:fork-a-weight (:total-weight weight-a)
     :fork-b-weight (:total-weight weight-b)
     :winner (if (= winner fork-a) "A" "B")}))

(defn test-sync-events
  "Tests Re-frame sync events with mock data"
  []
  (js/console.log "Testing sync events...")
  
  ;; Test delta creation event
  (rf/dispatch [::state/create-sync-delta 1000])
  
  ;; Check state
  (go
    (<! (async/timeout 100)) ; Wait for async processing
    (let [creating? @(rf/subscribe [::state/creating-delta?])
          delta-ready? @(rf/subscribe [::state/delta-ready?])]
      (js/console.log "- Creating delta:" creating?)
      (js/console.log "- Delta ready:" delta-ready?)))
  
  ;; Test scan progress update
  (rf/dispatch [::state/update-scan-progress {:type :progress :received 2 :expected 5}])
  (let [progress @(rf/subscribe [::state/scan-progress])]
    (js/console.log "- Scan progress:" (clj->js progress)))
  
  {:events-dispatched true})

(defn test-delta-logic
  "Tests core delta logic without serialization"
  []
  (let [mock-state {:nodes {"alice" {:mempool [{:id "tx1" :timestamp 1000 :question-id "q1"}]
                                     :chain [{:hash "block1" :timestamp 2000}]}}}
        peer-timestamp 500
        delta-payload (delta/calculate-delta mock-state peer-timestamp)
        merkle-root (delta/calculate-merkle-root (:transactions delta-payload) (:blocks delta-payload))]
    
    (js/console.log "Delta logic test:")
    (js/console.log "- Delta transactions:" (count (:transactions delta-payload)))
    (js/console.log "- Delta blocks:" (count (:blocks delta-payload)))
    (js/console.log "- Merkle root:" merkle-root)
    (js/console.log "- Version:" (:version delta-payload))
    {:transaction-count (count (:transactions delta-payload))
     :block-count (count (:blocks delta-payload))
     :has-merkle-root (not (nil? (:merkle-root delta-payload)))
     :version (:version delta-payload)}))

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
  
  (js/console.log "=== Phase 2 Demo Complete ==="))

(defn demo-phase3-features
  "Demonstrates Phase 3 synchronization features with sample data"
  []
  (js/console.log "=== Phase 3 Demo ===")
  
  ;; Test core delta logic first
  (test-delta-logic)
  
  ;; Test delta creation and validation
  (test-delta-creation)
  
  ;; Test conflict resolution mechanisms
  (test-conflict-resolution)
  
  ;; Test QR chunking and reassembly
  (test-qr-chunking)
  
  ;; Test fork resolution
  (test-fork-resolution)
  
  ;; Test Re-frame sync events
  (test-sync-events)
  
  (js/console.log "=== Phase 3 Demo Complete ==="))

(defn demo-all-features
  "Demonstrates all implemented features (Phases 1-3)"
  []
  (js/console.log "=== Complete Feature Demo ===")
  
  ;; Phase 1: Core blockchain
  (js/console.log "--- Phase 1: Blockchain Core ---")
  (test-reputation-calculation)
  (test-consensus-calculation)
  
  ;; Phase 2: Data layer
  (js/console.log "--- Phase 2: Data Layer ---")
  (demo-phase2-features)
  
  ;; Phase 3: Synchronization
  (js/console.log "--- Phase 3: Synchronization ---")
  (demo-phase3-features)
  
  (js/console.log "=== All Features Demo Complete ==="))

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
                      :demoPhase2 demo-phase2-features
                      ;; Phase 3 exports
                      :testDeltaLogic test-delta-logic
                      :testDeltaCreation test-delta-creation
                      :testConflictResolution test-conflict-resolution
                      :testQrChunking test-qr-chunking
                      :testForkResolution test-fork-resolution
                      :testSyncEvents test-sync-events
                      :demoPhase3 demo-phase3-features
                      :demoAll demo-all-features})
