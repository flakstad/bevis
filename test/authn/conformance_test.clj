(ns authn.conformance-test
  (:require [authn.conformance :as conformance]
            [authn.test-support :as support]
            [clojure.test :refer [deftest]]))

(deftest in-memory-adapter-obeys-challenge-contract
  (conformance/assert-challenge-store (support/memory-adapter)))

(deftest in-memory-adapter-obeys-session-contract
  (conformance/assert-session-store (support/memory-adapter)))
