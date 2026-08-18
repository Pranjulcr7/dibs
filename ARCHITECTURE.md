# Architecture

Companion to [CLAUDE.md](CLAUDE.md) (invariants) and [SPEC.md](SPEC.md) (full
technical design). This is the orientation map for contributors.

## Module map

```
:app  ─►  :feature:*  ─►  :core:domain
                      ─►  :core:data   ─►  :core:domain
          :core:ocr   ─►  :core:domain
          :core:sync  ─►  :core:domain
```

| Module | Contents | Android? |
|---|---|---|
| `:core:domain` | Money, splits, settlement, balances, the op-log CRDT | **No — pure Kotlin/JVM, enforced by the `verifyPureJvm` build check** |
| `:core:data` | Room + SQLCipher, repositories, op-log persistence, DataStore | Yes |
| `:core:ocr` | Receipt capture pipeline behind the `ReceiptParser` interface | Yes |
| `:core:sync` | Invite codes, encryption, transport behind `SyncTransport` | Yes |
| `:feature:groups` `:feature:expense` `:feature:settle` `:feature:scan` `:feature:settings` | One Compose screen group each; ViewModel + unidirectional data flow | Yes |
| `:app` | Single activity, navigation host, Hilt wiring — nothing else | Yes |

Build logic lives in `build-logic/convention` as convention plugins; versions
live in `gradle/libs.versions.toml` and nowhere else.

## Money

Money is a `Long` count of ISO-4217 minor units plus a currency code — never a
float of any kind. `$10.00` is `Money(1000)` with `USD`; `¥1000` is `Money(1000)`
with `JPY` (exponent 0). All split math funnels through one largest-remainder
allocator, so every allocation sums to its total exactly and every device
computes the identical result (ties broken by member UUID, never by list order).

## Why an op-log

The ledger is an append-only log of small immutable facts ("expense added",
"member renamed", "settled up"), each content-addressed by hash. Merging two
devices is a set union — idempotent and order-free. Replay order is
`(lamport, deviceId, opId)`, never wall clock, so two devices that have seen the
same ops fold to byte-identical state without a server to arbitrate. Edits are
field-level last-writer-wins in that same order; deletes are tombstones, which is
what makes 30-day trash recovery trivial.

We deliberately did not adopt a general-purpose CRDT library (e.g. Automerge via
JNI): the domain needs an ordered log of financial facts, not arbitrary JSON
merging, and ~400 lines of fully-testable Kotlin beats a native dependency. The
`Ledger` interface hides the implementation so that choice can be revisited.

## The two swappable tiers

`ReceiptParser` (ML Kit rule-based parser by default, optional on-device VLM)
and `SyncTransport` (manual file exchange first, opt-in relay later) are
interfaces selected at runtime. Feature modules never see a concrete
implementation; the app must build and run with `NoOpReceiptParser` and
`ManualFileTransport` alone.
