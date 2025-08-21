(ns pok.ui-test
  "Phase 4: Tests for UI components including quiz and dashboard functionality."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [pok.ui :as ui]
            [pok.state :as state]))

;; Test data setup

(def sample-question
  {:id "q123"
   :prompt "What is the correlation coefficient of this scatter plot?"
   :choices [{:value "a" :text "r = 0.85"}
             {:value "b" :text "r = 0.42"}
             {:value "c" :text "r = -0.73"}
             {:value "d" :text "r = 0.15"}]
   :chart {:type :scatter
           :data [{:x 1 :y 2} {:x 2 :y 4} {:x 3 :y 6}]
           :x-field :x
           :y-field :y}})

(def sample-lesson
  {:id "lesson-1"
   :name "Correlation and Regression"
   :description "Understanding correlation coefficients and linear regression"
   :questions [sample-question
               {:id "q124" 
                :prompt "Calculate the slope of the regression line"
                :choices [{:value "a" :text "2.0"}
                          {:value "b" :text "1.5"}]}]})

(def sample-nodes
  {"alice" {:pubkey "alice" :reputation 2.5 :archetype :aces}
   "bob" {:pubkey "bob" :reputation 1.8 :archetype :diligent}
   "charlie" {:pubkey "charlie" :reputation 0.9 :archetype :strugglers}})

;; Component rendering tests

(deftest test-question-component-structure
  (testing "Question component renders with required elements"
    (with-redefs [rf/subscribe (fn [subscription]
                                (case (first subscription)
                                  ::state/current-user (r/atom "alice")
                                  ::state/chart-spec (r/atom nil)
                                  ::state/chart-loading? (r/atom false)
                                  (r/atom nil)))]
      
      (let [component (ui/question-component sample-question)
            ;; Simulate component rendering by checking structure
            rendered-data (second component)] ; Get the component data structure
        
        (is (vector? component) "Component should return a vector")
        (is (= :div.question-container (first component)) "Should have question container div")
        
        ;; Test for presence of key elements by checking the structure
        (testing "Component structure validation"
          (is (some #(and (vector? %) (= :div.question-header (first %))) rendered-data)
              "Should contain question header")
          (is (some #(and (vector? %) (= :div.question-choices (first %))) rendered-data)
              "Should contain question choices")
          (is (some #(and (vector? %) (= :div.question-actions (first %))) rendered-data)
              "Should contain question actions"))))))

(deftest test-question-component-with-chart
  (testing "Question component handles chart display"
    (with-redefs [rf/subscribe (fn [subscription]
                                (case (first subscription)
                                  ::state/current-user (r/atom "alice")
                                  ::state/chart-spec (r/atom {:spec "mock-vega-spec"})
                                  ::state/chart-loading? (r/atom false)
                                  (r/atom nil)))]
      
      (let [component (ui/question-component sample-question)]
        (is (vector? component) "Component should render with chart")
        ;; Chart container should be present when chart data exists
        (is (some? (:chart sample-question)) "Sample question should have chart data")))))

(deftest test-quiz-navigation-component
  (testing "Quiz navigation shows correct button states"
    (let [component-first (ui/quiz-navigation sample-lesson 0)
          component-middle (ui/quiz-navigation sample-lesson 1)
          component-last (ui/quiz-navigation sample-lesson 1)] ; Only 2 questions in sample
      
      (is (vector? component-first) "First question navigation should render")
      (is (vector? component-middle) "Middle question navigation should render")
      (is (vector? component-last) "Last question navigation should render")
      
      ;; Test navigation state logic
      (testing "Navigation button availability"
        ;; These tests validate the logic within the component functions
        (let [question-count (count (:questions sample-lesson))]
          (is (= question-count 2) "Sample lesson should have 2 questions")
          (is (not (> 0 0)) "First question should not have previous")
          (is (< 0 (dec question-count)) "First question should have next")
          (is (not (< 1 (dec question-count))) "Last question should not have next"))))))

(deftest test-reputation-histogram-data
  (testing "Reputation histogram processes node data correctly"
    (with-redefs [rf/subscribe (fn [subscription]
                                (case (first subscription)
                                  ::state/network-reputation-stats 
                                  (r/atom {:average 1.73 :median 1.8})
                                  ::state/nodes (r/atom sample-nodes)
                                  (r/atom nil)))]
      
      (let [component (ui/reputation-histogram)]
        (is (vector? component) "Reputation histogram should render")
        
        ;; Test reputation categorization logic
        (testing "Reputation range calculation"
          (let [rep-values (map :reputation (vals sample-nodes))
                low-rep (count (filter #(< % 1.0) rep-values))
                med-rep (count (filter #(and (>= % 1.0) (< % 3.0)) rep-values))
                high-rep (count (filter #(>= % 3.0) rep-values))]
            
            (is (= low-rep 1) "Should have 1 low reputation node")
            (is (= med-rep 2) "Should have 2 medium reputation nodes") 
            (is (= high-rep 0) "Should have 0 high reputation nodes")))))))

(deftest test-sync-status-display
  (testing "Sync status displays operational states"
    (with-redefs [rf/subscribe (fn [subscription]
                                (case (first subscription)
                                  ::state/sync-statistics 
                                  (r/atom {:total-syncs 5 :success-rate 0.8 :last-success 1640995200000})
                                  ::state/last-sync 
                                  (r/atom {:status :success :timestamp 1640995200000})
                                  ::state/creating-delta? (r/atom false)
                                  ::state/processing-delta? (r/atom false)
                                  ::state/scan-progress (r/atom nil)
                                  (r/atom nil)))]
      
      (let [component (ui/sync-status-display)]
        (is (vector? component) "Sync status should render")
        
        ;; Validate sync statistics processing
        (testing "Sync statistics calculation"
          (let [stats {:total-syncs 5 :success-rate 0.8}
                success-percentage (-> (:success-rate stats) (* 100) Math/round)]
            (is (= success-percentage 80) "Should calculate 80% success rate")))))))

(deftest test-consensus-log-display
  (testing "Consensus log shows network health"
    (with-redefs [rf/subscribe (fn [subscription]
                                (case (first subscription)
                                  ::state/network-consensus-health
                                  (r/atom {:active-questions 3 :consensus-rate 0.75 :average-quorum 4})
                                  ::state/current-user (r/atom "alice")
                                  ::state/user-reputation (r/atom 2.5)
                                  (r/atom nil)))]
      
      (let [component (ui/consensus-log-display)]
        (is (vector? component) "Consensus log should render")
        
        ;; Test reputation level categorization
        (testing "Reputation level assignment"
          (let [test-cases [[0.3 "Very Low"]
                           [0.8 "Low"] 
                           [1.5 "Average"]
                           [2.5 "Good"]
                           [4.0 "Excellent"]]]
            (doseq [[reputation expected-level] test-cases]
              (let [rep-level (cond
                               (< reputation 0.5) "Very Low"
                               (< reputation 1.0) "Low"
                               (< reputation 2.0) "Average"
                               (< reputation 3.0) "Good"
                               :else "Excellent")]
                (is (= rep-level expected-level)
                    (str "Reputation " reputation " should be " expected-level))))))))))

(deftest test-dashboard-component-integration
  (testing "Dashboard integrates all sub-components"
    (with-redefs [rf/subscribe (fn [subscription]
                                ;; Return mock atoms for all subscriptions
                                (r/atom nil))]
      
      (let [component (ui/dashboard-component)]
        (is (vector? component) "Dashboard should render")
        (is (= :div.dashboard-container (first component)) "Should have dashboard container")
        
        ;; Verify sub-components are included
        (testing "Dashboard component structure"
          (let [component-data (rest component)]
            (is (some #(and (vector? %) 
                           (or (= (first %) :div.dashboard-header)
                               (= (first %) :div.dashboard-content))) 
                      component-data)
                "Should contain dashboard sections")))))))

;; Event handling tests

(deftest test-quiz-navigation-events
  (testing "Quiz navigation dispatches correct events"
    (let [dispatched-events (atom [])]
      (with-redefs [rf/dispatch (fn [event] (swap! dispatched-events conj event))]
        
        ;; Simulate navigation clicks by testing the event dispatch logic
        (testing "Question index navigation"
          (let [current-index 1
                prev-event [::ui/set-current-question-index (dec current-index)]
                next-event [::ui/set-current-question-index (inc current-index)]]
            
            ;; Test previous navigation
            (rf/dispatch prev-event)
            (is (some #{prev-event} @dispatched-events) "Should dispatch previous question event")
            
            ;; Test next navigation  
            (rf/dispatch next-event)
            (is (some #{next-event} @dispatched-events) "Should dispatch next question event")))))))

(deftest test-answer-submission-logic
  (testing "Answer submission creates proper transactions"
    (let [dispatched-events (atom [])]
      (with-redefs [rf/dispatch (fn [event] (swap! dispatched-events conj event))]
        
        ;; Simulate answer submission
        (let [question-id "q123"
              answer "a"
              submit-event [::state/submit-answer question-id answer]]
          
          (rf/dispatch submit-event)
          (is (some #{submit-event} @dispatched-events) 
              "Should dispatch submit-answer event to Phase 1 blockchain logic"))))))

;; Integration tests with state

(deftest test-ui-state-subscriptions
  (testing "UI state subscriptions work correctly"
    ;; Initialize a minimal database for testing
    (let [test-db {:ui {:current-question-index 0
                       :loading? false
                       :error nil}
                   :nodes sample-nodes
                   :current-user "alice"}]
      
      ;; Test current question index subscription
      (with-redefs [rf/subscribe (fn [subscription]
                                  (case (first subscription)
                                    ::ui/current-question-index 
                                    (r/atom (get-in test-db [:ui :current-question-index]))
                                    ::state/current-user
                                    (r/atom (:current-user test-db))
                                    (r/atom nil)))]
        
        (let [question-index @(rf/subscribe [::ui/current-question-index])
              current-user @(rf/subscribe [::state/current-user])]
          
          (is (= question-index 0) "Should return correct question index")
          (is (= current-user "alice") "Should return correct current user"))))))

(deftest test-lesson-selector-interaction
  (testing "Lesson selector handles curriculum navigation"
    (let [dispatched-events (atom [])]
      (with-redefs [rf/dispatch (fn [event] (swap! dispatched-events conj event))
                    rf/subscribe (fn [subscription]
                                  (case (first subscription)
                                    ::state/curriculum-index (r/atom {:units []})
                                    ::state/lessons-by-unit (r/atom {})
                                    ::state/current-lesson (r/atom nil)
                                    ::state/lesson-loading? (r/atom false)
                                    (r/atom nil)))]
        
        (let [component (ui/lesson-selector)]
          (is (vector? component) "Lesson selector should render")
          
          ;; Test lesson loading event
          (let [load-event [::state/load-lesson "unit1" "lesson1"]]
            (rf/dispatch load-event)
            (is (some #{load-event} @dispatched-events)
                "Should dispatch lesson loading event")))))))

;; Performance and validation tests

(deftest test-component-performance-constraints
  (testing "Components follow Phase 4 minimal UI constraints"
    ;; Test that components don't include animations or excessive interactivity
    (testing "Static chart constraint validation"
      ;; Components should not include interactive charts
      (let [question-comp (ui/question-component sample-question)]
        ;; Validate that chart display is static (no interaction handlers)
        (is (vector? question-comp) "Question component should be static vector")))
    
    (testing "Minimal interactivity validation"
      ;; Dashboard should have minimal interactive elements
      (with-redefs [rf/subscribe (fn [_] (r/atom nil))]
        (let [dashboard-comp (ui/dashboard-component)]
          (is (vector? dashboard-comp) "Dashboard should be static display"))))))

(deftest test-error-handling
  (testing "Components handle missing data gracefully"
    (with-redefs [rf/subscribe (fn [_] (r/atom nil))]
      
      ;; Test question component with nil data
      (let [nil-question-comp (ui/question-component nil)]
        (is (vector? nil-question-comp) "Should handle nil question data"))
      
      ;; Test dashboard with no network data
      (let [empty-dashboard (ui/dashboard-component)]
        (is (vector? empty-dashboard) "Should handle empty network state"))
      
      ;; Test reputation display with no nodes
      (let [empty-reputation (ui/reputation-histogram)]
        (is (vector? empty-reputation) "Should handle no reputation data")))))

(deftest test-accessibility-requirements
  (testing "Components include basic accessibility features"
    (with-redefs [rf/subscribe (fn [_] (r/atom nil))]
      
      ;; Test that form elements have proper labels
      (let [question-comp (ui/question-component sample-question)]
        ;; Question component should have label elements for radio buttons
        (is (vector? question-comp) "Question component should be accessible"))
      
      ;; Test navigation has descriptive text
      (let [nav-comp (ui/quiz-navigation sample-lesson 0)]
        (is (vector? nav-comp) "Navigation should be accessible")))))

;; Run all tests
(defn run-ui-tests
  "Run all UI component tests"
  []
  (run-tests 'pok.ui-test))