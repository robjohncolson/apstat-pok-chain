(ns pok.sync-test
  "Integration tests for Phase 3 synchronization with Re-frame state management."
  (:require [cljs.test :refer-macros [deftest is testing run-tests async]]
            [re-frame.core :as rf]
            [day8.re-frame.test :as rf-test]
            [clojure.core.async :as async-core :refer [go <!]]
            [pok.state :as state]
            [pok.qr :as qr]
            [pok.delta :as delta]))

;; Test fixtures and helpers
(defn create-test-state []
  {:nodes {"alice" {:pubkey "alice"
                    :reputation 2.0
                    :mempool [{:id "tx1" :timestamp 1000 :question-id "q1"}]
                    :chain [{:hash "block1" :timestamp 2000}]}
           "bob" {:pubkey "bob"
                  :reputation 1.5
                  :mempool []
                  :chain []}}
   :sync-history []
   :sync {:current-delta nil :peer-timestamps {}}
   :ui {:qr-scanning? false
        :processing-delta? false
        :creating-delta? false}})

(defn create-test-delta []
  {:version "1.0"
   :timestamp 3000
   :merkle-root "test-merkle-root"
   :transactions [{:id "tx2" :timestamp 2500 :owner-pubkey "bob" :question-id "q2"}]
   :blocks [{:hash "block2" :timestamp 2800 :proposer "bob"}]})

(deftest test-qr-scan-events
  (testing "QR scan initialization"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/start-qr-scan nil nil "expected-root"])
      
      (let [db @(rf/subscribe [::state/db])]
        (is (get-in db [:ui :qr-scanning?]))
        (is (contains? (get-in db [:ui :scan-progress]) :type))
        (is (string? (get-in db [:ui :scan-id]))))))
  
  (testing "QR scan cancellation"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/start-qr-scan nil nil "expected-root"])
      (rf/dispatch [::state/cancel-qr-scan])
      
      (let [db @(rf/subscribe [::state/db])]
        (is (not (get-in db [:ui :qr-scanning?])))
        (is (nil? (get-in db [:ui :scan-progress]))))))
  
  (testing "Scan progress updates"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/update-scan-progress {:type :progress :received 2 :expected 3}])
      
      (let [progress @(rf/subscribe [::state/scan-progress])]
        (is (= (:type progress) :progress))
        (is (= (:received progress) 2))
        (is (= (:expected progress) 3))))))

(deftest test-delta-creation-events
  (testing "Delta creation request"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/create-sync-delta 1000])
      
      (let [db @(rf/subscribe [::state/db])]
        (is (get-in db [:ui :creating-delta?])))))
  
  (testing "Delta creation completion"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (let [test-delta-result {:success true :delta (create-test-delta) :size 250}]
        (rf/dispatch [::state/sync-delta-created test-delta-result])
        
        (let [db @(rf/subscribe [::state/db])]
          (is (not (get-in db [:ui :creating-delta?])))
          (is (get-in db [:ui :delta-ready?]))
          (is (= (get-in db [:sync :current-delta]) test-delta-result)))))))

(deftest test-delta-merge-events
  (testing "Delta merge processing"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/process-scanned-delta (create-test-delta)])
      
      (let [db @(rf/subscribe [::state/db])]
        (is (get-in db [:ui :processing-delta?])))))
  
  (testing "Successful delta merge"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (let [merge-result {:success true :state (create-test-state)}]
        (rf/dispatch [::state/delta-merge-completed merge-result])
        
        (let [db @(rf/subscribe [::state/db])]
          (is (not (get-in db [:ui :processing-delta?])))
          (is (contains? (get-in db [:ui :last-sync]) :timestamp))
          (is (= (get-in db [:ui :last-sync :status]) :success))))))
  
  (testing "Failed delta merge"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (let [merge-result {:success false :error "Test merge failure"}]
        (rf/dispatch [::state/delta-merge-completed merge-result])
        
        (let [db @(rf/subscribe [::state/db])]
          (is (not (get-in db [:ui :processing-delta?])))
          (is (contains? (get-in db [:ui :error]) "Test merge failure")))))))

(deftest test-sync-subscriptions
  (testing "QR scanning state subscription"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/start-qr-scan nil nil "root"])
      
      (let [scanning? @(rf/subscribe [::state/qr-scanning?])]
        (is scanning?))))
  
  (testing "Sync progress subscription"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/update-scan-progress {:type :progress :received 1 :expected 3}])
      
      (let [progress @(rf/subscribe [::state/scan-progress])]
        (is (= (:type progress) :progress)))))
  
  (testing "Delta ready subscription"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/sync-delta-created {:success true :delta {}}])
      
      (let [ready? @(rf/subscribe [::state/delta-ready?])]
        (is ready?))))
  
  (testing "Current delta subscription"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (let [test-delta-result {:success true :delta (create-test-delta)}]
        (rf/dispatch [::state/sync-delta-created test-delta-result])
        
        (let [current-delta @(rf/subscribe [::state/current-delta])]
          (is (= current-delta test-delta-result)))))))

(deftest test-sync-statistics
  (testing "Sync statistics calculation"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      ;; Simulate some sync history
      (rf/dispatch-sync [::state/delta-merge-completed 
                         {:success true :state (assoc (create-test-state)
                                                      :sync-history 
                                                      [{:timestamp 1000 :status :success}
                                                       {:timestamp 2000 :status :failed}
                                                       {:timestamp 3000 :status :success}])}])
      
      (let [stats @(rf/subscribe [::state/sync-statistics])]
        (is (= (:total-syncs stats) 3))
        (is (= (:successful-syncs stats) 2))
        (is (= (:failed-syncs stats) 1))
        (is (= (:success-rate stats) (/ 2 3)))
        (is (= (:last-success stats) 3000)))))
  
  (testing "Empty sync history statistics"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      
      (let [stats @(rf/subscribe [::state/sync-statistics])]
        (is (= (:total-syncs stats) 0))
        (is (= (:success-rate stats) 0.0))
        (is (nil? (:last-success stats)))))))

(deftest test-error-handling
  (testing "QR scan error handling"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/qr-scan-completed "scan-123" 
                    {:success false :error "Camera not available"}])
      
      (let [db @(rf/subscribe [::state/db])]
        (is (not (get-in db [:ui :qr-scanning?])))
        (is (contains? (get-in db [:ui :error]) "Camera not available")))))
  
  (testing "Delta processing error handling"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/delta-merge-completed 
                    {:success false :error "Invalid delta format"}])
      
      (let [db @(rf/subscribe [::state/db])]
        (is (not (get-in db [:ui :processing-delta?])))
        (is (contains? (get-in db [:ui :error]) "Invalid delta format")))))
  
  (testing "Error clearing"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/set-error "Test error"])
      (rf/dispatch [::state/clear-error])
      
      (let [error @(rf/subscribe [::state/error])]
        (is (nil? error))))))

(deftest test-state-integration
  (testing "Sync state preservation during merge"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      ;; Set up initial state
      (rf/dispatch-sync [::state/add-node "alice" :aces 2.0])
      (rf/dispatch-sync [::state/set-current-user "alice"])
      
      ;; Simulate successful delta merge
      (let [updated-state (-> (create-test-state)
                             (assoc-in [:nodes "alice" :reputation] 2.5)
                             (assoc :sync-history [{:timestamp 4000 :status :success}]))]
        (rf/dispatch [::state/delta-merge-completed {:success true :state updated-state}])
        
        (let [db @(rf/subscribe [::state/db])
              alice-rep @(rf/subscribe [::state/user-reputation "alice"])]
          (is (= alice-rep 2.5)) ; Reputation should be updated
          (is (> (count (:sync-history db)) 0)))))) ; Sync history preserved

(deftest test-concurrent-operations
  (testing "Multiple scan operations"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      ;; Start first scan
      (rf/dispatch [::state/start-qr-scan nil nil "root1"])
      (let [first-scan-id (get-in @(rf/subscribe [::state/db]) [:ui :scan-id])]
        ;; Start second scan (should cancel first)
        (rf/dispatch [::state/start-qr-scan nil nil "root2"])
        (let [second-scan-id (get-in @(rf/subscribe [::state/db]) [:ui :scan-id])]
          (is (not= first-scan-id second-scan-id))))))
  
  (testing "Scan and delta creation concurrency"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch [::state/start-qr-scan nil nil "root"])
      (rf/dispatch [::state/create-sync-delta 1000])
      
      (let [db @(rf/subscribe [::state/db])]
        (is (get-in db [:ui :qr-scanning?]))
        (is (get-in db [:ui :creating-delta?]))))))

(deftest test-data-consistency
  (testing "Sync history ordering"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      ;; Add multiple sync events
      (let [state-with-history (-> (create-test-state)
                                  (assoc :sync-history 
                                         [{:timestamp 1000 :status :success}
                                          {:timestamp 3000 :status :success}
                                          {:timestamp 2000 :status :failed}]))]
        (rf/dispatch [::state/delta-merge-completed {:success true :state state-with-history}])
        
        (let [history @(rf/subscribe [::state/sync-history])]
          ;; History should maintain insertion order
          (is (= (map :timestamp history) [1000 3000 2000]))))))
  
  (testing "Node state consistency after merge"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      (rf/dispatch-sync [::state/add-node "alice" :aces 1.0])
      
      ;; Merge delta that updates alice's state
      (let [merged-state (-> (create-test-state)
                            (assoc-in [:nodes "alice" :reputation] 2.5)
                            (assoc-in [:nodes "alice" :progress] 5))]
        (rf/dispatch [::state/delta-merge-completed {:success true :state merged-state}])
        
        (let [alice-node @(rf/subscribe [::state/current-user-node])]
          ;; Note: current-user might not be set, so test node directly
          (let [nodes @(rf/subscribe [::state/nodes])
                alice (get nodes "alice")]
            (is (= (:reputation alice) 2.5))
            (is (= (:progress alice) 5))))))))

;; Performance and stress tests
(deftest test-performance-characteristics
  (testing "Large delta handling"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      ;; Create large delta
      (let [large-delta {:transactions (repeat 100 {:id (str "tx-" (rand-int 10000))
                                                     :timestamp (+ 1000 (rand-int 5000))})
                         :blocks (repeat 20 {:hash (str "block-" (rand-int 10000))
                                            :timestamp (+ 2000 (rand-int 3000))})
                         :merkle-root "large-root"}]
        (rf/dispatch [::state/process-scanned-delta large-delta])
        
        ;; Should handle without errors
        (let [db @(rf/subscribe [::state/db])]
          (is (get-in db [:ui :processing-delta?]))))))
  
  (testing "Frequent scan updates"
    (rf-test/run-test-sync
      (rf/dispatch [::state/initialize-db])
      ;; Simulate rapid progress updates
      (doseq [i (range 10)]
        (rf/dispatch [::state/update-scan-progress {:type :progress 
                                                    :received i 
                                                    :expected 10}]))
      
      (let [final-progress @(rf/subscribe [::state/scan-progress])]
        (is (= (:received final-progress) 9))
        (is (= (:expected final-progress) 10))))))

(defn run-sync-tests []
  (run-tests 'pok.sync-test))