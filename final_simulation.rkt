#lang racket
(require json plot racket/gui/base math/statistics)

;; Structs
(struct payload (answer hash) #:transparent)
(struct transaction (id timestamp owner-pubkey question-id type payload) #:transparent)
(struct block (hash txns type) #:transparent) ; Added type: "attestation" or "pok"
(struct question (id prompt qtype choices answerKey) #:transparent)
(struct node (pubkey archetype mempool chain progress reputation consensus-history) #:transparent #:mutable) ; Added reputation and history

;; Globals
(define total-nodes 40)
(define class-size 20)
(define sim-days 180)
(define meetings-per-week 4)
(define questions-per-day 5)
(define progress-lag-prob 0.1)
(define sync-failure-prob 0.1)
(define quorum-conv-thresh 0.7)
(define thought-leader-thresh 0.5)
(define thought-leader-bonus 2.5)
(define cleanup-age 3) ; days for incentivized cleanup
(define archetypes '((aces . 0.95) (diligent . 0.8) (strugglers . 0.6) (guessers . 0.3)))
(define *app-db* (make-hash)) ; Global state

;; Load Curriculum
(define (load-curriculum)
  (with-input-from-file "pok_curriculum_trimmed.json" ; Use trimmed for example
    (lambda () (map (lambda (q)
                      (question (hash-ref q 'id)
                                (hash-ref q 'prompt)
                                (hash-ref q 'type)
                                (hash-ref q 'choices '())
                                (hash-ref q 'answerKey #f))) (read-json)))))

(define curriculum (load-curriculum))

;; Helpers
(define (create-txn qid pubkey ans t type)
  (let ([hash (number->string (equal-hash-code ans))])
    (transaction (format "~a-~a" t type) t pubkey qid type (payload ans hash))))

(define (valid-txn? txn) ; Validation logic
  #t) ; Simplified

(define (group-attestations attns)
  (let ([dist (make-hash)])
    (for-each (lambda (txn) (when (string=? (transaction-type txn) "attestation")
                              (hash-update! dist (payload-hash (transaction-payload txn)) add1 0))) attns)
    dist))

(define (calculate-convergence node qid)
  (let* ([all-txns (append (node-mempool node) (apply append (map block-txns (node-chain node))))]
         [attns (filter (lambda (txn) (and (string=? (transaction-question-id txn) qid)
                                           (string=? (transaction-type txn) "attestation"))) all-txns)]
         [dist (group-attestations attns)]
         [total (apply + (hash-values dist))]
         [max-count (if (zero? (hash-count dist)) 0 (apply max (hash-values dist)))])
    (if (zero? total) 0.0 (exact->inexact (/ max-count total)))))

(define (propose-attestation-block node)
  (let ([attns (filter (lambda (txn) (string=? (transaction-type txn) "attestation")) (node-mempool node))])
    (when (>= (length attns) 5)
      (let ([new-block (block (format "~a-att-block" (length (node-chain node))) attns "attestation")])
        (set-node-chain! node (cons new-block (node-chain node)))
        (set-node-mempool! node (remove* attns (node-mempool node)))))))

(define (propose-pok-block node)
  (let* ([mempool (node-mempool node)]
         [q-index (node-progress node)]
         [min-attest (if (< q-index (/ (length curriculum) 2)) 2 4)] ; Dynamic quorum
         [valid-txns (filter (lambda (txn)
                               (and (string=? (transaction-type txn) "completion")
                                    (let ([conv (calculate-convergence node (transaction-question-id txn))])
                                      (and (>= (length (filter-attns txn)) min-attest) (>= conv quorum-conv-thresh))))) mempool)])
    (when (not (null? valid-txns))
      (let ([new-block (block (format "~a-pok-block" (length (node-chain node))) valid-txns "pok")])
        (set-node-chain! node (cons new-block (node-chain node)))
        (set-node-mempool! node (remove* valid-txns mempool))
        (update-reputation node valid-txns))))) ; Trigger Thought Leader

(define (update-reputation node mined-txns)
  (for-each (lambda (txn)
              (let ([qid (transaction-question-id txn)]
                    [final-ans (payload-answer (transaction-payload txn))]) ; Assume mined is truth
                (for-each (lambda (attn)
                            (let* ([attester (transaction-owner-pubkey attn)]
                                   [hist (hash-ref (node-consensus-history node) qid '())]
                                   [prop-at-time (lookup-prop hist (transaction-timestamp attn))]
                                   [bonus (if (and (string=? (payload-answer (transaction-payload attn)) final-ans)
                                                   (< prop-at-time thought-leader-thresh))
                                              thought-leader-bonus 1.0)]
                                   [weight (log (+ (node-reputation (find-node attester)) 1))]) ; Log scaling
                              (set-node-reputation! (find-node attester) (+ (node-reputation (find-node attester)) (* bonus weight)))))
                          (filter-attns-for-qid qid)))) mined-txns))

;; Weekly Notary Sync
(define (select-notaries classes)
  (map (lambda (cls) (list-ref (shuffle cls) 0)) classes))

(define (run-meeting day)
  (let ([all-nodes (shuffle (append (hash-ref *app-db* 'class-a) (hash-ref *app-db* 'class-b)))]
        [meeting-num (modulo day meetings-per-week)]
        [notaries (select-notaries (list (hash-ref *app-db* 'class-a) (hash-ref *app-db* 'class-b)))])
    (for ([i (in-range 0 (length all-nodes) 2)])
      (let ([n1 (list-ref all-nodes i)] [n2 (if (< (+ i 1) (length all-nodes)) (list-ref all-nodes (+ i 1)) #f)])
        (when n2
          (sync-nodes n1 n2) ; Gossip: exchange mempool + 25% random attns
          (attest-partner n1 n2 #:weight-old (> cleanup-age 3)) ; Incentivized cleanup
          (propose-attestation-block n1) (propose-attestation-block n2)
          (propose-pok-block n1) (propose-pok-block n2)))))
    (when (= meeting-num 3) (sync-nodes (car notaries) (cadr notaries))) ; Cross-notary
    (when (= meeting-num 0) (for-each (lambda (n) (when (< (random) 0.5) (sync-nodes n (class-notary n)))) all-nodes)))) ; Mega-chain prop

;; Daily Cycle, Setup, Stats, Stress Tests (e.g., teacher-reveal, wrong-ace, onboarding)
(define (run-teacher-stress qid)
  (let ([teacher (node "teacher" 'infinite '() '() 0 +inf.0 (make-hash))])
    (submit-ap-reveal teacher qid "A") ; Weight 10
    (run-meetings 5) ; Continue and track conv
    (plot-conv-history qid)))

(define (run-fairness-stress)
  (set-ace-wrong #t) ; For 10 questions
  (run-sim sim-days #:weighting 'linear)
  (run-sim sim-days #:weighting 'log))

(define (run-onboarding-stress)
  (run-sim 45) (add-new-node 'diligent)
  (run-sim 45) (add-new-node 'aces)
  (run-sim (- sim-days 90)))

(define (run-sim days #:mode 'normal)
  (setup-classrooms)
  (for ([day (in-range days)])
    (run-day day)
    (when (is-meeting-day? day) (run-meeting day))
    (display-stats day))
  (display-final-stats))

;; Run entry
(run-sim sim-days)