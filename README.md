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
gmail.client      -- auth (Bearer OAuth2 access token) + HTTP (injectable :http-fn) + JSON envelope
gmail.threads     -- list/get threads, modify (add/remove label ids), archive, trash/untrash, delete
gmail.labels      -- list, create, find-or-create (by display name), delete
gmail.drafts      -- get/list/create/update/delete a reply draft (plain-text RFC 2822, optional attachments/thread)
gmail.history     -- users.history.list, for cursor-based incremental sync (what's changed since a historyId)
gmail.mime        -- decode inbound messages: body data, headers, plain-text/html body, attachment descriptors
gmail.attachments -- download an inbound attachment's raw bytes (users.messages.attachments.get)
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
                       :cc ["cc1@example.com" "cc2@example.com"]  ; optional, string or collection
                       :subject "Re: Action needed"
                       :body "..."
                       :thread-id thread-id
                       :in-reply-to "<original-msg-id@mail.gmail.com>"
                       :references "<msg-1@mail.gmail.com> <msg-2@mail.gmail.com>"  ; optional, full chain
                       :attachments [{:filename "diagram.svg" :content-type "image/svg+xml"
                                     :bytes (.readAllBytes (java.io.FileInputStream. "diagram.svg"))}]})  ; optional
;; => {:id "draft-id" :message {...}}

(require '[gmail.history :as history])
;; persist :historyId from any prior response, feed it back in next poll
(history/list-history last-seen-history-id)
;; => {:history [{:id "..." :messagesAdded [...] ...} ...] :historyId "..."}
;; a 404 here means the cursor is too old -- do a full resync via threads/list-threads

;; Read an INBOUND message you just fetched (threads/get-thread returns
;; format=full, so each message's :payload is already the tree below):
(require '[gmail.mime :as mime]
         '[gmail.attachments :as attachments])

(let [payload (-> (threads/get-thread thread-id) :messages last :payload)]
  (mime/header payload "From")           ; => "Sender <sender@example.com>" (case-insensitive)
  (mime/plain-text-body payload)         ; => "the decoded text/plain body" (walks nested parts)
  (mime/html-body payload)               ; => "<p>...</p>" or nil
  (doseq [{:keys [filename attachment-id]} (mime/attachment-parts payload)]
    ;; large attachments aren't inlined -- fetch each by its id:
    (let [bytes (attachments/attachment-bytes message-id attachment-id)]
      (with-open [o (java.io.FileOutputStream. filename)] (.write o bytes)))))

;; Edit a draft in place (keeps the draft id stable -- no delete+recreate churn):
(drafts/update-draft! draft-id {:to "reply@support.example.com"
                                :subject "Re: Action needed"
                                :body "revised body"
                                :thread-id thread-id})

;; Trash is reversible (retained ~30 days); delete is PERMANENT -- prefer trash:
(threads/trash-thread! thread-id)     ; reversible via (threads/untrash-thread! thread-id)
;; (threads/delete-thread! thread-id) ; PERMANENT, bypasses Trash -- no undo
```

## Tests

```sh
clojure -M:test
```

No live account required -- every test injects a stub `:http-fn` and asserts
on the request shape (`cloudflare.client-test`'s pattern).
