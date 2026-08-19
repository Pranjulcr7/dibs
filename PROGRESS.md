# PROGRESS

## 2026-08-18 — M5 complete (scoped: no VLM)

`./gradlew check` green. Scope per SPEC §7 as revised: the VLM tier is dropped
entirely; ML Kit + the rule-based layout parser is the shipping path, and
manual line-item entry remains the primary path (one tap: FAB → itemized) —
scanning is an accelerator behind the group menu, never a gatekeeper.

- **Rule-based layout parser** (`:core:domain`, pure JVM): rows by vertical
  overlap; prices only in the right-aligned price column (a bare quantity "2"
  can never become $2); quantity prefixes ("2 x", "QTY 3"); keyword
  classification with subtotal/tax/tip/total, discounts as negative items,
  and payment rows ignored; the 0.02 arithmetic gate flags mismatches
  (edges 39/40) and never blocks; total-only receipts degrade to a single
  amount (edge 43); unreadable regions flag for manual entry (edge 36);
  multi-frame merge with boundary dedupe (edge 35); parsed-content
  fingerprints for same-receipt detection (edge 47).
- **ML Kit tier** (`:core:ocr`): bundled on-device Latin recognition (edge 38
  documented) behind an `OcrEngine` seam; 10s hard timeout (edge 46); engine
  failures degrade to "nothing found", never crash. The app runs on
  `NoOpReceiptParser` if the module's DI is dropped.
- **Scan flow** (`:feature:scan`): CameraX with guide frame + hints, gallery
  import, multi-frame; camera denial leaves gallery and manual entry
  (edge 33). Mandatory review (FR-R3) re-runs the gate on every edit, tip
  always editable (edge 42), duplicate-receipt warning. Approval prefills the
  itemized editor — assignment (incl. fractional consumption) is the same UI
  as manual entry. Receipt photo stored app-private, named by fingerprint.
- **Layout corpus** (`model/`): download-only scripts for CORD (CC BY-SA) and
  SROIE (MIT); images never committed. JVM harness feeds ground-truth boxes
  into the rule parser. Rates over 100 receipts each:

  | corpus | items found | total found | total == labeled |
  |---|---|---|---|
  | CORD | 99.0% | 83.0% | 79.8% |
  | SROIE | 62.0% | 64.0% | 52.6% |

  **Caveat (per SPEC §7):** these are scanned Southeast Asian receipts and do
  not predict performance on photographed Western receipts; the numbers are a
  layout-rule lower bound used to find bugs. The corpus already earned its
  keep: it exposed that the original two-decimal price rule read zero-decimal
  currency receipts (IDR/JPY-style) as having no prices at all (CORD items
  found was 6% before integer mode), and surfaced the discount-after-subtotal
  shape.
- Edges 44/45 (VLM download interruption / insufficient RAM) are obsolete
  with the VLM tier dropped — nothing is ever downloaded.
- Tests: 22 parser unit tests (edges named), 5 ML Kit wrapper tests, 5 scan
  ViewModel tests, 2 editor hand-off tests; corpus harness skips unless
  `DIBS_RECEIPT_CORPUS` is set.

### Deferred / notes

- FR-T4 collapses to nothing (single tier). FR-T5 (storage breakdown, clear
  receipt images) deferred; tracked for a follow-up settings pass.
- On-device verification of the camera flow is pending the phone reconnect,
  together with the 3c/3d/M4 installs.


## 2026-08-18 — M4 complete: import, export, backup

`./gradlew check` green. **Install to the phone pending — device disconnected;
3c/3d/M4 installs will all run when it reappears** (one `pm clear app.dibs` is
needed once: the dibs-op-v2 format change predates any on-device data worth
keeping).

- **Splitwise CSV import (FR-I1).** Domain parser for the English export:
  member names from the header, signed net columns reconstructed into exact
  paid/owed maps (debtors verbatim; payers split the residual by largest
  remainder over credit weights — sums exact to the cent), Payment rows →
  settlements, `Total balance` trailer used as a checksum, malformed rows
  skipped with line numbers. Every row's id is `BLAKE3(fileHash|row)`, so the
  same file can never import twice (edge 55). UI wizard: pick file (SAF) →
  rename members and mark "this is me" → preview (counts, per-currency totals,
  skipped rows, checksum warning, duplicate-file block) → confirm into a new
  group, self member claimed.
- **CSV export (FR-I2).** Splitwise-shaped export from the group overflow menu
  via SAF; proven re-importable by a round-trip test through the parser.
- **Encrypted backup (FR-I3/I4).** One `.dibs` file: whole op-log + identity
  master key + settings. Argon2id (t=3, m=32 MiB) → XChaCha20-Poly1305 with
  the header (magic, version, salt, KDF params) as AEAD associated data —
  tampering with any byte, parameters included, fails wholesale (edge 59).
  Wrong passphrase and corruption are indistinguishable and write nothing
  (edge 58). Restore is a set-union merge: idempotent, additive, never
  destructive (edge 57); the master key is adopted only on a device that has
  none (a live identity is never overwritten). Settings merge local-wins.
  Passphrase strength meter enforced at creation — WEAK refused in both the
  dialog and the ViewModel (edge 77).
- **Backup reminder (FR-I5).** Groups list banner when the ledger changed and
  the last backup is missing or 30+ days old. "Changed" is exact, not
  heuristic: `Ledger.totalOpCount` vs the count captured at backup — the log
  is append-only, so the count moving *is* the change.
- **Tests:** parser (12), export round-trip, backup codec (5: roundtrip,
  wrong passphrase, byte-flip AEAD rejection, salt uniqueness, garbage),
  backup service (4 Robolectric: fresh-device byte-identical restore with
  identity adoption, idempotent re-restore, merge-not-replace, wrong
  passphrase writes nothing), passphrase strength (6), import ViewModel (4),
  settings ViewModel backup events (3).

### Deferred / notes

- NFR-3 fold-time measurement with a 5k-expense import runs on the physical
  device in the quality pass (numbers, not estimates).
- Import binds all rows to one new group; importing into an existing group is
  intentionally not offered (see D34).

## 2026-08-18 — M3 stages 3c and 3d complete

`./gradlew check` green. (Install pending device reconnect, as above.)

- **3c — expense editor:** all six split modes (equal with per-member toggle,
  exact, percent (basis points), shares, adjustment, itemized with per-item
  consumers and proportional tax/tip), live remaining-bar with exact-sum
  guarantee and one-cent absorber note, category chips, date picker, multi-
  payer entry, draft survival across process death (SavedStateHandle, edge
  61), field-level edit diffing with atomic amounts edit, duplicate-expense
  prefill, repeat-rules (device-local, D33) with notification prefill —
  reminders never auto-create expenses.
- **3d — settle & manage:** balances tab (pairwise who-owes-whom by exact
  per-expense decomposition — property-tested equal to member nets — with
  opt-in simplify toggle and explanation), settle tab (suggested transfers,
  record-payment dialog with editable amount, manual payment, write-off with
  history label, edge 14), settlement history, search/filter, 30-day trash
  with restore (edge 21), archive/unarchive, ad-hoc "just this once" groups
  (FR-G5), app lock via BiometricPrompt (fail-open without credentials),
  FLAG_SECURE toggle, theme/dynamic-color/display-name/currency settings.


## 2026-08-18 — Identity model (SPEC §5.9) landed ahead of M6

Model + ops + hooks now; join/invite UI ships with M6. `./gradlew check` green.

- Crypto (`:core:domain`): HKDF (RFC-vector-pinned), master-key→identity
  keypair (Ed25519, deterministic — same master, same identity), per-group
  transport keys, BLAKE3 handle fingerprints with deterministic 4→6 digit
  collision extension, strict `dibs://join` link codec (edge-76 style caps).
- Six signed ops — CreateInvite, RevokeInvite, ClaimMember, ApproveMember,
  RemoveMember, SetJoinPolicy — validated in the fold: signatures, actor
  authorization, invite lifecycle (revoked/expired/exhausted), single-use
  reuse rejection, bootstrap claim, KeyAlreadyBound, RemovalBlocked. All
  quarantine reasons typed; every decision deterministic (D25–D29).
- Tests: crypto vectors and determinism; handle collision (hardcoded colliding
  masters); fold validations incl. named race tests — concurrent claims on one
  member and claim-vs-revoke, byte-identical in both merge orders; identity
  actions added to the convergence simulation; a third golden vector freezes
  all new encodings (M2/3b vectors untouched). Mutation-checked: signature
  bypass and uncounted invite uses each trip multiple tests.
- Hooks live now: master key Keystore-wrapped in `:core:data`
  (`dibs-master.key`; must join the M4 backup payload), create-group flow
  bootstrap-claims the self member, Members screen shows " #a1b2" fingerprints
  monospace-muted on claimed members only — names never styled as verified.
- Note: groups created before this change have unclaimed members (no
  fingerprint) — correct; they become claimable via invites in M6.


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
