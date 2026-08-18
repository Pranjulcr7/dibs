# CLAUDE.md — Dibs

Project constitution. Read this before every task. If a request conflicts with this
file, say so rather than silently deviating.

---

## What Dibs is

A native Android app for splitting shared expenses. Local-first: the phone is the
source of truth. No backend server exists, and none will be added. Free, open
source, no ads, no accounts, no telemetry.

The target user is a non-technical person migrating off Splitwise. If a feature
requires explaining CRDTs, key exchange, or quantization to them, the feature is
wrong or the UI is wrong.

## Non-negotiable invariants

Violating any of these is a bug regardless of what a task description says.

1. **Money is never a floating-point number.** Not `Double`, not `Float`, not
   `BigDecimal` in storage. Money is `Long` minor units (cents) plus an ISO-4217
   currency code, wrapped in a `Money` value class. Any PR introducing a `Double`
   in a money path is rejected.
2. **Splits always sum exactly to the total.** Every split algorithm must return
   allocations whose sum equals the expense total to the cent, with zero drift.
   This is enforced by a property-based test, not by inspection.
3. **No network calls except opt-in sync.** The app must function fully in
   airplane mode. There is no analytics SDK, no crash reporter that phones home
   by default, no ad SDK, no Firebase. Receipt images never leave the device.
4. **The core domain layer has zero Android imports.** `:core:domain` is pure
   Kotlin/JVM. It must compile and test without an emulator.
5. **Local data survives.** No migration may destroy user data. Every schema
   change ships with a tested migration and a pre-migration backup.
6. **All operations are append-only and deterministic.** The ledger is an op-log.
   Two devices that have seen the same set of ops must compute byte-identical
   state. No wall-clock ordering, no server arbitration.

## Architecture

Multi-module, strict dependency direction (arrows point at dependencies):

```
:app  ->  :feature:*  ->  :core:domain
                      ->  :core:data   ->  :core:domain
          :core:ocr    ->  :core:domain
          :core:sync   ->  :core:domain
```

- `:core:domain` — pure Kotlin. Money, Expense, Group, split algorithms,
  settlement, the op-log CRDT. No Android, no Room, no coroutines-android.
- `:core:data` — Room + SQLCipher, repositories, op-log persistence, DataStore.
- `:core:ocr` — receipt capture pipeline behind a `ReceiptParser` interface.
- `:core:sync` — invite codes, encryption, transport behind a `SyncTransport`
  interface.
- `:feature:*` — one module per screen group (groups, expense, settle, scan,
  settings). Jetpack Compose + ViewModel + unidirectional data flow.
- `:app` — navigation host, DI wiring, nothing else.

**The two swappable tiers.** `ReceiptParser` and `SyncTransport` are interfaces
with multiple implementations selected at runtime. Never let a concrete
implementation leak into a feature module. The app must still build and run with
`NoOpReceiptParser` and `ManualFileTransport` only.

## Stack

| Concern | Choice |
|---|---|
| Language | Kotlin, JDK 17 |
| UI | Jetpack Compose, Material 3, single Activity |
| Min / target SDK | 26 / latest stable |
| DI | Hilt |
| Async | Coroutines + Flow |
| Persistence | Room over SQLCipher |
| Preferences | DataStore (Proto) |
| Serialization | kotlinx.serialization |
| Camera | CameraX |
| Default OCR | ML Kit Text Recognition v2 (bundled, on-device) |
| Optional VLM | MediaPipe LLM Inference API, downloaded on demand |
| Background work | WorkManager |
| Testing | JUnit5, Turbine, Kotest property tests, Robolectric, Compose UI test |
| Build | Gradle version catalogs, convention plugins |

## Conventions

- Package root `app.dibs`.
- ktlint + detekt run in CI and must pass.
- Every public function in `:core:domain` has KDoc explaining the *why*.
- Tests are named `` `given X when Y then Z` `` in backticks.
- Compose: stateless composables take data + lambdas. State lives in ViewModels.
  Every screen has a `@Preview` for light, dark, and large-font.
- No `!!`. No `runCatching` swallowing errors silently. No `GlobalScope`.
- Strings live in `strings.xml` from day one, including the first draft. No
  hardcoded user-facing text in Compose.
- Currency and dates are formatted with `android.icu`, never hand-rolled.

## Working style

- Work milestone by milestone. Do not start M(n+1) until M(n)'s tests pass.
- Write the test before the implementation for anything in `:core:domain`.
- After each milestone: run `./gradlew check`, then update `PROGRESS.md` with
  what shipped, what was deferred, and any decision that deviated from SPEC.md.
- When SPEC.md is ambiguous, pick the option that is simpler for the end user,
  implement it, and note the choice in `DECISIONS.md`. Do not stall asking.
- When SPEC.md is *wrong* (contradicts an invariant, or is technically
  infeasible), stop and say so.
- Commit per logical unit with conventional-commit messages. Never commit
  secrets, keystores, or dataset images.

## Explicitly out of scope

Do not build these, and push back if asked mid-stream:

- Any server, backend, cloud function, or hosted API.
- User accounts, passwords, email, phone verification.
- Payment processing or bank integration. Dibs records who owes what; it never
  moves money.
- Ads, analytics, tracking, attribution.
- iOS (later, separate effort — but keep `:core:domain` logic portable in spirit).
- Cryptocurrency of any kind.
