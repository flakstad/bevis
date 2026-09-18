(ns bevis.test-runner
  (:require
   [bevis.challenge-test]
   [bevis.conformance-test]
   [bevis.policy-test]
   [bevis.ring-test]
   [bevis.secret-test]
   [bevis.session-test]
   [clojure.test :as test]))

(defn -main
  [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'bevis.challenge-test
                        'bevis.conformance-test
                        'bevis.policy-test
                        'bevis.ring-test
                        'bevis.secret-test
                        'bevis.session-test)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))
