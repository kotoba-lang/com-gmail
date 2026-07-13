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
client  auth (Bearer access token) + HTTP (injectable :http-fn) + JSON request/response envelope
threads list / get / modify (label add-remove) / archive
labels  list / create / find-or-create (by display name, resolving to a label id)
drafts  RFC 2822 plain-text message builder + create-draft! (optionally thread-scoped)
history users.history.list -- cursor-based "what changed since historyId" (incremental sync)
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
