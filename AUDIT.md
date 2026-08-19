# AUDIT — code ↔ documents reconciliation

Date: 2026-08-18. Baseline: commit `339e67d` (identity model landed). 217 tests
green, `./gradlew check` clean. Shipped so far: M0, M1, M2, M3a, M3b, and the
SPEC §5.9 identity model (ops + hooks; UI in M6).

---

## 1. Drift found, and what was done about it

**Documents updated to match code (code is right):**

| Where | Drift | Resolution |
|---|---|---|
| CLAUDE.md module map | No `:core:ui`; no `:app → :core:data` edge | Updated diagram: `:core:ui` design system (D20); `:app` is the DI composition root and links `:core:data` to bind domain interfaces |
| CLAUDE.md stack table | "DataStore (Proto)" | Updated to "DataStore (typed via kotlinx-serialization)" (D21) |
| SPEC §5.1 | "exponent read from `android.icu.util.Currency`" | Updated: exponent from the domain's embedded ISO-4217 table (D1); `android.icu` formats display only |
| SPEC §5.5 | Op id = "BLAKE3 of the canonical serialized **payload**" | Updated: hash covers the whole op (D11 — payload-only collides on repeated identical edits) |
| SPEC §6.3 case 74 | "signed by device key" — written before §5.9 existed | Updated to reference §5.9 identity keys and the M6 binding design (see task T1) |
| SPEC §7 | VLM presented as a co-equal track | Updated: VLM deferred until a real photographed eval set exists; ML Kit + rules is the shipping path (per M5 scope decision) |
| MILESTONES M6 | Lists invite/key work already landed | Annotated: identity ops, handles, canonical encodings and their tests pre-landed at M3.5; M6 is transport + join UI |
| SPEC §6.6 cases 66/67 | Assume instant-based expense timestamps | Superseded: expenses store a **civil date** (`dateEpochDay`) — the "expense day" has no timezone or DST behavior at all (new D30). Op `wallClockMillis` remains display-only metadata |

**Tasks opened (code is wrong / missing — tracked in §6):** T1–T8.

## 2. Requirement status

Statuses: **done** / **partial** / **not started** / **superseded**. "Domain
ready" = math/ops exist and are tested, UI does not.

### Functional requirements

| Req | Status | Notes |
|---|---|---|
| FR-G1 create group | partial | Name, emoji, currency done; **color** in payload but no picker UI |
| FR-G2 members by name | done | Local records; claimable per §5.9 |
| FR-G3 edit/remove members | partial | Rename/deactivate/reactivate done; RemoveMember op validated in fold; no removal UI (M6 notification context) |
| FR-G4 archive/delete group | not started | Task T5 → 3d |
| FR-G5 ad-hoc "just once" group | not started | Task T6 → 3d |
| FR-G6 net position on cards | done | Color-coded, default currency |
| FR-E1 record expense | partial | Domain ready incl. multi-payer; **no category/receipt fields in ExpenseFields** — T2, must fix before 3c ships; editor UI is 3c |
| FR-E2 six split modes | partial | Domain complete + property-proven; editors are 3c |
| FR-E3 multiple payers | partial | Ledger + balances support it; editor UI deferred past 3c (single payer first) |
| FR-E4 edit + history | partial | Fold keeps full history w/ device attribution; viewer UI is 3c |
| FR-E5 delete → trash | partial | Tombstone + restore ops done; trash UI is 3d; 30-day purge policy undefined (purge is display-side only — log is append-only) |
| FR-E6 duplicate expense | not started | 3c |
| FR-E7 recurring | not started | 3d; rule will be an additive shared payload |
| FR-E8 receipt photo | not started | M5; needs receiptRef in fields — folded into T2 |
| FR-E9 search/filter | not started | 3d |
| FR-S1 pairwise balances | partial | Net per member done; **pairwise** (who-owes-whom directly) needs a domain addition — 3d |
| FR-S2 simplify toggle | partial | Greedy settlement domain-done; toggle UI 3d |
| FR-S3/S4 settlements, partial | partial | Op + fold + balances done; UI 3d |
| FR-S5 member detail | not started | 3d |
| FR-S6 remind text | not started | 3d |
| FR-R1–R8 scanning | not started | M5 (scoped: no VLM) |
| FR-I1–I5 import/export/backup | not started | M4; backup **must** include the identity master key (D25) |
| FR-Y1–Y5 sync | not started | M6; §5.9 groundwork landed (ops, link codec, transport keys) |
| FR-T1 theme/dynamic/language | partial | Theme plumbing works end to end; no settings UI; language = system only |
| FR-T2 defaults | partial | defaultCurrency stored + used at create; no UI; default split/group missing |
| FR-T3 app lock | not started | 3d |
| FR-T4 parser tier mgmt | superseded (scoped) | VLM deferred; becomes "manage scanning" only |
| FR-T5 storage usage | not started | 3d/M5 |
| FR-T6 about | partial | Privacy statement shown; version/license/credits missing |

### Non-functional requirements

None measured yet — all targets are unverified except NFR-9. Measurement on
the physical device is scheduled in the quality pass; no estimates will be
recorded.

| NFR | Status |
|---|---|
| 1 cold start < 800ms | not measured |
| 2 add-expense ≤ 3 taps < 5s | not started (3c) |
| 3 5,000-expense fold < 100ms | not measured; fold copies sorted maps per op — likely needs work (T7) |
| 4/5 OCR latencies | not started (M5) |
| 6 APK < 20MB | not measured |
| 7 60fps lists | not measured |
| 8 offline 100% | done by construction — **no INTERNET permission exists in any manifest** |
| 9 domain coverage > 90% | done, build-enforced (Kover, currently ~97% line) |
| 10 accessibility | partial: 48dp/contrast designed in; no TalkBack/font pass yet (quality pass) |
| 11 SQLCipher + Keystore + allowBackup=false | done, verified on device |
| 12 no services/wakelocks/polling | done by construction (nothing scheduled) |
| 13 strings externalized, RTL | partial: all strings in resources; RTL untested |
| 14 reproducible build | not started (M7) |

### Edge cases 1–77

| # | Status | Notes |
|---|---|---|
| 1–11 money/arithmetic | **done** | Named tests + 10k-iteration properties (M1) |
| 12 same-name members | done | Badge + deterministic avatar colors + hint |
| 13 remove w/ history | done | Deactivate offered in UI; RemoveMember quarantines `RemovalBlocked` in fold |
| 14 remove w/ balance | partial | Blocked (any history ⊇ nonzero balance); **"write off" escape hatch missing** — T4 → 3d |
| 15 late member no retroactive share | partial | True by construction; not yet stated in UI (3c empty-share row will state it) |
| 16 single-member group | partial | Works; settlement-UI hiding lands with 3d |
| 17 100-member group | not started | Virtualized pickers + warning → 3d |
| 18 delete only group | not started | With T5 (3d) |
| 19 rename propagates | done | History retained, fold-tested |
| 20–26 concurrency | **done** | M2: LWW, delete-wins, lamport ordering, dedup, causal buffering, quarantine, dup-members; convergence sim |
| 27–32 sync bundles/relay | not started | M6 |
| 33–47 scanning | not started | M5 |
| 48–60 import/backup | not started | M4 |
| 61 process death drafts | partial | Onboarding/tabs use rememberSaveable; expense draft via SavedStateHandle is a 3c requirement |
| 62 rotation | partial | State hoisted + saveable, no systematic test yet (3d) |
| 63 storage full | not started | 3d |
| 64 migration failure restore | partial | Pre-migration backup taken; **auto-restore on failure missing** → M4 (with backup infra) |
| 65 kill during import | not started | M4 (transactional import) |
| 66 timezone change | **superseded** | Civil-date storage (D30) — no instant to shift |
| 67 DST | **superseded** | Same |
| 68 200% font | partial | Previews exist per screen; device pass in quality pass |
| 69 landscape/tablet | not started | Quality pass |
| 70 Doze/battery | done by construction | Nothing runs in background yet; recurring (3d) must use WorkManager |
| 71 API 26 floor | partial | minSdk 26 set; untested on a 26 device/emulator |
| 72 stolen phone | partial | Encryption at rest verified on device; app lock → 3d |
| 73 FLAG_SECURE | not started | 3d |
| 74 forged ops | partial | §5.9 join/identity ops signed + verified in fold; **financial ops unsigned** — T1 (M6 design, see §4) |
| 75 relay drop/replay | not started | M6 (replay already idempotent via content addressing) |
| 76 crafted deep link | partial | Strict invite-link parser w/ caps + tests; fuzzing not done (M6) |
| 77 public backup file | not started | M4 (passphrase strength meter) |

## 3. Obsolete / contradictory early specifications

1. **Edge 74 vs §5.9 (the big one).** "Ops are signed by device key" was
   written before the identity model. §5.9 signs *membership* ops with the
   identity key. Financial ops (expenses, settlements) are unsigned, and
   `Op.deviceId` is unauthenticated. Per-op signing cannot be added to
   existing payloads (encodings frozen). The coherent M6 design is: (a) a
   signed `AnnounceDevice` op binding deviceId → identity key, and (b)
   signing sync *bundles* with the sender's identity key. That authenticates
   authorship of everything in transit without touching op encodings. SPEC
   updated to say so; implementation is T1, **must land inside M6 before any
   op crosses devices**.
2. **FR-G2/FR-Y1 "no invite required" vs §5.9 invites.** Not actually a
   contradiction — members-as-names need no invite; invites exist only for a
   *device* to claim membership. SPEC wording untouched; noted here.
3. **Sync assumptions in §5.7.** "Group key travels only inside the QR
   invite" matches the `k=` param of the §5.9 link. Consistent. But §5.7's
   "per-group symmetric key" and §5.9's per-group *transport* keys are
   different objects serving different layers (content encryption vs relay
   identity); M6 documentation must define both precisely or merge them —
   folded into T1's design note.
4. **DECISIONS D16 ("merge op does not exist") still true** — and now
   *harder*: merging two members where one is claimed must reconcile identity
   keys. Recorded as an explicit M6+ design constraint (T8).
5. **FR-T4 (VLM management UI)** — scoped out with the VLM deferral; only
   "clear receipt data" remains meaningful (→ FR-T5).
6. **Cases 66/67** — superseded by civil-date storage (D30).

## 4. Painful-if-deferred, ranked

1. **ExpenseFields lacks `category` and `receiptRef` (T2).** The AddExpense
   canonical encoding freezes the field set. Adding them post-release means a
   format version bump plus migration for every user. **We are pre-release**:
   this is the last cheap moment. Action: extend `ExpenseFields`, bump the
   canonical format tag to `dibs-op-v2`, regenerate all golden vectors under
   an explicit, documented pre-release exception (D31), wipe dev-device data.
   Doing this *first thing in 3c*.
2. **Financial-op authentication design (T1).** Not needed until M6, but the
   *decision* (bundle signing + AnnounceDevice) constrains the M6 wire format;
   deferring the design past M4 risks backup format rework too. Design note
   written (§3.1); implementation M6.
3. **Backup must carry the master key (part of M4).** If M4 shipped a backup
   without it, every restored user would lose their identity. Locked into the
   M4 plan now.
4. **NFR-3 fold performance (T7).** Per-op TreeMap copying is O(n) per op →
   O(n²) per full refold. At 5,000 expenses this will likely miss 100ms.
   Incremental folding hides it day-to-day, but import (M4) does full folds.
   Measure during M4 with a 5k synthetic group; optimize then (builder-style
   fold), since the fix is internal to `GroupProjector` and non-breaking.
5. **F-Droid FOSS-OCR flavor.** ML Kit lands in M5. The `ReceiptParser`
   interface isolates it; the *build flavor* decision can wait, but every M5
   dependency must stay inside `:core:ocr` for the swap to stay cheap.
6. **Multi-payer editor.** Domain fully supports it; UI deferral is safe.
7. **Group archive/delete, ad-hoc groups (T5/T6).** Archive is an additive
   payload; cheap whenever. Delete is device-local. Low risk.

## 5. Task list

| # | Task | When |
|---|---|---|
| T1 | M6 authentication: `AnnounceDevice` binding op + signed sync bundles; unify §5.7 group key vs §5.9 transport keys in SPEC | M6 (design note done) |
| T2 | Add `category` + `receiptRef` to `ExpenseFields`; bump canonical format to v2; regenerate goldens under documented pre-release exception (D31) | **3c, first** |
| T3 | Members entry discoverability (unlabeled icon) + one-member-group CTA | Quality pass |
| T4 | Write-off adjustment (edge 14 escape hatch) | 3d |
| T5 | Archive (additive op) + local group delete with pre-delete backup | 3d |
| T6 | Ad-hoc "just this once" hidden group | 3d |
| T7 | Measure NFR-3 with 5k-expense synthetic group; optimize projector if over budget | M4 |
| T8 | Member-merge design must reconcile claimed identities | M6+ |

## 6. Milestone verdicts

- **M0/M1/M2: done** as documented; exit criteria met with evidence.
- **M3: in progress** — 3a/3b shipped; 3c/3d remain, plus M3 exit criteria
  (TalkBack/200%/tablet passes) land in the quality pass.
- **M3.5 identity model: done** (ops, crypto, goldens, races, hooks); its UI
  belongs to M6 by design.
- **M4/M5: not started**; M5 rescoped (no VLM). **M6/M7: blocked by design**
  (external inputs), except T1's design note.
