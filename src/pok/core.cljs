(ns pok.core
  "Core namespace for PoK blockchain application initialization."
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [pok.state :as state]
            [pok.reputation :as rep]
            [pok.consensus :as consensus]))

(defn mount-root
  "Placeholder mount function - UI components will be implemented in Phase 4"
  []
  (rdom/render [:div "PoK Chain - Phase 1 Complete"] (.getElementById js/document "app")))

(defn ^:export init
  "Application initialization function"
  []
  (rf/dispatch-sync [::state/initialize-db])
  (mount-root)
  (js/console.log "PoK Chain Phase 1 initialized"))

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

;; Export for global access in development
(set! js/pokCore #js {:createTestNode create-test-node
                      :testReputation test-reputation-calculation
                      :testConsensus test-consensus-calculation})
