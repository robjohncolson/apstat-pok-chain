(ns pok.ui
  "Phase 4: User Interface Layer for PoK blockchain quiz and dashboard components.
   Implements reactive Reagent components with static Vega charts and minimal interactivity."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string]
            [clojure.core.async :as async :refer [go <!]]
            [pok.state :as state]
            [pok.renderer :as renderer]))

;; Chart Component with Vega-Lite Integration

(defn chart-component
  "Renders Vega-Lite charts from EDN attachments with performance logging."
  [question-id attachments]
  (let [chart-state (r/atom {:loading true :error nil :render-time nil})
        element-id (str "chart-" question-id)
        mounted? (r/atom false)]
    
    (r/create-class
     {:component-did-mount
      (fn [_this]
        (reset! mounted? true)
        (js/console.log "Chart component mounted for question:" question-id)
        (js/console.log "Attachments received:" (clj->js attachments))
        (if attachments
          (do
            (js/console.log "Attachment keys:" (clj->js (keys attachments)))
            (when (or (:table attachments) 
                      (:chart-type attachments))
              (js/console.log "Valid chart attachments detected...")
              ;; Multiple attempts to ensure DOM is ready
              (js/setTimeout
                (fn []
                  (when @mounted?
                    (js/console.log "Attempting chart render - DOM should be ready...")
                    (go
                      (let [render-result (<! (renderer/render-edn-chart element-id attachments))]
                        (when @mounted?  ; Check if still mounted
                          (js/console.log "Chart render result:" (clj->js render-result))
                          (swap! chart-state assoc 
                                 :loading false
                                 :error (when-not (:success render-result) (:error render-result))
                                 :render-time (:render-time render-result))
                          (when (:success render-result)
                            (js/console.log "Chart rendered successfully in" 
                                           (:render-time render-result) "ms")))))))
                200))) ; Longer delay to ensure DOM is fully ready
          (do
            (js/console.log "No attachments found for question:" question-id)
            (swap! chart-state assoc :loading false))))
      
      :component-will-unmount
      (fn [_this]
        (reset! mounted? false)
        (js/console.log "Unmounting chart component for question:" question-id))
      
      :reagent-render
      (fn [_question-id _attachments]
        [:div.chart-container
         [:div.chart-wrapper
          [:div.chart-display {:id element-id}]
          (cond
            (:loading @chart-state)
            [:div.chart-loading "Loading chart..."]
            
            (:error @chart-state)
            [:div.chart-error 
             [:p "Chart rendering failed"]
             [:details
              [:summary "Error details"]
              [:pre (:error @chart-state)]]]
            
            (:render-time @chart-state)
            [:div.chart-perf-info
             [:small (str "Rendered in " (:render-time @chart-state) "ms")]])]])})))

;; UI Component: Quiz Question Display

(defn question-component
  "Renders a quiz question with prompt, multiple choice options, and static Vega chart.
   Dispatches to Phase 1 blockchain logic on answer submission."
  [question-data]
  (let [current-user @(rf/subscribe [::state/current-user])
        selected-answer (r/atom nil)]
    
    [:div.question-container
     [:div.question-header
      [:h3.question-title (:prompt question-data)]
      [:p.question-id (str "Question ID: " (:id question-data))]]
     
     ;; Vega chart display from EDN attachments (only when chart-type is specified)
     (when-let [attachments (:attachments question-data)]
       (when (:chart-type attachments)
         [chart-component (:id question-data) attachments]))
     
     ;; Multiple choice options
     [:div.question-choices
      (doall
        (map-indexed 
          (fn [index choice]
            ^{:key index}
            [:label.choice-option
             [:input.choice-input
              {:type "radio"
               :name (str "question-" (:id question-data))
               :value (:value choice)
               :on-change #(reset! selected-answer (:value choice))}]
             [:span.choice-text (:text choice)]])
          (:choices question-data)))]
     
     ;; Submit button
     [:div.question-actions
      [:button.submit-btn
       {:disabled (or (nil? @selected-answer) (nil? current-user))
        :on-click #(when @selected-answer
                    ;; Dispatch to Phase 1 blockchain logic
                    (rf/dispatch [::state/submit-answer (:id question-data) @selected-answer])
                    ;; Reset selection after submission
                    (reset! selected-answer nil))}
       "Submit Answer"]
      
      (when (nil? current-user)
        [:p.user-warning "Please set up a user profile to submit answers"])]]))

(defn quiz-navigation
  "Navigation component for moving between questions in a lesson."
  [lesson-data current-question-index]
  (let [question-count (count (:questions lesson-data))
        has-prev? (> current-question-index 0)
        has-next? (< current-question-index (dec question-count))]
    
    [:div.quiz-navigation
     [:div.nav-info
      [:span.question-counter 
       (str "Question " (inc current-question-index) " of " question-count)]]
     
     [:div.nav-buttons
      [:button.nav-btn.prev-btn
       {:disabled (not has-prev?)
        :on-click #(rf/dispatch [::set-current-question-index (dec current-question-index)])}
       "← Previous"]
      
      [:button.nav-btn.next-btn
       {:disabled (not has-next?)
        :on-click #(rf/dispatch [::set-current-question-index (inc current-question-index)])}
       "Next →"]]]))

(defn quiz-progress
  "Shows progress through current lesson."
  [lesson-data user-progress]
  (let [total-questions (count (:questions lesson-data))
        completed (or user-progress 0)
        percentage (if (pos? total-questions) 
                     (* 100 (/ completed total-questions)) 
                     0)]
    
    [:div.quiz-progress
     [:div.progress-header
      [:h4 "Progress: " (:name lesson-data)]
      [:span.progress-text (str completed "/" total-questions " completed")]]
     
     [:div.progress-bar
      [:div.progress-fill 
       {:style {:width (str percentage "%")}}]]
     
     [:div.progress-stats
      [:span.completion-rate (str (Math/round percentage) "% complete")]]]))

;; UI Component: Dashboard with Reputation and Sync Status

(defn reputation-histogram
  "Displays reputation distribution across the network as a simple histogram."
  []
  (let [network-stats @(rf/subscribe [::state/network-reputation-stats])
        nodes @(rf/subscribe [::state/nodes])]
    
    [:div.reputation-display
     [:h4 "Network Reputation Distribution"]
     
     (if (seq nodes)
       [:div.reputation-content
        ;; Simple text-based histogram (no complex interactivity)
        [:div.reputation-stats
         [:p "Network Statistics:"]
         [:ul.stats-list
          [:li (str "Total Nodes: " (count nodes))]
          [:li (str "Average Reputation: " 
                   (when network-stats 
                     (-> (:average network-stats) 
                         (* 1000) 
                         Math/round 
                         (/ 1000))))]
          [:li (str "Median Reputation: " 
                   (when network-stats 
                     (-> (:median network-stats) 
                         (* 1000) 
                         Math/round 
                         (/ 1000))))]]]
        
        ;; Simple reputation ranges display
        [:div.reputation-ranges
         [:p "Reputation Ranges:"]
         (let [rep-values (map :reputation (vals nodes))
               low-rep (count (filter #(< % 1.0) rep-values))
               med-rep (count (filter #(and (>= % 1.0) (< % 3.0)) rep-values))
               high-rep (count (filter #(>= % 3.0) rep-values))]
           [:ul.range-list
            [:li (str "Low (< 1.0): " low-rep " nodes")]
            [:li (str "Medium (1.0-3.0): " med-rep " nodes")]
            [:li (str "High (≥ 3.0): " high-rep " nodes")]])]]
       
       [:div.no-data "No reputation data available"])]))

(defn sync-status-display
  "Shows synchronization status and recent sync history from Phase 3."
  []
  (let [sync-stats @(rf/subscribe [::state/sync-statistics])
        last-sync @(rf/subscribe [::state/last-sync])
        creating-delta? @(rf/subscribe [::state/creating-delta?])
        processing-delta? @(rf/subscribe [::state/processing-delta?])
        scan-progress @(rf/subscribe [::state/scan-progress])]
    
    [:div.sync-status
     [:h4 "Synchronization Status"]
     
     ;; Current sync operations
     [:div.sync-operations
      (when creating-delta?
        [:div.sync-operation.creating
         [:span.status-indicator "🔄"] 
         [:span.status-text "Creating sync delta..."]])
      
      (when processing-delta?
        [:div.sync-operation.processing
         [:span.status-indicator "⚡"] 
         [:span.status-text "Processing received delta..."]])
      
      (when scan-progress
        [:div.sync-operation.scanning
         [:span.status-indicator "📱"] 
         [:span.status-text 
          (case (:type scan-progress)
            :initializing "Initializing QR scanner..."
            :progress (str "Scanning QR codes: " (:received scan-progress) "/" (:expected scan-progress))
            :completed "QR scan completed"
            "Scanning...")]])]
     
     ;; Sync statistics
     [:div.sync-statistics
      [:p "Sync History:"]
      (if sync-stats
        [:ul.sync-stats-list
         [:li (str "Total Syncs: " (:total-syncs sync-stats))]
         [:li (str "Success Rate: " 
                  (-> (:success-rate sync-stats) 
                      (* 100) 
                      Math/round) "%")]
         [:li (str "Last Success: " 
                  (if (:last-success sync-stats)
                    (js/Date. (:last-success sync-stats))
                    "Never"))]]
        [:p "No sync history available"])]
     
     ;; Last sync details
     (when last-sync
       [:div.last-sync
        [:p (str "Last Sync: " (:status last-sync) " at " 
                (js/Date. (:timestamp last-sync)))]])]))

(defn consensus-log-display
  "Shows minimal consensus and resolution logs from Phase 3 sync operations."
  []
  (let [network-health @(rf/subscribe [::state/network-consensus-health])
        current-user @(rf/subscribe [::state/current-user])
        user-reputation @(rf/subscribe [::state/user-reputation current-user])]
    
    [:div.consensus-logs
     [:h4 "Consensus Activity"]
     
     ;; Network health summary
     [:div.network-health
      [:p "Network Consensus Health:"]
      (if network-health
        [:ul.health-list
         [:li (str "Active Questions: " (:active-questions network-health))]
         [:li (str "Consensus Rate: " 
                  (-> (:consensus-rate network-health) 
                      (* 100) 
                      Math/round) "%")]
         [:li (str "Average Quorum: " (:average-quorum network-health))]]
        [:p "No consensus data available"])]
     
     ;; User's reputation status
     (when current-user
       [:div.user-status
        [:p (str "Your Reputation: " 
                (-> (or user-reputation 1.0) 
                    (* 1000) 
                    Math/round 
                    (/ 1000)))]
        
        ;; Simple reputation level indicator
        (let [rep-level (cond
                         (< user-reputation 0.5) "Very Low"
                         (< user-reputation 1.0) "Low"
                         (< user-reputation 2.0) "Average"
                         (< user-reputation 3.0) "Good"
                         :else "Excellent")]
          [:span.reputation-level {:class (str "level-" (clojure.string/lower-case rep-level))}
           rep-level])])
     
     ;; Recent consensus events (simplified)
     [:div.recent-events
      [:p "Recent Activity:"]
      [:ul.event-list
       [:li "✓ Consensus reached on Question A123"]
       [:li "⚖️ Reputation bonus for correct minority attestation"]
       [:li "🔗 Block mined with 3 transactions"]
       [:li "📊 Network synchronized via QR"]]]]))

;; Main Dashboard Component

(defn dashboard-component
  "Main dashboard combining reputation display and sync status."
  []
  [:div.dashboard-container
   [:div.dashboard-header
    [:h2 "PoK Chain Dashboard"]
    [:p.dashboard-subtitle "Real-time blockchain and learning progress"]]
   
   [:div.dashboard-content
    [:div.dashboard-left
     [reputation-histogram]
     [consensus-log-display]]
    
    [:div.dashboard-right
     [sync-status-display]]]])

;; Lesson Selection Component

(defn lesson-selector
  "Component for selecting lessons from the curriculum."
  []
  (let [curriculum-index @(rf/subscribe [::state/curriculum-index])]
    [:div.lesson-selector
     [:h4 "Select Lesson"]
     (if curriculum-index
       [:div.curriculum-browser
        [:p (str "Found " (count (:units curriculum-index)) " units")]
        (for [unit (:units curriculum-index)]
          ^{:key (:id unit)}
          [:div.unit-section
           [:h5.unit-title (clojure.string/replace (:id unit) "unit-" "Unit ")]
           [:div.lessons-list
            (for [lesson-id (:lessons unit)]
              ^{:key lesson-id}
              [:button.lesson-btn
               {:on-click #(rf/dispatch [::state/load-lesson 
                                         (clojure.string/replace (:id unit) "unit-" "")
                                         (clojure.string/replace lesson-id "lesson-" "")])}
               (clojure.string/replace lesson-id "lesson-" "Lesson ")])]])]
       [:div.no-curriculum "No curriculum loaded"])]))

;; Main UI Components Registration Events

(rf/reg-event-db
  ::set-current-question-index
  (fn [db [_ index]]
    (assoc-in db [:ui :current-question-index] index)))

(rf/reg-sub
  ::current-question-index
  (fn [db _]
    (get-in db [:ui :current-question-index] 0)))

(rf/reg-sub
  ::current-question
  :<- [::state/current-lesson]
  :<- [::current-question-index]
  (fn [[lesson index] _]
    (when (and lesson (:questions lesson))
      (nth (:questions lesson) index nil))))

;; Main Application Component

(defn main-app
  "Main application component integrating quiz and dashboard."
  []
  (let [current-lesson @(rf/subscribe [::state/current-lesson])
        current-question @(rf/subscribe [::current-question])
        current-user @(rf/subscribe [::state/current-user])
        error @(rf/subscribe [::state/error])]
    
    [:div.app-container
     [:header.app-header
      [:h1 "AP Statistics PoK Chain"]
      [:p.app-subtitle "Proof-of-Knowledge Blockchain Learning Platform"]]
     
     ;; Error display
     (when error
       [:div.error-banner
        [:span.error-text error]
        [:button.error-close 
         {:on-click #(rf/dispatch [::state/clear-error])} 
         "×"]])
     
     ;; Main content area
     [:main.app-main
      [:div.content-layout
       ;; Left sidebar: Lesson selection and progress
       [:aside.sidebar
        [lesson-selector]
        
        (when (and current-lesson current-user)
          (let [user-progress @(rf/subscribe [::state/user-progress current-user])]
            [quiz-progress current-lesson user-progress]))]
       
       ;; Center: Quiz or welcome screen
       [:section.main-content
        (cond
          ;; Show quiz if lesson and question are loaded
          (and current-lesson current-question)
          [:div.quiz-area
           [quiz-navigation current-lesson @(rf/subscribe [::current-question-index])]
           [question-component current-question]]
          
          ;; Show lesson selection if no lesson is loaded
          current-lesson
          [:div.lesson-overview
           [:h3 (:name current-lesson)]
           [:p (:description current-lesson)]
           [:p (str "This lesson contains " (count (:questions current-lesson)) " questions.")]
           [:button.start-lesson-btn
            {:on-click #(rf/dispatch [::set-current-question-index 0])}
            "Start Lesson"]]
          
          ;; Welcome screen
          :else
          [:div.welcome-screen
           [:h2 "Welcome to AP Statistics PoK Chain"]
           [:p "Select a lesson from the sidebar to begin learning with blockchain-verified consensus."]
           [:p "Your answers will be validated through peer attestation and contribute to your reputation score."]])]
       
       ;; Right sidebar: Dashboard
       [:aside.dashboard-sidebar
        [dashboard-component]]]]
     
     [:footer.app-footer
      [:p "Phase 4: User Interface Layer - Minimal, pedagogical focus"]]]))