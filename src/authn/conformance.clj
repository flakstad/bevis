(ns authn.conformance
  "Reusable behavioral assertions for application persistence adapters.

  These functions intentionally use clojure.test so a consumer can call them
  from its ordinary test suite without copying fixtures."
  (:require
   [authn.challenge :as challenge]
   [authn.session :as session]
   [clojure.test :refer [is testing]])
  (:import
   (java.time Duration Instant)
   (java.util UUID)))

(def ^:private code-hash-key
  "authn-core-conformance-only-key-32-bytes")

(defn assert-challenge-store
  "Exercises a challenge adapter.

  Required operations:
  :insert-challenge!  (fn [record])
  :load-challenge     (fn [id])
  :verify-challenge!  (fn [{:keys [selector method proof now hash-key]}])

  verify-challenge! must select, decide with authn.challenge/verify, and apply
  its transition in one row lock or CAS operation. It returns the verify result."
  [{:keys [insert-challenge! load-challenge verify-challenge!] :as adapter}]
  (doseq [operation [:insert-challenge! :load-challenge :verify-challenge!]]
    (is (fn? (get adapter operation)) (str "adapter provides " operation)))
  (let [now (Instant/parse "2030-01-01T10:00:00Z")
        identity {:kind :conformance :id (str (UUID/randomUUID))}]
    (testing "magic links are hashed, expiring, atomic, and one-time"
      (let [{:keys [record proof]}
            (challenge/issue {:method :magic-link
                              :identity identity
                              :now now
                              :ttl (Duration/ofMinutes 15)})
            request {:selector (challenge/selector {:method :magic-link :proof proof})
                     :method :magic-link :proof proof :now (.plusSeconds now 1)}]
        (insert-challenge! record)
        (let [stored (load-challenge (:id record))]
          (is (= (:proof-hash record) (:proof-hash stored)))
          (is (not= proof (:proof-hash stored)))
          (is (not (.contains (pr-str stored) proof))))
        (is (= :invalid-proof
               (:status
                (verify-challenge!
                 {:selector (challenge/selector {:method :magic-link :proof "wrong"})
                  :method :magic-link :proof "wrong" :now (.plusSeconds now 1)}))))
        (let [gate (promise)
              attempts (doall
                        (repeatedly 2
                                    #(future @gate (verify-challenge! request))))]
          (deliver gate true)
          (is (= 1 (count (filter #(= :verified (:status %)) (map deref attempts))))
              "two concurrent verifications have exactly one winner")
          (is (some #{:consumed :invalid-proof}
                    (map :status (map deref attempts)))))))

    (testing "expiry is enforced at the exact boundary"
      (let [{:keys [record proof]}
            (challenge/issue {:method :magic-link :identity identity :now now
                              :ttl (Duration/ofSeconds 1)})]
        (insert-challenge! record)
        (is (= :expired
               (:status
                (verify-challenge!
                 {:selector (challenge/selector {:method :magic-link :proof proof})
                  :method :magic-link :proof proof :now (:expires-at record)}))))))

    (testing "codes count failures atomically and lock at the attempt limit"
      (let [{:keys [record proof]}
            (challenge/issue {:method :code :identity identity :now now
                              :hash-key code-hash-key :max-attempts 5})
            request (fn [submitted]
                      {:selector (challenge/selector {:method :code :id (:id record)})
                       :method :code :proof submitted :hash-key code-hash-key
                       :now (.plusSeconds now 1)})]
        (insert-challenge! record)
        (dotimes [_ 4]
          (is (= :invalid-proof (:status (verify-challenge! (request "000000"))))))
        (is (= :attempts-exhausted
               (:status (verify-challenge! (request "000000")))))
        (is (= 5 (:failed-attempt-count (load-challenge (:id record)))))
        (is (= :attempts-exhausted
               (:status (verify-challenge! (request proof)))))
        (is (nil? (:consumed-at (load-challenge (:id record)))))))

    (testing "a successful code is consumed and cannot replay"
      (let [{:keys [record proof]}
            (challenge/issue {:method :code :identity identity :now now
                              :hash-key code-hash-key})
            request {:selector (challenge/selector {:method :code :id (:id record)})
                     :method :code :proof proof :hash-key code-hash-key
                     :now (.plusSeconds now 1)}]
        (insert-challenge! record)
        (is (= :verified (:status (verify-challenge! request))))
        (is (= :consumed (:status (verify-challenge! request))))))

    (testing "concurrent wrong codes cannot increment beyond the limit"
      (let [{:keys [record]}
            (challenge/issue {:method :code :identity identity :now now
                              :hash-key code-hash-key :max-attempts 3})
            request {:selector (challenge/selector {:method :code :id (:id record)})
                     :method :code :proof "not-a-code" :hash-key code-hash-key
                     :now (.plusSeconds now 1)}
            gate (promise)]
        (insert-challenge! record)
        (let [attempts (doall (repeatedly 10 #(future @gate (verify-challenge! request))))]
          (deliver gate true)
          (doseq [attempt attempts] @attempt)
          (is (= 3 (:failed-attempt-count (load-challenge (:id record)))))
          (is (= :attempts-exhausted
                 (:status (verify-challenge! request)))))))
    true))

(defn assert-session-store
  "Exercises a session adapter.

  Required operations:
  :insert-session! (fn [record])
  :find-session     (fn [credential-hash-candidates])
  :load-session     (fn [id])
  :revoke-session!  (fn [id now])"
  [{:keys [insert-session! find-session load-session revoke-session!] :as adapter}]
  (doseq [operation [:insert-session! :find-session :load-session :revoke-session!]]
    (is (fn? (get adapter operation)) (str "adapter provides " operation)))
  (let [now (Instant/parse "2030-01-01T10:00:00Z")
        {:keys [record credential]}
        (session/issue {:subject {:kind :conformance :id (str (UUID/randomUUID))}
                        :now now :ttl (Duration/ofHours 1)})]
    (insert-session! record)
    (let [stored (load-session (:id record))]
      (is (= (:credential-hash record) (:credential-hash stored)))
      (is (not= credential (:credential-hash stored)))
      (is (not (.contains (pr-str stored) credential))))
    (is (= :invalid-session
           (:status (session/check (find-session [(session/credential-hash "wrong")])
                                   {:now now}))))
    (let [stored (find-session (session/credential-hash-candidates credential))]
      (is (= :active (:status (session/check stored {:now now}))))
      (is (= :expired-session
             (:status (session/check stored {:now (:expires-at record)})))))
    (is (true? (boolean (revoke-session! (:id record) (.plusSeconds now 1)))))
    (is (= :revoked-session
           (:status
            (session/check
             (find-session (session/credential-hash-candidates credential))
             {:now (.plusSeconds now 2)}))))
    true))
