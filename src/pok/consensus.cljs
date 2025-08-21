(ns pok.consensus
  "Consensus mechanisms with dynamic quorum and convergence calculation.
   Ports logic from final_simulation.rkt (lines 52-59) and app.py (lines 116-141)."
  (:require [clojure.core.async :as async :refer [go]]
            [pok.reputation :as rep]))

;; Constants from foundational architecture
(def ^:const quorum-convergence-threshold 0.7)
(def ^:const ap-reveal-weight 10.0)
(def ^:const base-quorum-size 3)
(def ^:const quorum-fraction 0.3)

;; Core consensus calculation functions

(defn calculate-convergence
  "Calculates convergence for a specific question based on attestation distribution.
   Returns the proportion of the most popular answer (max_count / total_count).
   Supports weighted calculation using reputation-based weights."
  ([attestations]
   (calculate-convergence attestations false {}))
  ([attestations weighted? nodes]
   (if (empty? attestations)
     0.0
     (let [;; Group attestations by hash and calculate weights
           hash-weights (reduce
                          (fn [acc attestation]
                            (let [hash (get-in attestation [:payload :hash])
                                  weight (cond
                                           ;; AP reveal gets maximum weight
                                           (= (:type attestation) "ap_reveal") ap-reveal-weight
                                           ;; Use reputation-based weight if enabled
                                           weighted? (rep/calculate-reputation-weight
                                                       (:reputation (get nodes (:owner-pubkey attestation) {:reputation 1.0})))
                                           ;; Default uniform weight
                                           :else 1.0)]
                              (update acc hash (fnil + 0) weight)))
                          {}
                          attestations)
           total-weight (apply + (vals hash-weights))
           max-weight (if (empty? hash-weights) 0 (apply max (vals hash-weights)))]
       (if (zero? total-weight)
         0.0
         (/ max-weight total-weight))))))

(defn calculate-dynamic-quorum
  "Calculates dynamic quorum size based on active node count.
   Formula: max(base-quorum-size, floor(quorum-fraction * active-nodes))
   Ensures minimum viability for small classes while scaling for larger groups."
  [active-node-count]
  (max base-quorum-size (js/Math.floor (* quorum-fraction active-node-count))))

(defn calculate-progressive-quorum
  "Calculates progressive quorum based on curriculum progress.
   Earlier questions require smaller quorum (2), later questions require larger (4).
   Matches Racket implementation logic from final_simulation.rkt line 71."
  [question-index total-curriculum-size]
  (if (< question-index (/ total-curriculum-size 2))
    2
    4))

(defn get-attestations-for-question
  "Extracts all attestations for a specific question from transactions.
   Filters for 'attestation' and 'ap_reveal' transaction types."
  [transactions question-id]
  (filter (fn [txn]
            (and (= (:question-id txn) question-id)
                 (contains? #{"attestation" "ap_reveal"} (:type txn))))
          transactions))

(defn check-consensus-readiness
  "Determines if a question is ready for PoK block proposal.
   Requires minimum attestation count AND convergence above threshold."
  [transactions question-id min-attestations]
  (let [attestations (get-attestations-for-question transactions question-id)
        attestation-count (count attestations)
        convergence (calculate-convergence attestations)]
    {:ready? (and (>= attestation-count min-attestations)
                  (>= convergence quorum-convergence-threshold))
     :attestation-count attestation-count
     :convergence convergence
     :min-required min-attestations
     :threshold quorum-convergence-threshold}))

(defn find-consensus-answer
  "Finds the consensus answer hash for a question based on attestation weights.
   Returns the hash with the highest total weight, or nil if no attestations."
  [attestations weighted? nodes]
  (if (empty? attestations)
    nil
    (let [hash-weights (reduce
                         (fn [acc attestation]
                           (let [hash (get-in attestation [:payload :hash])
                                 weight (cond
                                          (= (:type attestation) "ap_reveal") ap-reveal-weight
                                          weighted? (rep/calculate-reputation-weight
                                                      (:reputation (get nodes (:owner-pubkey attestation) {:reputation 1.0})))
                                          :else 1.0)]
                             (update acc hash (fnil + 0) weight)))
                         {}
                         attestations)]
      (when (seq hash-weights)
        (key (apply max-key val hash-weights))))))

(defn validate-pok-consensus
  "Validates that a completion transaction meets consensus requirements.
   Checks both quorum size and convergence threshold for mining eligibility."
  [completion-txn all-transactions active-node-count curriculum-size]
  (let [question-id (:question-id completion-txn)
        ;; Calculate required quorum (dynamic and progressive)
        dynamic-quorum (calculate-dynamic-quorum active-node-count)
        progress-quorum (calculate-progressive-quorum 
                          ;; Assume question index from question-id or use default
                          0 curriculum-size)
        min-quorum (max dynamic-quorum progress-quorum)
        
        ;; Check consensus
        consensus-check (check-consensus-readiness all-transactions question-id min-quorum)]
    
    (assoc consensus-check
           :dynamic-quorum dynamic-quorum
           :progress-quorum progress-quorum
           :final-quorum min-quorum
           :question-id question-id)))

;; Async consensus processing

(defn process-consensus-async
  "Asynchronously processes consensus for multiple questions.
   Returns channel with consensus results for batch processing."
  [questions transactions nodes]
  (go
    (for [question-id questions]
      (let [attestations (get-attestations-for-question transactions question-id)
            convergence (calculate-convergence attestations true nodes)
            consensus-answer (find-consensus-answer attestations true nodes)]
        {:question-id question-id
         :convergence convergence
         :consensus-answer consensus-answer
         :attestation-count (count attestations)}))))

;; Network-wide consensus statistics

(defn calculate-network-consensus-health
  "Calculates overall network consensus health metrics.
   Provides insight into consensus quality across all active questions."
  [questions transactions nodes]
  (let [question-stats (for [question-id questions]
                         (let [attestations (get-attestations-for-question transactions question-id)
                               convergence (calculate-convergence attestations true nodes)]
                           {:question-id question-id
                            :convergence convergence
                            :attestation-count (count attestations)}))
        convergences (map :convergence question-stats)
        attestation-counts (map :attestation-count question-stats)]
    {:total-questions (count questions)
     :avg-convergence (if (seq convergences) (/ (apply + convergences) (count convergences)) 0.0)
     :min-convergence (if (seq convergences) (apply min convergences) 0.0)
     :max-convergence (if (seq convergences) (apply max convergences) 0.0)
     :avg-attestations (if (seq attestation-counts) (/ (apply + attestation-counts) (count attestation-counts)) 0.0)
     :questions-above-threshold (count (filter #(>= % quorum-convergence-threshold) convergences))
     :consensus-ratio (if (seq convergences)
                        (/ (count (filter #(>= % quorum-convergence-threshold) convergences))
                           (count convergences))
                        0.0)}))

;; Utility functions for debugging

(defn debug-consensus-state
  "Returns detailed consensus state for debugging and validation."
  [question-id transactions nodes]
  (let [attestations (get-attestations-for-question transactions question-id)
        hash-distribution (frequencies (map #(get-in % [:payload :hash]) attestations))
        convergence (calculate-convergence attestations true nodes)
        consensus-answer (find-consensus-answer attestations true nodes)]
    {:question-id question-id
     :attestations (count attestations)
     :hash-distribution hash-distribution
     :convergence convergence
     :consensus-answer consensus-answer
     :meets-threshold? (>= convergence quorum-convergence-threshold)}))
