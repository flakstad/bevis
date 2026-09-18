# authn-core

`authn-core` 0.1.0 is a small, storage-agnostic Clojure library for
passwordless authentication:

```text
identity → challenge → proof → verification → session
```

It supports high-entropy magic-link proofs and numeric one-time codes without
owning users, authorization, routes, email, UI, or a database. Its API is plain
functions over plain maps. Clojure and the JDK are its only dependencies.

Version 0.1.x is a pilot API. Pin consumers to a full Git commit, not a branch:

```clojure
{:deps
 {io.github.flakstad/authn-core
  {:git/url "https://github.com/flakstad/authn-core.git"
   :git/sha "<full-40-character-sha>"}}}
```

During coordinated development, override that coordinate with `:local/root`
from the command line or an uncommitted developer alias.

## Magic-link flow

```clojure
(require '[authn.challenge :as challenge]
         '[authn.session :as session]
         '[authn.ring :as auth-ring])

(let [{:keys [record proof]}
      (challenge/issue {:method :magic-link
                        :identity {:account-id account-id}
                        :ttl (java.time.Duration/ofMinutes 15)
                        :metadata {:return-path "/konto"}})]
  ((:insert-challenge! auth-store) record)
  ;; The application constructs and sends its own URL/email.
  (send-login-link! email (str public-url "/auth/verify?token=" proof)))

;; The adapter performs lookup + decision + transition under one lock/CAS.
(let [result ((:verify-challenge! auth-store)
              {:selector (challenge/selector {:method :magic-link
                                               :proof token})
               :method :magic-link
               :proof token
               :now now})]
  (when (= :verified (:status result))
    (let [{:keys [record credential]}
          (session/issue {:subject (get-in result [:challenge :identity])
                          :now now
                          :ttl (java.time.Duration/ofHours 12)})]
      ((:insert-session! auth-store) record)
      {:set-cookie
       (auth-ring/session-cookie {:name "__Host-example_session"
                                  :value credential
                                  :max-age 43200})})))
```

The returned proof/credential is the only plaintext copy. Persist only the
record. A verified result contains a sanitized challenge and no proof hash.

## One-time-code flow

```clojure
(let [{:keys [record proof]}
      (challenge/issue {:method :code
                        :identity {:account-id account-id}
                        :hash-key otp-hmac-key})]
  ((:insert-challenge! auth-store) record)
  (send-code! destination proof)
  {:challenge-id (:id record)})

((:verify-challenge! auth-store)
 {:selector (challenge/selector {:method :code :id challenge-id})
  :method :code
  :proof submitted-code
  :hash-key otp-hmac-key
  :now now})
```

Codes default to six numeric digits, ten minutes, and five attempts. Code
hashes require an application-held HMAC key. The adapter must update failed
attempts under the same lock/CAS used for successful consumption.

## Sessions

```clojure
(let [stored ((:find-session auth-store)
              (session/credential-hash-candidates cookie-value))]
  (case (:status (session/check stored {:now now}))
    :active          (get-in (session/check stored {:now now}) [:session :subject])
    :invalid-session nil
    :expired-session nil
    :revoked-session nil))
```

`credential-hash-candidates` includes the 0.1 versioned digest and the legacy
64-character SHA-256 hex digest used by initial Radar consumers.

## Adapter conformance

Consumers run the shipped assertions against their real adapter:

```clojure
(deftest auth-store-conforms
  (let [account (create-test-account! test-ds)
        adapter (assoc (postgres-adapter test-ds)
                       :conformance/identity {:account-id (:id account)}
                       :conformance/subject {:account-id (:id account)})]
    (auth-test/assert-challenge-store adapter)
    (auth-test/assert-session-store adapter)))
```

See [DESIGN.md](DESIGN.md) for the exact operation contract and
[SECURITY.md](SECURITY.md) for the threat model.

## Public namespaces

- `authn.secret` — generated credentials, versioned hashes, compatibility hashes.
- `authn.challenge` — issue, select, verify, and apply explicit transitions.
- `authn.session` — issue and classify persisted sessions.
- `authn.policy` — a small issuance-count decision primitive.
- `authn.ring` — Set-Cookie values and conservative local return paths.
- `authn.conformance` — reusable persistence-adapter assertions.

Run the suite with `clojure -M:test`.
