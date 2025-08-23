(ns pok.reputation
  "Reputation system with time-windowed proportion calculation and accuracy-conditional bonuses.
   Ports algorithm fidelity from final_simulation.rkt and backend/app.py (lines 194-232)."
  (:require [clojure.core.async :as async :refer [go]]))

;; Clamp function implementation since cljs.core doesn't have it
(defn clamp [min-val val max-val]
  (max min-val (min val max-val)))

;; Constants from foundational architecture (Phase 8 optimized)
(def ^:const thought-leader-threshold 0.5)
(def ^:const thought-leader-bonus 2.5)
(def ^:const reputation-bounds [0.1 10.0])
(def ^:const max-replay-depth 50)  ; Phase 8: Cap replay depth for performance

;; Core reputation calculation functions

(defn calculate-proportion-before-attestation
  "Calculates the proportion of the winning answer at the time of attestation.
   Uses strict < timestamp filter to exclude self and later attestations.
   Phase 8: Caps replay depth to max-replay-depth for performance optimization."
  [attestations target-timestamp _target-hash]
  (let [;; Filter attestations strictly before target timestamp (excludes self)
        prior-attestations (filter #(< (:timestamp %) target-timestamp) attestations)
        ;; Phase 8: Cap replay depth for performance (most recent attestations)
        capped-attestations (if (> (count prior-attestations) max-replay-depth)
                             (take-last max-replay-depth 
                                       (sort-by :timestamp prior-attestations))
                             prior-attestations)
        ;; Group by hash and count
        hash-distribution (frequencies (map #(get-in % [:payload :hash]) capped-attestations))
        total-count (count capped-attestations)
        max-count (if (empty? hash-distribution) 0 (apply max (vals hash-distribution)))]
    (if (zero? total-count)
      0.0
      (/ max-count total-count))))

(defn calculate-reputation-bonus
  "Determines bonus multiplier based on proportion and final correctness.
   Returns thought-leader-bonus (2.5x) if prop < 0.5 AND final hash matches,
   otherwise returns 1.0 (no bonus). Enforces accuracy-conditional logic."
  [proportion-at-time final-hash attestation-hash]
  (if (and (< proportion-at-time thought-leader-threshold)
           (= final-hash attestation-hash))
    thought-leader-bonus
    1.0))

(defn calculate-reputation-weight
  "Calculates logarithmic weight based on current reputation.
   Uses log1p (log(1 + rep)) to avoid log(0) issues and provide diminishing returns."
  [current-reputation]
  (js/Math.log1p current-reputation))

(defn update-reputation
  "Updates attester reputation with bonus and weight calculations.
   Applies strict bounds clamping [0.1, 10.0] as per architecture."
  [current-reputation bonus weight]
  (let [delta (* bonus weight)
        new-reputation (+ current-reputation delta)]
    (clamp (first reputation-bounds) new-reputation (second reputation-bounds))))

(defn process-attestation-rewards
  "Processes reputation rewards for all attestations matching the final answer.
   Implements the full reputation update algorithm with:
   - Strict timestamp filtering (< not <=)
   - Proportion calculation at attestation time
   - Accuracy-conditional bonuses (only for matching final hash)
   - Logarithmic weight scaling
   - Bounded reputation updates"
  [attestations final-hash nodes]
  (go
    (let [;; Sort attestations by timestamp for chronological processing
          sorted-attestations (sort-by :timestamp attestations)
          ;; Filter only attestations that match the final correct answer
          correct-attestations (filter #(= (get-in % [:payload :hash]) final-hash) sorted-attestations)]
      (doseq [attestation correct-attestations]
        (let [attester-pubkey (:owner-pubkey attestation)
              attester-node (get nodes attester-pubkey)]
          (when attester-node
            (let [;; Calculate proportion at time of this attestation
                  proportion-at-time (calculate-proportion-before-attestation
                                       sorted-attestations
                                       (:timestamp attestation)
                                       final-hash)
                  ;; Determine bonus multiplier
                  bonus (calculate-reputation-bonus
                          proportion-at-time
                          final-hash
                          (get-in attestation [:payload :hash]))
                  ;; Calculate logarithmic weight
                  weight (calculate-reputation-weight (:reputation attester-node))
                  ;; Update reputation with bounds
                  new-reputation (update-reputation (:reputation attester-node) bonus weight)]
              ;; Return updated node (immutable update in practice)
              (assoc-in nodes [attester-pubkey :reputation] new-reputation))))))))

(defn get-median-reputation
  "Calculates median reputation for provisional node initialization.
   Used when adding new nodes to existing network."
  [nodes]
  (if (empty? nodes)
    1.0
    (let [reputations (map :reputation (vals nodes))
          sorted-reps (sort reputations)
          count (count sorted-reps)
          mid (quot count 2)]
      (if (odd? count)
        (nth sorted-reps mid)
        (/ (+ (nth sorted-reps (dec mid)) (nth sorted-reps mid)) 2.0)))))

;; Utility functions for testing and debugging

(defn reputation-stats
  "Returns statistical summary of reputation distribution in network."
  [nodes]
  (let [reputations (map :reputation (vals nodes))]
    (when (seq reputations)
      {:count (count reputations)
       :min (apply min reputations)
       :max (apply max reputations)
       :mean (/ (apply + reputations) (count reputations))
       :median (get-median-reputation nodes)})))

(defn validate-reputation-bounds
  "Validates that all node reputations are within acceptable bounds."
  [nodes]
  (let [reputations (map :reputation (vals nodes))
        [min-bound max-bound] reputation-bounds]
    (every? #(and (>= % min-bound) (<= % max-bound)) reputations)))
