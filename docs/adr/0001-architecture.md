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
```

## Non-goals

- The OAuth2 consent/refresh flow itself -- callers supply a valid access
  token (env `GMAIL_ACCESS_TOKEN` or explicit `:token`), mirroring
  `cloudflare.client`'s `CLOUDFLARE_API_TOKEN` convention.
- HTML/multipart draft bodies -- `drafts/->raw-message` builds a minimal
  plain-text message only; richer MIME construction is left to callers who
  need it (or a follow-up namespace).

## Consequences

- `gftdcojp/local-manimani`'s babashka/`.cljc` path (its own README already
  treats this as canonical over the TS `server/`) can depend on this library
  instead of shelling out or re-deriving Gmail HTTP calls.
- `kotoba-lang/kotoba-procedure-clj` procedures whose steps are "reply to
  this thread" or "wait for the counterparty's Gmail reply" can drive Gmail
  state through this library without a code dependency between the two
  (same "shared vocabulary, no shared code" relationship as
  `kotoba-issue-clj`/`kotoba-ledger-clj`).
