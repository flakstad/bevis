(ns authn.test-runner
  (:require
   [authn.challenge-test]
   [authn.conformance-test]
   [authn.policy-test]
   [authn.ring-test]
   [authn.secret-test]
   [authn.session-test]
   [clojure.test :as test]))

(defn -main
  [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'authn.challenge-test
                        'authn.conformance-test
                        'authn.policy-test
                        'authn.ring-test
                        'authn.secret-test
                        'authn.session-test)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))
