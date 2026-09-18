# bevis

`bevis` 0.1.0 is a small, storage-agnostic Clojure library for
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
 {io.github.flakstad/bevis
  {:git/url "https://github.com/flakstad/bevis.git"
   :git/sha "<full-40-character-sha>"}}}
```

During coordinated development, override that coordinate with `:local/root`
from the command line or an uncommitted developer alias.

## Magic-link flow

```clojure
(require '[bevis.challenge :as challenge]
         '[bevis.session :as session]
         '[bevis.ring :as auth-ring]
         '[your-app.auth-store :as auth-store]) ; application-owned persistence

(defn issue-magic-link!
  [db {:keys [account-id email public-url send-login-link!]}]
  (let [{:keys [record proof]}
        (challenge/issue {:method :magic-link
                          :identity {:account-id account-id}
                          :ttl (java.time.Duration/ofMinutes 15)
                          :metadata {:return-path "/konto"}})]
    (auth-store/insert-challenge! db record)
    ;; The application constructs and sends its own URL/email.
    (send-login-link! email (str public-url "/auth/verify?token=" proof))))

(defn verify-magic-link!
  [db {:keys [token now]}]
  ;; The store performs lookup + decision + transition under one lock/CAS.
  (let [result (auth-store/verify-challenge!
                db
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
        (auth-store/insert-session! db record)
        {:set-cookie
         (auth-ring/session-cookie {:name "__Host-example_session"
                                    :value credential
                                    :max-age 43200})}))))
```

The returned proof/credential is the only plaintext copy. Persist only the
record. A verified result contains a sanitized challenge and no proof hash.

## One-time-code flow

```clojure
(require '[bevis.challenge :as challenge]
         '[your-app.auth-store :as auth-store]) ; application-owned persistence

(defn issue-code!
  [db {:keys [identity destination otp-hmac-key send-code!]}]
  (let [{:keys [record proof]}
        (challenge/issue {:method :code
                          :identity identity
                          :hash-key otp-hmac-key})]
    (auth-store/insert-challenge! db record)
    (send-code! destination proof)
    {:challenge-id (:id record)}))

(defn verify-code!
  [db {:keys [challenge-id submitted-code otp-hmac-key now]}]
  (auth-store/verify-challenge!
   db
   {:selector (challenge/selector {:method :code :id challenge-id})
    :method :code
    :proof submitted-code
    :hash-key otp-hmac-key
    :now now}))
```

Codes default to six numeric digits, ten minutes, and five attempts. Code
hashes require an application-held HMAC key. The adapter must update failed
attempts under the same lock/CAS used for successful consumption.

## Sessions

```clojure
(require '[bevis.session :as session]
         '[your-app.auth-store :as auth-store]) ; application-owned persistence

(defn authenticated-subject
  [db cookie-value now]
  (let [stored (auth-store/find-session
                db
                (session/credential-hash-candidates cookie-value))
        result (session/check stored {:now now})]
    (case (:status result)
      :active          (get-in result [:session :subject])
      :invalid-session nil
      :expired-session nil
      :revoked-session nil)))
```

`credential-hash-candidates` includes the 0.1 versioned digest and the legacy
64-character SHA-256 hex digest used by initial Radar consumers.

## Adapter conformance

Normal application code calls its persistence namespace directly, as above.
For conformance tests only, that namespace exposes the required operations as
an adapter map so Bevis can exercise the same implementation generically:

```clojure
(require '[bevis.conformance :as auth-test]
         '[your-app.auth-store :as auth-store])

(defn assert-auth-store-conformance!
  [db identity subject]
  (let [adapter (assoc (auth-store/adapter db)
                       :conformance/identity identity
                       :conformance/subject subject)]
    (auth-test/assert-challenge-store adapter)
    (auth-test/assert-session-store adapter)))
```

See [DESIGN.md](DESIGN.md) for the exact operation contract and
[SECURITY.md](SECURITY.md) for the threat model.

## Public namespaces

- `bevis.secret` — generated credentials, versioned hashes, compatibility hashes.
- `bevis.challenge` — issue, select, verify, and apply explicit transitions.
- `bevis.session` — issue and classify persisted sessions.
- `bevis.policy` — a small issuance-count decision primitive.
- `bevis.ring` — Set-Cookie values and conservative local return paths.
- `bevis.conformance` — reusable persistence-adapter assertions.

Run the suite with `clojure -M:test`.
