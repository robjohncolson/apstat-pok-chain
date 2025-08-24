(ns pok.qr
  "QR code scanning and chunk reassembly for offline synchronization.
   Implements jsQR-based camera scanning with core.async loops for multi-chunk
   payload validation using Transit deserialization and Merkle root verification."
  (:require [clojure.core.async :as async :refer [go go-loop chan <! >! timeout close!]]
            [cognitect.transit :as transit]
            [goog.crypt :as crypt]
            [goog.crypt.Sha256]))

;; QR scanning configuration constants
(def ^:const max-chunk-size 2800)  ; QR capacity limit with error correction
(def ^:const scan-timeout-ms 30000) ; 30 second timeout per scan session
(def ^:const chunk-retry-limit 3)   ; Maximum retries for failed chunks
(def ^:const validation-timeout-ms 5000) ; Hash validation timeout

;; Chunk metadata schema for multi-part QR payloads
(defn create-chunk-metadata
  "Creates standardized chunk metadata for QR payload segmentation."
  [total-chunks chunk-index payload-hash]
  {:version "1.0"
   :total-chunks total-chunks
   :chunk-index chunk-index
   :payload-hash payload-hash
   :timestamp (js/Date.now)})

(defn parse-chunk-metadata
  "Parses and validates chunk metadata from QR scan result."
  [qr-text]
  (try
    (let [data (js/JSON.parse qr-text)]
      (when (and (.-metadata data) (.-chunk data))
        {:metadata (js->clj (.-metadata data) :keywordize-keys true)
         :chunk (.-chunk data)
         :valid? true}))
    (catch js/Error e
      {:valid? false :error (str "Parse error: " e)})))

(defn validate-chunk-integrity
  "Validates chunk metadata consistency and payload hash."
  [chunk-data expected-total expected-hash]
  (let [{:keys [metadata chunk]} chunk-data
        {:keys [total-chunks chunk-index payload-hash]} metadata]
    (and
      (= total-chunks expected-total)
      (= payload-hash expected-hash)
      (< chunk-index total-chunks)
      (>= chunk-index 0)
      (string? chunk))))

;; QR scanning with jsQR integration
(defn create-video-stream
  "Creates video stream from user camera for QR scanning."
  [constraints]
  (let [result-chan (chan)]
    (if (and js/navigator (.-mediaDevices js/navigator))
      (-> (.getUserMedia (.-mediaDevices js/navigator) (clj->js constraints))
          (.then #(async/put! result-chan {:success true :stream %}))
          (.catch #(async/put! result-chan {:success false :error %})))
      (async/put! result-chan {:success false :error "Camera not supported"}))
    result-chan))

(defn scan-qr-from-canvas
  "Scans QR code from canvas image data using jsQR library."
  [canvas-context]
  (js/console.log "DIAGNOSTIC: QR scan attempt on canvas context:" canvas-context)
  (try
    (let [canvas (.-canvas canvas-context)
          width (.-width canvas)
          height (.-height canvas)
          image-data (.getImageData canvas-context 0 0 width height)
          qr-result (js/jsQR (.-data image-data) width height)]
      (js/console.log "DIAGNOSTIC: QR scan result:" qr-result)
      (if qr-result
        (do
          (js/console.log "DIAGNOSTIC: QR code detected successfully:" (.-data qr-result))
          {:success true :data (.-data qr-result)})
        (do
          (js/console.log "DIAGNOSTIC: No QR code detected in image")
          {:success false :error "No QR code detected"})))
    (catch js/Error e
      (js/console.error "DIAGNOSTIC: QR scan error:" e)
      {:success false :error (str "Scan error: " e)})))

(defn setup-video-canvas
  "Sets up canvas for video frame capture and QR scanning."
  [video-element canvas-element]
  (let [canvas canvas-element
        context (.getContext canvas "2d")
        video-width (.-videoWidth video-element)
        video-height (.-videoHeight video-element)]
    (set! (.-width canvas) video-width)
    (set! (.-height canvas) video-height)
    {:canvas canvas
     :context context
     :width video-width
     :height video-height}))

;; Chunk reassembly and validation
(defn reassemble-chunks
  "Reassembles QR chunks into complete payload with validation."
  [chunks expected-hash]
  (try
    (let [sorted-chunks (sort-by #(get-in % [:metadata :chunk-index]) chunks)
          payload-parts (map :chunk sorted-chunks)
          complete-payload (apply str payload-parts)
                ;; Calculate hash for validation
      sha256-instance (goog.crypt.Sha256.)
      _ (.update sha256-instance complete-payload)
      sha256-hash (.digest sha256-instance)
      calculated-hash (crypt/byteArrayToHex sha256-hash)]
      (if (= calculated-hash expected-hash)
        {:success true :payload complete-payload :hash calculated-hash}
        {:success false :error "Hash validation failed" 
         :expected expected-hash :calculated calculated-hash}))
    (catch js/Error e
      {:success false :error (str "Reassembly error: " e)})))

(defn validate-chunk-completeness
  "Validates that all chunks are present for reassembly."
  [chunks total-expected]
  (let [chunk-indices (set (map #(get-in % [:metadata :chunk-index]) chunks))
        expected-indices (set (range total-expected))]
    {:complete? (= chunk-indices expected-indices)
     :missing (into #{} (filter #(not (contains? chunk-indices %)) expected-indices))
     :received (count chunks)
     :expected total-expected}))

;; Core async scanning loop
(defn start-qr-scan-session
  "Starts async QR scanning session with chunk collection and timeout."
  [video-element canvas-element options]
  (let [result-chan (chan)
        chunks-atom (atom {})
        session-active? (atom true)
        {:keys [timeout-ms]} (merge {:timeout-ms scan-timeout-ms
                                     :retry-limit chunk-retry-limit}
                                    options)]
    
    ;; Main scanning loop
    (go-loop [scan-count 0]
      (when @session-active?
        (let [setup-result (setup-video-canvas video-element canvas-element)
              scan-result (scan-qr-from-canvas (:context setup-result))]
          
          (when (:success scan-result)
            (let [parse-result (parse-chunk-metadata (:data scan-result))]
              (when (:valid? parse-result)
                (let [{:keys [metadata]} parse-result
                      {:keys [total-chunks chunk-index payload-hash]} metadata
                      chunk-key chunk-index]
                  
                  ;; Store valid chunk
                  (swap! chunks-atom assoc chunk-key parse-result)
                  
                  ;; Check if reassembly is possible
                  (let [current-chunks (vals @chunks-atom)
                        completeness (validate-chunk-completeness current-chunks total-chunks)]
                    (if (:complete? completeness)
                      ;; Attempt reassembly
                      (let [reassembly-result (reassemble-chunks current-chunks payload-hash)]
                        (reset! session-active? false)
                        (>! result-chan (assoc reassembly-result :type :complete)))
                      ;; Continue scanning for missing chunks
                      (>! result-chan {:type :progress
                                       :received (:received completeness)
                                       :expected (:expected completeness)
                                       :missing (:missing completeness)})))))))
          
          ;; Continue scanning with delay
          (<! (timeout 100))
          (if (< scan-count (* timeout-ms 10)) ; 100ms intervals
            (recur (inc scan-count))
            (do
              (reset! session-active? false)
              (>! result-chan {:type :timeout :error "Scan session timed out"}))))))
    
    ;; Return control channel
    {:result-chan result-chan
     :stop-fn #(reset! session-active? false)
     :chunks-fn #(deref chunks-atom)}))

;; Transit deserialization for delta payloads
(defn deserialize-delta-payload
  "Deserializes Transit-encoded delta payload from QR scan."
  [payload-string]
  (try
    (let [read-transform {"m" (fn [rep] (js/Date. rep))} ; Handle Date objects
          reader (transit/reader :json {:transform read-transform})
          data (transit/read reader payload-string)]
      {:success true :data data})
    (catch js/Error e
      {:success false :error (str "Transit deserialization error: " e)})))

;; Hash validation utilities
(defn calculate-payload-hash
  "Calculates SHA-256 hash of payload for validation."
  [payload-string]
  (let [sha256-instance (goog.crypt.Sha256.)
        _ (.update sha256-instance payload-string)
        sha256-hash (.digest sha256-instance)]
    (crypt/byteArrayToHex sha256-hash)))

(defn validate-merkle-root
  "Validates Merkle root from delta payload against expected value."
  [delta-payload expected-merkle-root]
  (let [calculated-root (get delta-payload :merkle-root)]
    {:valid? (= calculated-root expected-merkle-root)
     :expected expected-merkle-root
     :calculated calculated-root}))

;; Public API functions
(defn scan-qr-delta
  "High-level function to scan and validate QR delta payload.
   Returns channel with complete delta or error result."
  [video-element canvas-element expected-merkle-root]
  (let [result-chan (chan)]
    (go
      (let [scan-session (start-qr-scan-session video-element canvas-element {})
            scan-result (<! (:result-chan scan-session))]
        
        (if (= (:type scan-result) :complete)
          ;; Deserialize and validate
          (let [deserialize-result (deserialize-delta-payload (:payload scan-result))]
            (if (:success deserialize-result)
              (let [merkle-validation (validate-merkle-root (:data deserialize-result) expected-merkle-root)]
                (if (:valid? merkle-validation)
                  (>! result-chan {:success true
                                   :delta (:data deserialize-result)
                                   :payload-hash (:hash scan-result)})
                  (>! result-chan {:success false
                                   :error "Merkle root validation failed"
                                   :validation merkle-validation})))
              (>! result-chan {:success false
                               :error (:error deserialize-result)})))
          ;; Scan failed or timed out
          (>! result-chan {:success false
                           :error (or (:error scan-result) "Scan incomplete")
                           :details scan-result}))
        
        (close! result-chan)))
    result-chan))

;; Helper functions for QR generation (for testing/development)
(defn chunk-payload-for-qr
  "Chunks large payload into QR-compatible segments with metadata."
  [payload-string max-chunk-size]
  (let [payload-hash (calculate-payload-hash payload-string)
        chunk-size (- max-chunk-size 200) ; Reserve space for metadata
        chunks (partition-all chunk-size payload-string)
        total-chunks (count chunks)]
    (map-indexed
      (fn [index chunk-chars]
        (let [chunk-string (apply str chunk-chars)
              metadata (create-chunk-metadata total-chunks index payload-hash)]
          {:metadata metadata
           :chunk chunk-string
           :qr-text (js/JSON.stringify #js {:metadata (clj->js metadata)
                                            :chunk chunk-string})}))
      chunks)))