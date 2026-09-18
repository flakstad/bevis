(ns bevis.conformance-test
  (:require [bevis.conformance :as conformance]
            [bevis.test-support :as support]
            [clojure.test :refer [deftest]]))

(deftest in-memory-store-obeys-challenge-contract
  (conformance/assert-challenge-store (support/memory-store)))

(deftest in-memory-store-obeys-session-contract
  (conformance/assert-session-store (support/memory-store)))
