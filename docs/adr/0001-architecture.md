# ADR-0001 — com-gmail architecture: a portable Gmail API v1 boundary

- Status: Accepted
- Date: 2026-07-06
- Context tags: gmail-api, portable-cljc, vendor-client
- Builds on: `kotoba-lang/com-cloudflare` (client/`:http-fn` injection
  pattern), `gftdcojp/local-manimani` (`server/src/gmail.ts`, the TS Gmail
  boundary this library gives a portable `.cljc` counterpart to)

## Decision

Give Gmail the same treatment `com-cloudflare` gave the Cloudflare API: one
tested `client` namespace (auth + HTTP + JSON envelope, injectable
`:http-fn`) plus one namespace per capability area (`threads`, `labels`,
`drafts`), all pure `.cljc` except the JVM-only default transport and
`java.util.Base64`-dependent MIME builder.

## Why extract rather than build fresh per host

manimani's own Gmail wiring lives in TypeScript (`server/src/gmail.ts`) and
isn't reachable from `.cljc` hosts (a babashka runner, `kotoba-procedure-clj`
consumers, a future actor). A chat assistant's own ad hoc Gmail connector is
frequently granted read-only scope and can't perform the label/draft writes
a triage workflow needs. Rather than hand-roll thread-list/label-modify/
draft-create HTTP calls again per project, this library is the one place
that logic lives, tested against stubs.

## Module boundaries

```
client      auth (Bearer access token) + HTTP (injectable :http-fn) + JSON request/response envelope
threads     list / get / modify (label add-remove) / archive / trash / untrash / delete
labels      list / create / find-or-create (by display name, resolving to a label id) / delete
drafts      RFC 2822 plain-text message builder + get / list / create / update / delete (optionally thread-scoped)
history     users.history.list -- cursor-based "what changed since historyId" (incremental sync)
mime        decode an INBOUND message payload: body data (base64url) / headers (case-insensitive) / plain-text + html body / attachment descriptors
attachments download an inbound attachment's raw bytes (users.messages.attachments.get, under /messages not /threads)
```

## Non-goals

- The OAuth2 consent/refresh flow itself -- callers supply a valid access
  token (env `GMAIL_ACCESS_TOKEN` or explicit `:token`), mirroring
  `cloudflare.client`'s `CLOUDFLARE_API_TOKEN` convention.
- HTML draft bodies -- `drafts/->raw-message` builds a plain-text body only
  (multipart/mixed for file attachments is supported as of the 2026-07-13
  addendum below; multipart/alternative for an HTML+plain-text body pair is
  not).

## Consequences

- `gftdcojp/local-manimani`'s babashka/`.cljc` path (its own README already
  treats this as canonical over the TS `server/`) can depend on this library
  instead of shelling out or re-deriving Gmail HTTP calls.
- `kotoba-lang/kotoba-procedure-clj` procedures whose steps are "reply to
  this thread" or "wait for the counterparty's Gmail reply" can drive Gmail
  state through this library without a code dependency between the two
  (same "shared vocabulary, no shared code" relationship as
  `kotoba-issue-clj`/`kotoba-ledger-clj`).

## Addendum (2026-07-13): `gmail.history`

`kotoba-lang/tayori`'s own `tayori.channel.email/list-new-messages` (ADR
2607061500) returns `[]` unconditionally, with a code comment explaining why:
Gmail's `threads.get`/`threads.list` have no "since" filter, so polling by
`q`/date alone can't distinguish "new since last time" from "everything
again" -- a real implementation needs `users.history.list` against a
persisted `historyId` cursor, which this library didn't yet expose. Added
`gmail.history/list-history` to close that gap (consumed first by
`kotoba-lang/mail-archive`'s incremental sync; wiring it into tayori's own
`list-new-messages` is a separate follow-up, not done here).

**Known limitation carried over from `gmail.client/request!`**: its
query-string builder (`(map (fn [[k v]] (str (name k) "=" v)) query)`)
serializes one value per key and does not URL-encode. Gmail's
`historyTypes` filter is a *repeated* query param
(`historyTypes=X&historyTypes=Y`), which this shape cannot express --
`gmail.history/list-history` intentionally does not expose a `history-types`
param rather than silently mis-serializing one. Any caller needing to
narrow the returned records to specific change types should filter the
`:history` response client-side. Fixing this properly (real query-string
encoding, repeated-key support) would touch `client.cljc`'s core request
builder and is out of scope for this addition.

## Addendum (2026-07-13): `drafts/->raw-message` gains `:cc` and `:references`

A real reply-into-an-existing-thread case (multiple Cc'd recipients, more
than one prior message) needed both: `:cc` accepts a single address string
or a collection (joined with `", "`); `:references` lets a caller supply the
full RFC 2822 References chain instead of the previous behavior of always
mirroring `:in-reply-to` alone -- Gmail's own threading heuristic (and other
clients') considers the whole chain, not just the immediate parent, so
always collapsing References to one Message-ID under-informed threading on
deep replies. `:references` is optional; omitting it preserves the old
mirror-`:in-reply-to` behavior exactly, so this is additive/non-breaking.

## Addendum (2026-07-13): `drafts/->raw-message` gains `:attachments`

A reply needed to carry reference diagrams (SVG) alongside the text body.
`:attachments` is a collection of `{:filename :content-type :bytes}`;
when present the message becomes `multipart/mixed` (a `text/plain` part
plus one part per attachment, base64-encoded with RFC 2045 line wrapping
via `Base64/getMimeEncoder` -- a *different* encoding layer than the
URL-safe, unwrapped encoding the outer `message.raw` field itself needs;
conflating the two silently produces a draft Gmail can't render). Omitting
`:attachments` keeps the exact prior single-part plain-text shape
(verified byte-for-byte via a dedicated test), so this is additive/
non-breaking. This namespace does no filesystem I/O -- callers read
attachment bytes themselves, keeping `->raw-message` testable with plain
byte arrays and no real files.

## Addendum (2026-07-13): `client/jvm-http-fn` gains `:delete`, `drafts/delete-draft!`

`jvm-http-fn`'s method dispatch only built `:get`/`:post` requests --
anything else threw `"Unsupported HTTP method"`, discovered when trying to
retire a superseded draft before creating its replacement. Added `:delete`
(`HttpRequest.Builder/DELETE`) and `drafts/delete-draft!` (`DELETE
/drafts/{id}`, matching `create-draft!`'s injectable-`:http-fn`
convention). Gmail's `drafts.delete` returns an empty 204 body, which
`client/request!` already treats as `nil` rather than a JSON parse
target -- no change needed there.

## Addendum (2026-07-13): `gmail.mime` -- decode INBOUND messages

Every namespace so far acted on Gmail *metadata* (thread ids, label ids,
history records) or built *outbound* MIME (`drafts/->raw-message`). Nothing
read an inbound message's actual content: `threads/get-thread` (format=full)
returns each message's `:payload` as a base64url-encoded body inside an
arbitrarily nested `:parts` tree, and callers were left holding those raw
strings with no portable way to answer "what did they say, and did they
attach anything?". `gmail.mime` is the read counterpart to the drafts
encoder:

- `decode-body-data` -- base64url *decode* (the URL-safe alphabet Gmail uses,
  the inverse of `drafts`' own outgoing encoder; the standard decoder would
  reject a `-`/`_`), nil/blank-safe.
- `header` (case-insensitive lookup -- RFC 2822 header names are
  case-insensitive even though Gmail preserves sender casing) and `headers`
  (flat `{name value}` map; documented to collapse genuinely multi-valued
  headers like `Received` to their last instance -- callers needing every
  hop filter the raw `:headers` vector).
- `plain-text-body` / `html-body` -- a single recursive part walk that
  handles single-part `text/plain`, `multipart/alternative` (returns the
  right half), and `multipart/mixed` (returns the body part, skips
  attachments) uniformly, so the caller doesn't branch on message shape.
- `attachment-parts` -- collects `{:filename :mime-type :attachment-id
  :size}` for every part with a non-blank `:filename` (Gmail's marker for an
  attachment vs. a body part), the descriptors `gmail.attachments` then
  fetches by id.

Pure `.cljc` shape; the base64 decode is JVM-only (`java.util.Base64`), the
same single-platform posture as the drafts encoder.

## Addendum (2026-07-13): `gmail.attachments` -- download attachment bytes

Gmail does not inline a large attachment's bytes into the message payload --
the attachment part carries only a `:body {:attachmentId ... :size N}`
descriptor (surfaced by `mime/attachment-parts`), and the bytes are fetched
on demand from `users.messages.attachments.get`. That endpoint lives under
`/messages/{messageId}/attachments/{attachmentId}` (NOT `/threads/...`), but
`client/api-base` already ends in `/users/me`, so the path is just that tail.
`get-attachment` returns the raw `{:size :data}` envelope; `attachment-bytes`
adds the base64url decode and returns a raw `byte[]` (deliberately NOT
stringified -- attachments are binary), ready to write to a file or hand back
to `drafts/->raw-message`'s `:attachments :bytes` to re-attach. Together with
`gmail.mime` this makes the library able to fully *read* an inbound message,
not just act on its metadata.

## Addendum (2026-07-13): `drafts` gains `get-draft!` / `list-drafts!` / `update-draft!`

`drafts` could create and delete but not read or edit. The gap that forced
the fix: editing a draft's body meant `delete-draft!` + `create-draft!`,
which churned the draft id (breaking any reference a caller held) and briefly
left the thread with no draft at all. `update-draft!` (Gmail's `drafts.update`,
`PUT /drafts/{id}`, same `message`/`->raw-message` construction as
`create-draft!`) keeps the id stable and the edit atomic. `get-draft!`
(`GET /drafts/{id}`) and `list-drafts!` (`GET /drafts`, paginated via the
same `:max-results`/`:page-token` -> `maxResults`/`pageToken` convention as
`threads/list-threads`) round out read access. `update-draft!` needs a `PUT`
transport, so `client/jvm-http-fn` grew a `:put` branch alongside the earlier
`:delete` one (same shape as `:post`).

## Addendum (2026-07-13): `labels/delete-label!`, `threads` trash/untrash/delete

Destructive lifecycle operations the library was missing, with Gmail's own
reversible-vs-permanent distinction preserved explicitly rather than
conflated:

- `labels/delete-label!` (`DELETE /labels/{id}`) -- removes a *user* label
  from every thread that carried it (system labels can't be deleted).
- `threads/trash-thread!` (`POST /threads/{id}/trash`) -- **reversible**;
  Trash retains the thread (~30 days) and `threads/untrash-thread!`
  (`POST /threads/{id}/untrash`) restores it. `untrash-thread!` is exposed
  *alongside* `trash-thread!` on purpose -- a trash without an untrash is a
  trap, stranding a caller with no programmatic recovery path.
- `threads/delete-thread!` (`DELETE /threads/{id}`) -- **PERMANENT**, bypasses
  Trash entirely, no undo (and needs the broad `https://mail.google.com/`
  scope; `gmail.modify` can trash but not delete). The docstring says so
  loudly and points callers at `trash-thread!` as the default.

## Addendum (2026-07-13): `client/request!` now percent-encodes query values

The query-string builder emitted `(str (name k) "=" v)` with **no encoding
at all**, so a `:q` value with a space (`"after:2024/01/01 in:inbox"`) or any
`&`/`+`/`=`/non-ASCII character corrupted the URL -- exactly the kind of
Gmail search string a real caller passes. Fixed by percent-encoding each
*value* (keys stay literal -- they're always simple identifiers like
`q`/`maxResults`/`pageToken` here). `java.net.URLEncoder` was deliberately
avoided: it is form-encoding, which serializes a space as `+` (a literal `+`
in a URI, ambiguous -- Gmail wants `%20`) and over-encodes `:`/`/`, the two
characters Gmail search syntax most needs readable and that RFC 3986 already
allows unencoded in a query. Instead a small byte-wise encoder keeps RFC 3986
`unreserved` plus `:`/`/` literal and percent-encodes every other UTF-8 byte,
so the delimiters space/`&`/`=`/`+`/`#` can never be misread as syntax
(verified: a space round-trips to `%20` not `+`, and literal `+`/`&` escape
to `%2B`/`%26`). This is a strictly smaller, uncontroversial bug fix and does
**not** add repeated-key support -- that larger design decision (needed for
`historyTypes` and other array params) remains documented as out of scope in
the `gmail.history` addendum above, unchanged.
