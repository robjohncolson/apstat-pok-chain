(ns pok.curriculum
  "Curriculum data loader with async EDN loading and IndexedDB caching.
   Implements lazy loading of ~6KB lessons with cache-first strategy for performance."
  (:require [clojure.core.async :as async :refer [go >! <! chan]]
            [cljs.reader :as reader]))

;; Device performance detection
(defn detect-device-performance
  "Detects device performance level based on hardware concurrency.
   Returns :low, :medium, or :high for rendering optimization."
  []
  (let [cores (or (.-hardwareConcurrency js/navigator) 2)]
    (cond
      (<= cores 2) :low
      (<= cores 4) :medium
      :else :high)))

;; Validation functions
(defn validate-lesson-data
  "Validates loaded lesson data structure."
  [lesson-data]
  (and (map? lesson-data)
       (contains? lesson-data :id)
       (contains? lesson-data :name)
       (contains? lesson-data :content)
       (or (contains? lesson-data :questions)
           (contains? lesson-data :chart-data))))

(defn validate-chart-data
  "Validates chart data for Vega-Lite rendering."
  [chart-data]
  (and (map? chart-data)
       (contains? chart-data :type)
       (contains? chart-data :data)
       (vector? (:data chart-data))
       (#{:bar :scatter :histogram :line} (:type chart-data))))

;; EDN file loading utilities using fetch API
(defn load-edn-file
  "Loads an EDN file asynchronously via fetch API"
  [file-path]
  (let [result-chan (chan)]
    (js/console.log "Loading EDN file:" file-path)
    (-> (js/fetch file-path)
        (.then (fn [response]
                 (if (.-ok response)
                   (.text response)
                   (throw (js/Error. (str "HTTP " (.-status response) ": " (.-statusText response)))))))
        (.then (fn [text]
                 (js/console.log "EDN file loaded, content length:" (.-length text))
                 (try
                   (let [data (reader/read-string text)]
                     (js/console.log "EDN parsed successfully")
                     (go (>! result-chan {:success true :data data})))
                   (catch js/Error e
                     (js/console.error "EDN parsing error:" e)
                     (go (>! result-chan {:success false :error (str "Parse error: " e)}))))))
        (.catch (fn [error]
                  (js/console.error "Fetch error:" error)
                  (go (>! result-chan {:success false :error (str "Network error: " error)})))))
    result-chan))

;; Curriculum loading implementations
(defn load-curriculum-index
  "Load curriculum index from resources/edn/index.edn"
  []
  (js/console.log "load-curriculum-index called")
  (let [result-chan (chan)]
    (go
      (js/console.log "Starting to load index.edn file...")
      (let [index-result (<! (load-edn-file "/resources/edn/index.edn"))]
        (js/console.log "Index file load result:" (clj->js index-result))
        (if (:success index-result)
          (>! result-chan {:success true :data (:data index-result)})
          (>! result-chan {:success false :error (:error index-result)}))))
    result-chan))

(defn load-lesson
  "Load a specific lesson by unit and lesson ID"
  [unit-id lesson-id]
  (let [result-chan (chan)
        filename (str "unit-" unit-id "-lesson-" lesson-id ".edn")
        file-path (str "/resources/edn/" filename)]
    (go
      (let [lesson-result (<! (load-edn-file file-path))]
        (if (:success lesson-result)
          (>! result-chan {:success true :data (:data lesson-result)})
          (>! result-chan {:success false :error (:error lesson-result)}))))
    result-chan))

(defn load-multiple-lessons
  "Batch load multiple lessons"
  [lesson-specs]
  (let [result-chan (chan)]
    (go
      (let [load-results (<! (async/map vector 
                                        (mapv (fn [{:keys [unit-id lesson-id]}]
                                                (load-lesson unit-id lesson-id))
                                              lesson-specs)))]
        (>! result-chan 
            (into {} (map-indexed (fn [idx result]
                                    [(str (:unit-id (nth lesson-specs idx)) 
                                          "/" (:lesson-id (nth lesson-specs idx)))
                                     result])
                                  load-results)))))
    result-chan))

;; PHASE 9 PROTOTYPE: Multi-subject curriculum generalization
(defn detect-curriculum-subject
  "Detects curriculum subject from index metadata for multi-subject support."
  [index-data]
  (let [subject-hints (:subject index-data)
        unit-patterns (map :id (:units index-data))]
    (cond
      subject-hints subject-hints
      (some #(re-find #"stat|data|probability" (str %)) unit-patterns) :ap-statistics
      (some #(re-find #"calc|derivative|integral" (str %)) unit-patterns) :ap-calculus
      (some #(re-find #"bio|cell|genetics" (str %)) unit-patterns) :ap-biology
      (some #(re-find #"chem|molecular|atomic" (str %)) unit-patterns) :ap-chemistry
      :else :unknown)))

(defn load-subject-specific-index
  "PROTOTYPE: Load curriculum index with subject-aware path resolution."
  [subject-id]
  (let [result-chan (chan)
        base-path (case subject-id
                    :ap-statistics "/resources/edn/index.edn"
                    :ap-calculus "/resources/edn/calculus/index.edn"
                    :ap-biology "/resources/edn/biology/index.edn"
                    :ap-chemistry "/resources/edn/chemistry/index.edn"
                    "/resources/edn/index.edn")] ; Default fallback
    (go
      (let [index-result (<! (load-edn-file base-path))]
        (if (:success index-result)
          (let [detected-subject (detect-curriculum-subject (:data index-result))]
            (>! result-chan {:success true 
                            :data (:data index-result)
                            :detected-subject detected-subject
                            :requested-subject subject-id}))
          (>! result-chan {:success false :error (:error index-result)}))))
    result-chan))

(defn generalize-lesson-loader
  "PROTOTYPE: Generalized lesson loader supporting multiple curriculum structures."
  [subject-id unit-id lesson-id]
  (let [result-chan (chan)
        subject-path (case subject-id
                       :ap-statistics ""
                       :ap-calculus "calculus/"
                       :ap-biology "biology/"
                       :ap-chemistry "chemistry/"
                       "")
        filename (str "unit-" unit-id "-lesson-" lesson-id ".edn")
        file-path (str "/resources/edn/" subject-path filename)]
    (go
      (let [lesson-result (<! (load-edn-file file-path))]
        (if (:success lesson-result)
          (>! result-chan {:success true 
                          :data (:data lesson-result)
                          :subject subject-id
                          :path file-path})
          (>! result-chan {:success false :error (:error lesson-result)}))))
    result-chan))

;; Cache management (simplified for now - future IndexedDB integration)
(defn clear-cache
  "Clear curriculum cache"
  []
  (let [result-chan (chan)]
    (go 
      ;; For now, just return success - implement IndexedDB clearing later
      (>! result-chan true))
    result-chan))

(defn get-cache-stats
  "Get cache statistics"
  []
  (let [result-chan (chan)]
    (go 
      (>! result-chan {:cache-enabled false
                       :device-performance (detect-device-performance)
                       :lessons-cached 0
                       :cache-size 0}))
    result-chan))