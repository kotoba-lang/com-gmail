# com-gmail

Portable (`.cljc`) Gmail API v1 client -- thread listing/reading, label
management, and reply-draft creation with one tested auth/HTTP boundary and
an injectable transport, for any kotoba-lang/gftdcojp project that needs to
read or act on Gmail instead of re-deriving `curl`/HTTP-call boilerplate ad
hoc.

## Why this exists

`gftdcojp/local-manimani` triages Gmail into policies (`todo`/`waiting`/
`done`/`archive`/`reply_llm`/...) and its README documents a Gmail-connected
pipeline (label, archive, draft a reply), but that pipeline runs through a
TypeScript implementation (`server/src/gmail.ts`) that isn't wired to every
host, and ad hoc Gmail connectors (e.g. a chat assistant's own OAuth grant)
are frequently read-only-scoped and can't act. This library is the portable,
independently-tested Gmail boundary any `.cljc` host (manimani's babashka
path, `kotoba-procedure-clj` consumers, a future actor) can depend on
instead of re-deriving thread/label/draft HTTP calls per project -- the same
role `com-cloudflare` plays for Cloudflare.

## Design

```text
gmail.client  -- auth (Bearer OAuth2 access token) + HTTP (injectable :http-fn) + JSON envelope
gmail.threads -- list/get threads, modify (add/remove label ids), archive
gmail.labels  -- list, create, find-or-create (by display name)
gmail.drafts  -- create a reply draft (plain-text RFC 2822, optionally attached to a thread)
gmail.history -- users.history.list, for cursor-based incremental sync (what's changed since a historyId)
```

Query construction and response parsing are pure `.cljc`. The actual HTTP
call is JVM-only by default (`java.net.http`) but every function takes an
injectable `:http-fn` (`{:url :method :headers :body} -> {:status :body}`,
the same convention `cloudflare.client`/`cloud-itonami.runtime`/
`cloud-itonami.mail` already use) -- every namespace here is tested with a
stub, never only against a live account.

**Out of scope**: the OAuth2 consent/refresh flow itself. Callers pass a
valid `GMAIL_ACCESS_TOKEN` (env) or an explicit `:token`, the same way
`cloudflare.client` expects a pre-obtained `CLOUDFLARE_API_TOKEN` rather than
performing its own auth flow. Token acquisition is host-specific (desktop
OAuth loop, service account, a chat assistant's own connector) and
deliberately left to the caller.

## Usage

```clojure
(require '[gmail.threads :as threads]
         '[gmail.labels :as labels]
         '[gmail.drafts :as drafts])

;; GMAIL_ACCESS_TOKEN in the environment, or pass :token explicitly
(threads/list-threads {:q "from:wise.com in:inbox"})
;; => {:threads [{:id "..." :snippet "..."} ...] :resultSizeEstimate N}

(def todo-label-id (labels/find-or-create-label! "00_STATUS/01_要対応"))
(threads/modify-thread! thread-id {:add-label-ids [todo-label-id]})

(drafts/create-draft! {:to "reply@support.example.com"
                       :subject "Re: Action needed"
                       :body "..."
                       :thread-id thread-id})
;; => {:id "draft-id" :message {...}}

(require '[gmail.history :as history])
;; persist :historyId from any prior response, feed it back in next poll
(history/list-history last-seen-history-id)
;; => {:history [{:id "..." :messagesAdded [...] ...} ...] :historyId "..."}
;; a 404 here means the cursor is too old -- do a full resync via threads/list-threads
```

## Tests

```sh
clojure -M:test
```

No live account required -- every test injects a stub `:http-fn` and asserts
on the request shape (`cloudflare.client-test`'s pattern).
