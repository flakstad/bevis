(ns bevis.test-support
  (:require [bevis.challenge :as challenge]))

(defn memory-adapter
  []
  (let [challenges (atom {})
        sessions (atom {})
        lock (Object.)]
    {:insert-challenge!
     (fn [record]
       (locking lock
         (swap! challenges assoc (:id record) record)
         record))
     :load-challenge (fn [id] (get @challenges id))
     :verify-challenge!
     (fn [{:keys [selector] :as request}]
       (locking lock
         (let [record (some (fn [[_ candidate]]
                              (when (and (= (:method selector) (:method candidate))
                                         (if-let [id (:id selector)]
                                           (= id (:id candidate))
                                           (= (:proof-hash selector) (:proof-hash candidate))))
                                candidate))
                            @challenges)
               result (challenge/verify record request)]
           (when record
             (swap! challenges assoc (:id record)
                    (challenge/apply-transition record result)))
           result)))
     :insert-session!
     (fn [record]
       (locking lock
         (swap! sessions assoc (:id record) record)
         record))
     :find-session
     (fn [credential-hashes]
       (some (fn [[_ record]]
               (when (some #{(:credential-hash record)} credential-hashes)
                 record))
             @sessions))
     :load-session (fn [id] (get @sessions id))
     :revoke-session!
     (fn [id now]
       (locking lock
         (when (get @sessions id)
           (swap! sessions update id #(assoc % :revoked-at (or (:revoked-at %) now)))
           true)))}))
