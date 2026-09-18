(ns bevis.test-runner
  (:require
   [bevis.challenge-test]
   [bevis.conformance-test]
   [bevis.policy-test]
   [bevis.postgres-store-test]
   [bevis.ring-test]
   [bevis.secret-test]
   [bevis.session-test]
   [bevis.sqlite-store-test]
   [clojure.test :as test]))

(defn -main
  [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'bevis.challenge-test
                        'bevis.conformance-test
                        'bevis.policy-test
                        'bevis.postgres-store-test
                        'bevis.ring-test
                        'bevis.secret-test
                        'bevis.session-test
                        'bevis.sqlite-store-test)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))
