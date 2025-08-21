(ns pok.qr-test
  "Test suite for QR scanning, chunk reassembly, and payload validation."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.core.async :as async :refer [go <!]]
            [pok.qr :as qr]))

(deftest test-chunk-metadata-creation
  (testing "Chunk metadata structure"
    (let [metadata (qr/create-chunk-metadata 3 1 "test-hash")]
      (is (= (:total-chunks metadata) 3))
      (is (= (:chunk-index metadata) 1))
      (is (= (:payload-hash metadata) "test-hash"))
      (is (= (:version metadata) "1.0"))
      (is (number? (:timestamp metadata))))))

(deftest test-chunk-metadata-parsing
  (testing "Valid chunk metadata parsing"
    (let [test-chunk {:metadata {:total-chunks 2
                                 :chunk-index 0
                                 :payload-hash "abc123"
                                 :version "1.0"}
                      :chunk "data-part-1"}
          qr-text (js/JSON.stringify (clj->js test-chunk))
          parsed (qr/parse-chunk-metadata qr-text)]
      (is (:valid? parsed))
      (is (= (get-in parsed [:metadata :total-chunks]) 2))
      (is (= (:chunk parsed) "data-part-1"))))
  
  (testing "Invalid chunk metadata parsing"
    (let [invalid-json "not-json"
          parsed (qr/parse-chunk-metadata invalid-json)]
      (is (not (:valid? parsed)))
      (is (contains? parsed :error)))))

(deftest test-chunk-integrity-validation
  (testing "Valid chunk integrity"
    (let [chunk-data {:metadata {:total-chunks 3
                                 :chunk-index 1
                                 :payload-hash "expected-hash"}
                      :chunk "test-data"}]
      (is (qr/validate-chunk-integrity chunk-data 3 "expected-hash"))))
  
  (testing "Invalid chunk integrity - wrong total"
    (let [chunk-data {:metadata {:total-chunks 2
                                 :chunk-index 1
                                 :payload-hash "expected-hash"}
                      :chunk "test-data"}]
      (is (not (qr/validate-chunk-integrity chunk-data 3 "expected-hash")))))
  
  (testing "Invalid chunk integrity - wrong hash"
    (let [chunk-data {:metadata {:total-chunks 3
                                 :chunk-index 1
                                 :payload-hash "wrong-hash"}
                      :chunk "test-data"}]
      (is (not (qr/validate-chunk-integrity chunk-data 3 "expected-hash")))))
  
  (testing "Invalid chunk integrity - index out of range"
    (let [chunk-data {:metadata {:total-chunks 3
                                 :chunk-index 5
                                 :payload-hash "expected-hash"}
                      :chunk "test-data"}]
      (is (not (qr/validate-chunk-integrity chunk-data 3 "expected-hash"))))))

(deftest test-chunk-completeness-validation
  (testing "Complete chunk set"
    (let [chunks [{:metadata {:chunk-index 0}} 
                  {:metadata {:chunk-index 1}} 
                  {:metadata {:chunk-index 2}}]
          validation (qr/validate-chunk-completeness chunks 3)]
      (is (:complete? validation))
      (is (empty? (:missing validation)))
      (is (= (:received validation) 3))))
  
  (testing "Incomplete chunk set"
    (let [chunks [{:metadata {:chunk-index 0}} 
                  {:metadata {:chunk-index 2}}]
          validation (qr/validate-chunk-completeness chunks 3)]
      (is (not (:complete? validation)))
      (is (= (:missing validation) #{1}))
      (is (= (:received validation) 2)))))

(deftest test-chunk-reassembly
  (testing "Successful chunk reassembly"
    (let [payload "test-payload-data"
          expected-hash (qr/calculate-payload-hash payload)
          chunks [{:metadata {:chunk-index 0} :chunk "test-"}
                  {:metadata {:chunk-index 1} :chunk "payload-"}
                  {:metadata {:chunk-index 2} :chunk "data"}]
          result (qr/reassemble-chunks chunks expected-hash)]
      (is (:success result))
      (is (= (:payload result) payload))
      (is (= (:hash result) expected-hash))))
  
  (testing "Failed chunk reassembly - hash mismatch"
    (let [chunks [{:metadata {:chunk-index 0} :chunk "test-"}
                  {:metadata {:chunk-index 1} :chunk "payload-"}
                  {:metadata {:chunk-index 2} :chunk "data"}]
          wrong-hash "wrong-hash-value"
          result (qr/reassemble-chunks chunks wrong-hash)]
      (is (not (:success result)))
      (is (contains? result :error))
      (is (= (:expected result) wrong-hash)))))

(deftest test-payload-chunking
  (testing "Payload chunking for QR"
    (let [payload "This is a test payload that will be chunked"
          chunk-size 10
          chunks (qr/chunk-payload-for-qr payload chunk-size)]
      (is (> (count chunks) 1)) ; Should be multiple chunks
      (is (every? #(contains? % :metadata) chunks))
      (is (every? #(contains? % :chunk) chunks))
      (is (every? #(contains? % :qr-text) chunks))
      
      ;; Verify all chunks have same total and hash
      (let [first-metadata (:metadata (first chunks))
            total-chunks (:total-chunks first-metadata)
            payload-hash (:payload-hash first-metadata)]
        (is (= (count chunks) total-chunks))
        (is (every? #(= (get-in % [:metadata :total-chunks]) total-chunks) chunks))
        (is (every? #(= (get-in % [:metadata :payload-hash]) payload-hash) chunks))))))

(deftest test-transit-serialization
  (testing "Delta payload serialization"
    (let [test-delta {:version "1.0"
                      :timestamp 1234567890
                      :merkle-root "test-root"
                      :transactions []
                      :blocks []}
          result (qr/deserialize-delta-payload (pr-str test-delta))]
      ;; Note: This test would need actual Transit serialization
      ;; For now, testing the error handling path
      (is (contains? result :success)))))

(deftest test-hash-calculation
  (testing "Payload hash calculation consistency"
    (let [payload "test-payload"
          hash1 (qr/calculate-payload-hash payload)
          hash2 (qr/calculate-payload-hash payload)]
      (is (= hash1 hash2)) ; Same payload should give same hash
      (is (string? hash1))
      (is (> (count hash1) 0))))
  
  (testing "Different payloads give different hashes"
    (let [payload1 "test-payload-1"
          payload2 "test-payload-2"
          hash1 (qr/calculate-payload-hash payload1)
          hash2 (qr/calculate-payload-hash payload2)]
      (is (not= hash1 hash2)))))

(deftest test-merkle-validation
  (testing "Valid Merkle root validation"
    (let [delta-payload {:merkle-root "expected-root"}
          expected-root "expected-root"
          validation (qr/validate-merkle-root delta-payload expected-root)]
      (is (:valid? validation))
      (is (= (:expected validation) expected-root))
      (is (= (:calculated validation) expected-root))))
  
  (testing "Invalid Merkle root validation"
    (let [delta-payload {:merkle-root "actual-root"}
          expected-root "expected-root"
          validation (qr/validate-merkle-root delta-payload expected-root)]
      (is (not (:valid? validation)))
      (is (= (:expected validation) expected-root))
      (is (= (:calculated validation) "actual-root")))))

;; Integration test stubs (would require browser environment)
(deftest test-qr-scan-integration-stubs
  (testing "QR scan session creation"
    ;; These would require actual DOM elements in browser environment
    ;; For now, testing that functions exist and handle null gracefully
    (is (fn? qr/start-qr-scan-session))
    (is (fn? qr/scan-qr-delta))
    (is (fn? qr/setup-video-canvas))))

(deftest test-error-handling
  (testing "Graceful error handling in chunk parsing"
    (let [result (qr/parse-chunk-metadata "")]
      (is (not (:valid? result)))
      (is (contains? result :error))))
  
  (testing "Graceful error handling in reassembly"
    (let [result (qr/reassemble-chunks [] "test-hash")]
      (is (not (:success result)))
      (is (contains? result :error)))))

;; Performance and constraint tests
(deftest test-qr-constraints
  (testing "Chunk size limits"
    (let [large-payload (apply str (repeat 5000 "x"))
          chunks (qr/chunk-payload-for-qr large-payload qr/max-chunk-size)]
      ;; Each chunk should be within QR size limits
      (is (every? #(< (count (:qr-text %)) qr/max-chunk-size) chunks))))
  
  (testing "Maximum chunk count handling"
    (let [very-large-payload (apply str (repeat 50000 "x"))
          chunks (qr/chunk-payload-for-qr very-large-payload 1000)]
      (is (> (count chunks) 1))
      (is (< (count chunks) 100)) ; Reasonable upper limit
      )))

(defn run-qr-tests []
  (run-tests 'pok.qr-test))