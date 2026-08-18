# PLAN — M2 (Ledger and persistence)

Prior plans live in git history. Scope: the op-log CRDT in pure Kotlin, Room +
SQLCipher persistence with repositories and incremental fold, Keystore-backed
database key, the convergence simulation, edge cases 20–26, and a migration
test harness built **before** the first schema exists. Stop at the M2 boundary.

---

## 1. Domain: the op-log CRDT (`:core:domain`, pure Kotlin)

New package `app.dibs.core.domain.ledger`:

| Type | Shape |
|---|---|
| `OpId`, `DeviceId` | value classes over strings, `Comparable` |
| `Op` | `id, groupId, lamport: Long, deviceId, parents: Set<OpId>, wallClockMillis: Long, payload` — wall clock is display-only, never ordering (SPEC §6.3 case 22) |
| `OpPayload` | sealed, `@Serializable`: `AddMember`, `RenameMember`, `DeactivateMember`, `AddExpense`, `EditExpense`, `DeleteExpense`, `RestoreExpense`, `RecordSettlement` |
| `ExpenseFields` / `ExpenseEdit` | full data vs. partial (nullable = unchanged) for field-level LWW. Money+payers+split change as **one atomic field** because they are mutually constrained (sum invariant) → DECISIONS.md |
| `OpFactory` | computes `OpId` = BLAKE3 (commons-codec, pure Java) over a **hand-rolled canonical byte encoding** of the whole op (header + payload). Why not kotlinx-JSON: property order is declaration order, which can drift between app versions and silently change every op id. Why header included, not payload alone as SPEC §5.5 loosely words it: two identical edits ("rename back") would collide and vanish under set-union → DECISIONS.md |
| `OpLog` | immutable; `merge(ops)` = set union keyed by id (dedup free, case 23); `heads` (frontier → parents for the next local op); `maxLamport`; splits ops into causally-complete (all ancestors present) vs `pending` (case 24 buffering) |
| `GroupProjector` | **pure left fold**: `state = ops.fold(empty, ::apply)` over the total order `(lamport, deviceId, opId)`. A single-step `apply` is what makes incremental refold in the data layer trivial and correct |
| `GroupState` | members (with rename history), expenses (data, tombstone flag, full edit history with per-op deviceId — cases 20/21), settlements, quarantined ops with typed reasons (case 25), all in id-sorted collections; `canonicalBytes()` for byte-identical convergence assertions |
| `ClockSkew` | pure helper: warn when local wall clock differs > 24 h from newest received op (case 22) |
| `DuplicateMembers` | detection of same-name distinct-id members; merge is a *user action* in M3, never automatic (case 26) |

Fold rules (all deterministic, all replay-order):
- Field-level LWW falls out of folding edits in total order.
- `DeleteExpense` tombstones; edits still fold into data + history (recoverable
  from trash); only `RestoreExpense` clears the tombstone → "delete wins" for
  any concurrent edit (case 21).
- Ops referencing unknown members/expenses, or internally invalid (sums
  mismatch), are **quarantined with a reason**, never dropped, never a crash
  (case 25). Quarantine decisions happen in fold order, so they are identical
  on every device.

## 2. Data: Room + SQLCipher (`:core:data`)

- **Migration harness first** (committed before any schema): Robolectric +
  `MigrationTestHelper`; a guard test that reads `schemas/` and fails if any
  consecutive version pair lacks a migration in `DibsDatabase.ALL_MIGRATIONS`;
  a full walk oldest→newest once ≥2 schemas exist. Plus
  `DatabaseBackupManager`: sidecar version file (SQLCipher encrypts the SQLite
  header, so `user_version` can't be read from the file), copy the db before
  any version change (CLAUDE.md invariant 5), pure-JVM tested.
- Schema v1: single `ops` table (op id PK, group indexed, payload as JSON via
  kotlinx-serialization) + materialized cache tables (`members`, `expenses`,
  `settlements`, `fold_meta`) that mirror `GroupState` for M3's UI queries.
  Ops are the source of truth; cache tables are disposable projections.
- `LedgerRepository`: append local op (assigns lamport = maxSeen+1, parents =
  heads), ingest remote ops (dedup by PK), recompute state — **incrementally**:
  if all newly-applicable ops sort after the last folded position, fold just
  the suffix via `GroupProjector.apply`; otherwise full refold. Emits
  `Flow<GroupState>`. Writes cache tables transactionally.
- Key: `DatabaseKeyProvider` interface — `AndroidKeystoreDatabaseKeyProvider`
  (random 256-bit passphrase wrapped by a Keystore AES-GCM key, stored wrapped
  in app-private storage) for the app; plain provider for tests. SQLCipher via
  `net.zetetic:sqlcipher-android` `SupportOpenHelperFactory`, injected by Hilt.
  Robolectric can't load the SQLCipher native lib or real Keystore, so JVM
  tests run Room in-memory without the factory; the encrypted path is exercised
  on-device in M3 → DECISIONS.md.

## 3. Tests (written first)

Domain (Kotest, JVM):
- **Convergence simulation (the centerpiece):** N simulated devices each with
  their own `OpLog` + lamport counter; random rounds of local ops (add/rename
  members, add/edit/delete/restore expenses, settlements) interleaved with
  gossip that reorders, duplicates, drops, and delays deliveries; one device
  partitioned mid-run and back-filled at the end in shuffled chunks with
  re-deliveries; final anti-entropy pass; assert every device's
  `GroupState.canonicalBytes()` is byte-identical, pending is empty, balances
  agree. ≥500 random scenarios plus directed scenarios (partition,
  concurrent delete+edit both merge orders).
- Named edge tests 20–26: concurrent field edits (both survive; same-field LWW
  by `(lamport, deviceId)`; history shows both devices), delete-vs-edit
  concurrency (delete wins, history intact, restorable), 1970/2099 wall clocks
  (ordering unaffected; skew warning fires), duplicate delivery (no-op),
  out-of-causal-order (buffered as pending, applied when parents arrive),
  unknown-member op (quarantined, no crash), same-name member from two devices
  (two records, merge suggested, never silent).
- Op identity: canonical bytes stable under map/set iteration-order shuffling;
  distinct header → distinct id; same logical op → same id across devices.
- Lamport: monotonic, merge advances, ties broken by deviceId then opId.

Data (Robolectric JUnit4 + pure JVM where possible):
- Migration guard + baseline schema creation via the harness.
- Backup manager: backs up exactly when version changes, keeps prior backup.
- DAO: duplicate insert ignored; ordered queries per group.
- Repository: local append assigns lamport/parents; ingest converges with
  domain fold; incremental suffix-fold equals full refold (property-style over
  random streams); Flow emits updated state (Turbine).

## 4. Order of work

1. PLAN.md (this file) — commit.
2. Migration harness + backup manager + data test scaffolding — commit
   (before any schema exists).
3. Domain ledger tests (red) → implementation → green — commit.
4. Schema v1 + DAOs + repository + key provider, tests green — commit.
5. `./gradlew check`, update PROGRESS.md / DECISIONS.md / CHANGELOG.md — commit.
   Stop at M2 boundary.
