(ns pok.simulation
  "Simulation validation runner to match Racket benchmarks from final_simulation.rkt.
   Validates >90% accuracy and <7-day latency in 40-node/180-day simulation runs."
  (:require [cljs.core.async :as async :refer [go <! timeout]]
            [pok.consensus :as cons]
            [pok.reputation :as rep]
            [pok.performance :as perf]))

;; Simulation constants matching final_simulation.rkt
(def ^:const total-nodes 40)
(def ^:const class-size 20)
(def ^:const sim-days 180)
(def ^:const meetings-per-week 4)
(def ^:const questions-per-day 5)
(def ^:const progress-lag-prob 0.1)
(def ^:const sync-failure-prob 0.1)
(def ^:const cleanup-age 3)

;; Node archetypes from Racket simulation (line 23)
(def archetypes
  {:aces {:accuracy 0.95 :participation 0.90}
   :diligent {:accuracy 0.8 :participation 0.85}
   :strugglers {:accuracy 0.6 :participation 0.7}
   :guessers {:accuracy 0.3 :participation 0.6}})

;; Success criteria from foundational architecture
(def ^:const target-accuracy 0.90)
(def ^:const max-latency-days 7)

;; Simulation state management
(defonce simulation-state (atom {:nodes {}
                                 :transactions []
                                 :blocks []
                                 :questions []
                                 :current-day 0
                                 :metrics {:accuracy-history []
                                          :latency-history []
                                          :consensus-events []}}))

;; Node creation and management
(defn create-simulation-node
  "Creates a simulation node with specified archetype and initial state."
  [pubkey archetype]
  (let [archetype-data (get archetypes archetype)]
    {:pubkey pubkey
     :archetype archetype
     :accuracy (:accuracy archetype-data)
     :participation (:participation archetype-data)
     :reputation 1.0
     :mempool []
     :chain []
     :progress {:current-unit 1 :current-lesson 1}
     :last-active 0
     :consensus-history []}))

(defn initialize-simulation-network
  "Initializes the simulation network with 40 nodes distributed across archetypes."
  []
  (let [;; Distribution matching Racket simulation
        node-distribution {:aces 8      ; 20% high performers
                          :diligent 16  ; 40% steady workers  
                          :strugglers 12 ; 30% need support
                          :guessers 4}   ; 10% random guessers
        
        nodes (atom {})]
    
    (doseq [[archetype count] node-distribution]
      (dotimes [i count]
        (let [pubkey (str (name archetype) "-" i)]
          (swap! nodes assoc pubkey (create-simulation-node pubkey archetype)))))
    
    @nodes))

;; Question generation
(defn generate-simulation-questions
  "Generates questions for simulation matching curriculum structure."
  [num-questions]
  (for [i (range num-questions)]
    {:id (str "sim-question-" i)
     :prompt (str "Statistics Question " i)
     :type "multiple-choice"
     :choices ["A" "B" "C" "D"]
     :answer-key (rand-nth ["A" "B" "C" "D"])
     :unit (inc (quot i 20)) ; 20 questions per unit
     :difficulty (if (< (rand) 0.3) :hard :normal)}))

;; Answer simulation based on archetype
(defn simulate-answer
  "Simulates a node's answer based on its archetype accuracy."
  [node question]
  (let [accuracy (:accuracy node)
        correct-answer (:answer-key question)
        choices (:choices question)]
    
    (if (< (rand) accuracy)
      correct-answer ; Answer correctly
      (rand-nth (remove #(= % correct-answer) choices))))) ; Answer incorrectly

;; Participation simulation
(defn should-participate?
  "Determines if a node should participate based on archetype participation rate."
  [node _day]
  (let [participation (:participation node)
        lag-penalty (if (< (rand) progress-lag-prob) 0.3 0)]
    (< (rand) (- participation lag-penalty))))

;; Transaction generation
(defn create-simulation-transaction
  "Creates a simulation transaction for attestation or completion."
  [node question answer timestamp tx-type]
  {:id (str (:pubkey node) "-" timestamp "-" tx-type)
   :timestamp timestamp
   :owner-pubkey (:pubkey node)
   :question-id (:id question)
   :type tx-type
   :payload {:answer answer
             :hash (str (hash answer))}})

;; Consensus simulation
(defn simulate-consensus-round
  "Simulates a consensus round for a given question."
  [question nodes day]
  (go
    (let [participating-nodes (filter #(should-participate? (val %) day) nodes)
          base-timestamp (* day 24 60 60 1000) ; Convert day to ms
          
          ;; Phase 1: Initial attestations
          attestations (for [[_pubkey node] participating-nodes
                            :let [answer (simulate-answer node question)
                                  timestamp (+ base-timestamp (* (rand-int 1000) 60))]]
                        (create-simulation-transaction node question answer timestamp "attestation"))
          
          ;; Phase 2: Wait for consensus formation
          _ (<! (timeout 100)) ; Simulate network delay
          
          ;; Phase 3: Check for consensus
          consensus-answer (cons/find-consensus-answer attestations true (into {} nodes))
          convergence (cons/calculate-convergence attestations true (into {} nodes))
          
          ;; Phase 4: PoK completion if consensus reached
          completion-txns (when (and consensus-answer 
                                    (>= convergence cons/quorum-convergence-threshold))
                           (let [consensus-nodes (filter (fn [[_ node]]
                                                          (= consensus-answer 
                                                             (str (hash (simulate-answer node question)))))
                                                        participating-nodes)]
                             (for [[_pubkey node] (take 3 consensus-nodes) ; First 3 to mine
                                   :let [timestamp (+ base-timestamp (* 2000 60))]]
                               (create-simulation-transaction node question 
                                                            (get-in question [:answer-key]) 
                                                            timestamp "completion"))))
          
          all-transactions (concat attestations completion-txns)
          
          ;; Calculate accuracy for this round
          correct-hash (str (hash (:answer-key question)))
          accurate? (= consensus-answer correct-hash)
          
          ;; Calculate latency (time from first attestation to consensus)
          first-attestation-time (apply min (map :timestamp attestations))
          consensus-time (if (seq completion-txns)
                          (apply max (map :timestamp completion-txns))
                          first-attestation-time)
          latency-hours (/ (- consensus-time first-attestation-time) 1000 60 60)]
      
      {:question-id (:id question)
       :day day
       :transactions all-transactions
       :consensus-answer consensus-answer
       :correct-answer correct-hash
       :accurate? accurate?
       :convergence convergence
       :latency-hours latency-hours
       :participating-nodes (count participating-nodes)
       :total-transactions (count all-transactions)})))

;; Reputation updates
(defn update-simulation-reputations
  "Updates node reputations based on consensus results."
  [nodes consensus-results]
  (go
    (doseq [result consensus-results]
      (when (:accurate? result)
        (let [final-hash (:correct-answer result)
              attestations (filter #(= (:type %) "attestation") (:transactions result))]
          (<! (rep/process-attestation-rewards attestations final-hash nodes)))))
    nodes))

;; Main simulation runner
(defn run-simulation-day
  "Runs simulation for a single day with multiple questions."
  [nodes questions day]
  (go
    (let [daily-questions (take questions-per-day (drop (* day questions-per-day) questions))
          day-results (atom [])]
      
      ;; Process each question for the day
      (doseq [question daily-questions]
        (let [result (<! (simulate-consensus-round question nodes day))]
          (swap! day-results conj result)))
      
      ;; Update reputations based on day's consensus results
      (let [updated-nodes (<! (update-simulation-reputations nodes @day-results))]
        {:day day
         :results @day-results
         :updated-nodes updated-nodes
         :daily-accuracy (if (seq @day-results)
                          (/ (count (filter :accurate? @day-results))
                             (count @day-results))
                          0.0)
         :avg-latency-hours (if (seq @day-results)
                             (/ (reduce + (map :latency-hours @day-results))
                                (count @day-results))
                             0.0)}))))

;; Full simulation runner
(defn run-full-simulation
  "Runs the complete 40-node, 180-day simulation matching Racket benchmarks."
  []
  (go
    (println "Starting full simulation validation (40 nodes, 180 days)...")
    (perf/record-memory-snapshot)
    
    (let [start-time (js/Date.now)
          nodes (initialize-simulation-network)
          questions (generate-simulation-questions (* sim-days questions-per-day))
          
          ;; Track metrics across simulation
          accuracy-history (atom [])
          latency-history (atom [])]
      
      ;; Reset simulation state
      (reset! simulation-state {:nodes nodes
                               :transactions []
                               :blocks []
                               :questions questions
                               :current-day 0
                               :metrics {:accuracy-history []
                                        :latency-history []
                                        :consensus-events []}})
      
      ;; Run simulation day by day
      (loop [day 0
             current-nodes nodes]
        (if (>= day sim-days)
          ;; Simulation complete - calculate final metrics
          (let [end-time (js/Date.now)
                total-time-ms (- end-time start-time)
                
                overall-accuracy (if (seq @accuracy-history)
                                  (/ (reduce + @accuracy-history) (count @accuracy-history))
                                  0.0)
                avg-latency-hours (if (seq @latency-history)
                                   (/ (reduce + @latency-history) (count @latency-history))
                                   0.0)
                avg-latency-days (/ avg-latency-hours 24)
                
                meets-accuracy? (>= overall-accuracy target-accuracy)
                meets-latency? (<= avg-latency-days max-latency-days)
                
                final-reputations (map :reputation (vals current-nodes))
                reputation-stats {:min (apply min final-reputations)
                                 :max (apply max final-reputations)
                                 :avg (/ (reduce + final-reputations) (count final-reputations))}]
            
            (perf/record-memory-snapshot)
            (perf/record-operation-time "full-simulation" total-time-ms)
            
            {:success? (and meets-accuracy? meets-latency?)
             :simulation-time-ms total-time-ms
             :overall-accuracy overall-accuracy
             :avg-latency-days avg-latency-days
             :meets-accuracy-target? meets-accuracy?
             :meets-latency-target? meets-latency?
             :total-questions (* sim-days questions-per-day)
             :total-transactions (count (:transactions @simulation-state))
             :final-reputation-stats reputation-stats
             :accuracy-history @accuracy-history
             :latency-history @latency-history
             :nodes-final-state (count current-nodes)
             :validation-status (if (and meets-accuracy? meets-latency?)
                                 "PASSED - Ready for deployment"
                                 "FAILED - Requires optimization")})
          
          ;; Run next day
          (let [day-result (<! (run-simulation-day current-nodes questions day))
                daily-accuracy (:daily-accuracy day-result)
                daily-latency (:avg-latency-hours day-result)]
            
            (swap! accuracy-history conj daily-accuracy)
            (swap! latency-history conj daily-latency)
            
            ;; Progress reporting every 30 days
            (when (zero? (mod day 30))
              (println (str "Simulation progress: Day " day "/" sim-days 
                           " - Accuracy: " (/ (Math/round (* daily-accuracy 10000)) 100) "%"
                           " - Latency: " (/ (Math/round (* daily-latency 100)) 100) "h")))
            
            ;; Continue simulation
            (recur (inc day) (:updated-nodes day-result))))))))

;; Quick simulation for faster testing
(defn run-quick-simulation
  "Runs a shortened simulation for faster validation during development."
  [num-nodes num-days]
  (go
    (let [quick-nodes (take num-nodes (vals (initialize-simulation-network)))
          quick-questions (take (* num-days questions-per-day) 
                               (generate-simulation-questions (* num-days questions-per-day)))
          accuracy-results (atom [])]
      
      (dotimes [day num-days]
        (let [day-result (<! (run-simulation-day (zipmap (map :pubkey quick-nodes) quick-nodes)
                                                quick-questions day))]
          (swap! accuracy-results conj (:daily-accuracy day-result))))
      
      {:overall-accuracy (if (seq @accuracy-results)
                          (/ (reduce + @accuracy-results) (count @accuracy-results))
                          0.0)
       :days-simulated num-days
       :nodes-simulated num-nodes})))

;; Validation test suite
(defn run-validation-suite
  "Runs comprehensive validation suite matching foundational architecture requirements."
  []
  (go
    (println "=== APStat Chain Phase 5 Validation Suite ===")
    
    ;; Test 1: Performance validation
    (println "\n1. Running performance validation...")
    (let [perf-report (<! (perf/run-performance-test-suite 
                            cons/calculate-convergence 
                            rep/process-attestation-rewards))]
      (println "Performance validation:" 
               (if (:all-bounds-met? (:validation perf-report)) "PASSED" "FAILED")))
    
    ;; Test 2: Consensus accuracy validation  
    (println "\n2. Running consensus accuracy validation...")
    (let [quick-sim-result (<! (run-quick-simulation 10 20))] ; 10 nodes, 20 days
      (println "Quick simulation accuracy:" (:overall-accuracy quick-sim-result)
               "Target:" target-accuracy))
    
    ;; Test 3: Full simulation validation
    (println "\n3. Running full simulation validation (this may take several minutes)...")
    (let [full-sim-result (<! (run-full-simulation))]
      (println "\n=== SIMULATION RESULTS ===")
      (println "Overall Accuracy:" (/ (Math/round (* (:overall-accuracy full-sim-result) 10000)) 100) "%")
      (println "Average Latency:" (/ (Math/round (* (:avg-latency-days full-sim-result) 100)) 100) " days")
      (println "Meets Accuracy Target (≥90%):" (:meets-accuracy-target? full-sim-result))
      (println "Meets Latency Target (≤7 days):" (:meets-latency-target? full-sim-result))
      (println "Total Questions Processed:" (:total-questions full-sim-result))
      (println "Total Transactions:" (:total-transactions full-sim-result))
      (println "Simulation Runtime:" (/ (:simulation-time-ms full-sim-result) 1000) " seconds")
      (println "\nValidation Status:" (:validation-status full-sim-result))
      
      full-sim-result)))

;; Comparison with Racket benchmarks
(defn compare-with-racket-benchmarks
  "Compares CLJS simulation results with expected Racket benchmarks."
  [simulation-result]
  (let [racket-expected {:accuracy 0.93 ; From simulation_results.md
                        :latency-days 5.8}
        
        accuracy-diff (- (:overall-accuracy simulation-result) (:accuracy racket-expected))
        latency-diff (- (:avg-latency-days simulation-result) (:latency-days racket-expected))
        
        accuracy-within-tolerance? (< (Math/abs accuracy-diff) 0.05) ; 5% tolerance
        latency-within-tolerance? (< (Math/abs latency-diff) 1.0)]    ; 1 day tolerance
    
    {:racket-benchmarks racket-expected
     :cljs-results {:accuracy (:overall-accuracy simulation-result)
                   :latency-days (:avg-latency-days simulation-result)}
     :differences {:accuracy-diff accuracy-diff
                  :latency-diff latency-diff}
     :within-tolerance? (and accuracy-within-tolerance? latency-within-tolerance?)
     :ready-for-deployment? (and (:success? simulation-result)
                                accuracy-within-tolerance?
                                latency-within-tolerance?)}))

;; Export simulation data
(defn export-simulation-results
  "Exports simulation results for analysis and documentation."
  [simulation-result]
  (let [export-data {:timestamp (js/Date.now)
                    :simulation-config {:total-nodes total-nodes
                                      :sim-days sim-days
                                      :questions-per-day questions-per-day
                                      :archetype-distribution archetypes}
                    :results simulation-result
                    :comparison (compare-with-racket-benchmarks simulation-result)}]
    
    (js/JSON.stringify (clj->js export-data) nil 2)))