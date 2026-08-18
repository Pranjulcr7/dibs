# PROGRESS

## 2026-08-18 — M3 stage 3b complete (awaiting review)

`./gradlew check` green; installed to the Galaxy S24 Ultra. Groups and members
are fully functional end to end on one device.

- **Domain:** new op payloads `CreateGroup`, `EditGroup` (field-level LWW with
  cleared-vs-untouched markers), `ReactivateMember`; `GroupMeta` folds into
  `GroupState` (canonical line emitted only when present, so all M2 golden
  vectors stand untouched); a new golden vector freezes the 3b encodings;
  convergence simulation now exercises the new payloads. `Ledger` interface
  extracted (SPEC §5.5) — features depend on domain only; implementations bound
  in `:app`.
- **Data:** `LedgerRepository` implements `Ledger` (repository stamps the
  display-only wall clock), `observeGroupIds` seeded from the ops table and
  live-updated, verified across reopen.
- **UI:** group list with color-coded "you're owed / you owe / settled up"
  cards; create group (name, emoji, currency); group detail with header net
  position, syncing chip, Expenses/Balances/Settle tabs (Expenses fills in 3c,
  Settle in 3d); balances tab with "you" pinned first and per-currency
  sections; members screen with add/rename/deactivate/reactivate, "You" chip,
  duplicate-name hint with deterministic avatar colors (edges 12/13/26);
  edit-group dialog. Navigation: home / create-group / group / members routes.
- **Tests first throughout:** projector tests for the new payloads, golden
  vector for 3b encodings, repository interface tests, ViewModel tests over a
  FakeLedger that wraps the real domain fold, Compose tests for list, create,
  members, and empty states. 3a's screens untouched except the groups list
  gaining content.
- Self-identity mapping is device-local settings (D23); merge stays
  detection-only (D16/D24).


## 2026-08-18 — M2 complete

`./gradlew check` green: 138 tests (112 domain JVM + 26 data), ktlint, detekt,
lint, purity check, coverage gate. Stopped at the M2 boundary; M3 not started.

### M2 — Ledger and persistence (shipped)

**Domain op-log CRDT** (`app.dibs.core.domain.ledger`, pure Kotlin):

- `Op` with BLAKE3 content-addressed id over a versioned hand-rolled canonical
  encoding (DECISIONS.md D11/D12); payloads: add/rename/deactivate member,
  add/edit/delete/restore expense, record settlement. Edits are field-level
  with the money block atomic (D13).
- `OpLog`: merge = set union; replay order `(lamport, deviceId, opId)`; ops
  with incomplete ancestry buffer as `pending` (edge 24); `heads` become the
  next local op's parents. `GroupLedger` owns the append rules (D17).
- `GroupProjector`: pure left fold to `GroupState` (sorted maps, full edit
  history with device attribution, tombstones, typed quarantine — D14/D15);
  `canonicalBytes()` for byte-identical comparison; balances derive from
  active expenses only. `ClockSkew` (edge 22), `DuplicateMembers` (edge 26).

**Convergence simulation (the centerpiece):** 500 random multi-device
scenarios — 2–5 devices, random interleaved ops, gossip with drops, duplicates
and reordering, one partitioned device back-filled only at the end in shuffled
re-delivered chunks — every device folds to byte-identical canonical state,
pending empties, balances agree, and a fresh device folding the raw union
matches too. Directed scenarios cover a from-the-start partition, concurrent
delete+edit in both merge orders, and byte-identical quarantine bookkeeping.
Edges 20–26 each have named tests. Mutation-verified: lamport-only ordering
and disabled causal buffering each trip multiple tests, including the
simulation itself.

**Data layer** (`:core:data`):

- Migration harness was committed **before** the first schema existed: a
  coverage guard over exported schema JSONs plus a MigrationTestHelper walk;
  verified end-to-end by bumping the version without a migration (two tests
  fail). `DatabaseBackupManager` copies the db file aside on any version
  change, tracked via a sidecar version file (D18).
- Schema v1 (exported, committed): `ops` as source of truth; `members`,
  `expenses`, `settlements`, `fold_meta` as transactional projections.
- `LedgerRepository`: append/ingest/observe with **incremental fold** — an
  O(1) checkpoint-intact test folds only the new suffix; an earlier-sorting
  arrival triggers a full refold; tests prove both paths equal a from-scratch
  fold and count which path ran. Repository reopen refolds persisted ops to
  identical bytes.
- Keystore-backed key (NFR-11): random 256-bit SQLCipher passphrase wrapped by
  a Keystore AES-GCM key; store logic JVM-tested with a fake cipher;
  `DibsDatabase.buildEncrypted` is the production path (on-device check in M3).

### Addendum (2026-08-18): on-device encrypted-database test

`EncryptedDatabaseTest` (androidTest in `:core:data`) now covers the real
startup sequence with no fakes, run on a physical Galaxy S24 Ultra (Android
16): Keystore-wrapped passphrase round-trips across store instances; write →
close → reopen through `DibsDatabase.buildEncrypted` folds to byte-identical
state; the file on disk is not plaintext SQLite; a lost key file makes the
database unreadable (the passphrase is load-bearing). First run caught a real
device-only bug: Android's boot-classpath commons-codec shadowed the bundled
`Hex` and broke op-id computation on hardware — fixed by hand-rolling hex in
`OpFactory` (op ids unchanged; see DECISIONS.md D19). All 4 tests pass on
device; the JVM suite and `./gradlew check` stay green. Note: this test is not
in CI (needs hardware); rerun it via `:core:data:connectedDebugAndroidTest`
when touching the key or database-open path.

### Deferred / notes

- Projection tables store expense fields/history as JSON blobs; M3 will shape
  them for real UI queries as screens dictate.
- `GroupState` fold copies sorted maps per op (fine at current scale);
  NFR-3 (5,000-expense group < 100 ms) gets measured and optimized in M7.
- Vector clocks / sync-gap detection (edge 75) belong to M6's transport work.
- A member-merge op (edge 26's user action) is intentionally absent until M3
  UI exists to drive it.

## 2026-08-17 — M0 and M1 complete

`./gradlew check` is green (tests + ktlint + detekt + Android Lint + domain
purity check + coverage gate). Stopped at the M1 boundary per plan; M2 not
started.

### M0 — Foundation (shipped)

- Multi-module skeleton exactly per CLAUDE.md: `:app`, `:core:domain`,
  `:core:data`, `:core:ocr`, `:core:sync`, `:feature:{groups,expense,settle,scan,settings}`.
  Non-domain core and feature modules are intentionally empty shells with
  correct dependency edges.
- Gradle 8.14.3 wrapper, version catalog (`gradle/libs.versions.toml`),
  convention plugins in `build-logic/convention` (Kotlin 2.2.10, AGP 8.11.1,
  JDK 17, compileSdk 36 / minSdk 26).
- **Module dependency check**: `verifyPureJvm` fails the build if `:core:domain`
  gains an Android artifact (resolved *or requested*), any project dependency,
  or an `android.`/`androidx.` import. Wired into `check` and mutation-tested
  (verified it actually fails on an injected androidx dependency, both AAR and
  pure-JVM jar).
- Hilt via KSP, placeholder Compose app (single activity, M3 theme with dynamic
  color, light/dark/large-font previews, strings externalized), checked-in dummy
  debug keystore so a clean clone builds with zero setup.
- ktlint + detekt on every module, `config/detekt/detekt.yml`, `.editorconfig`.
- CI (`.github/workflows/ci.yml`): `./gradlew check` + `:app:assembleDebug` on
  every PR/push, fork-friendly, reports uploaded on failure. `release.yml`
  builds tagged releases (signing deferred to M7 by design). Dependabot weekly.
- Open-source scaffolding per OPEN_SOURCE_SETUP.md §3: Apache-2.0 LICENSE,
  README, CONTRIBUTING, CODE_OF_CONDUCT (Covenant 2.1 with real contact),
  SECURITY (threat model), ARCHITECTURE, PRIVACY, CHANGELOG, issue templates,
  PR template. SPDX headers in source files.

### M1 — Domain core (shipped)

All pure Kotlin/JVM in `:core:domain`; no emulator needed for any test.

- `Money` (`Long` minor units, overflow-checked ops), `CurrencyCode`,
  `Currencies` exponent table (0/2/3/4-decimal currencies — see DECISIONS.md D1).
- All six split modes (`SplitCalculator` + `SplitSpec`): equal, exact amounts,
  percentage (basis points), shares, adjustment, itemized. Every mode funnels
  through one largest-remainder allocator (`LargestRemainder`) with the
  ascending-member-UUID tiebreak; `Allocation.roundingAdjustment` exposes who
  absorbed leftover cents for the UI.
- Itemized math: exact rational per-member subtotals (BigInteger over a common
  denominator), tax+tip prorated by subtotal, one rounding at the end against
  the true total. Negative (discount) items supported.
- `Balances.net`: per-currency `paid − owed` with multi-payer expenses and
  settlement payments; zero-sum by construction; output sorted by currency and
  member id.
- `Settlement.simplify`: greedy max-debtor/max-creditor with `(amount desc,
  memberId asc)` ordering; the code comment states plainly it is not provably
  minimal (that problem is NP-hard).

**Test evidence (exit criteria):** 73 tests, all passing, ~2 s on JVM.

- Property tests at 10,000 iterations each: (a) sum invariance per split mode —
  six separate properties; (b) net balances sum to zero over random multi-payer
  ledgers with settlements; (c) settlement terminates in ≤ n−1 transfers and
  zeroes every balance with all-positive transfers; (d) byte-identical canonical
  output on rerun *and under shuffled input collection order*, for splits,
  balances, and settlement. Plus a share-accuracy property (every allocation
  within one minor unit of the exact rational share).
- SPEC §6.1 edge cases 1–11: one named test each (11 = the sum-invariance
  property), including $10/3, $0.01/5, zero total, negative rejection,
  `Long.MAX_VALUE` and overflow, JPY, KRW, KWD, BHD splits — not USD-only.
- Coverage: **97.9% line / 95.5% method** in `:core:domain`, with a Kover gate
  at 90% wired into `check` (NFR-9).
- The allocator was mutation-tested by hand (broken tiebreak and dropped
  leftover distribution both trip multiple tests).

### Deferred / notes

- Feature, data, ocr, sync modules are empty shells until their milestones.
- Release signing + reproducible-build work deferred to M7 (release workflow
  currently attaches an unsigned APK).
- F-Droid FOSS-OCR build flavor decision (OPEN_SOURCE_SETUP §7) not yet made —
  must be decided no later than M5 when ML Kit lands.
- README has no screenshots yet (nothing worth showing until M3); badge URLs
  assume a `dibs-app` org and need updating when the repo is pushed.
- `Op`/`Ledger` types (SPEC §5.5) intentionally not started — they are M2.

### Decisions

See DECISIONS.md D1–D10 (ISO-4217 table vs android.icu, basis-point
percentages, adjustment semantics, itemized zero-subtotal rule, discount
validation, zero-share handling, Kotest naming, multi-payer model, SDK 36,
purity-check scope).
