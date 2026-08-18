# Dibs — Specification

Version 1.0. Android. Companion to `CLAUDE.md` (invariants) and `MILESTONES.md`
(build order).

---

## 1. Product definition

**One-liner.** Split expenses with friends. Everything stays on your phone.

**Positioning.** Splitwise without the account, the ads, the expense caps, or the
server. Same mental model, same vocabulary, so a migrating user needs no
re-learning.

**Design principles, in priority order:**

1. **Logging an expense is the whole product.** It must take under five seconds
   and at most three taps from app launch. Everything else is secondary.
2. **Never show a number the user can't verify.** Every balance is drillable to
   the expenses that produced it.
3. **Degrade, don't fail.** No camera, no OCR model, no network, no other
   members online — the app still works. Manual entry is always available and
   never buried.
4. **No jargon in the UI.** The words "CRDT", "relay", "keypair", "quantized",
   "op-log", "public key" appear nowhere a user can see them. Internally: "sync
   code", "backup file", "device".

---

## 2. Functional requirements

### 2.1 Groups

- FR-G1 Create a group with a name, optional emoji/color, and a default currency.
- FR-G2 Add members by name only. No email, no phone, no invite required. A
  member is a local record until (optionally) claimed by a real device.
- FR-G3 Edit and remove members. Removal is blocked if the member appears in any
  expense; offer "deactivate" instead, which hides them from new-expense pickers
  but preserves history.
- FR-G4 Archive a group (hidden from the main list, still readable). Delete a
  group with a typed confirmation and an automatic pre-delete backup.
- FR-G5 A one-off split with no group ("just this once") creates a hidden
  ad-hoc group so it uses the same code path.
- FR-G6 Group list shows, per group, the current user's net position: "you are
  owed $42.50" / "you owe $12.00" / "settled up".

### 2.2 Expenses

- FR-E1 Record: amount, currency, description, date, payer, split mode,
  participants, optional category, optional note, optional receipt image.
- FR-E2 Split modes:
  - **Equally** among selected participants (default).
  - **By exact amounts** — must sum to the total; live-updating "$X left to
    assign" indicator.
  - **By percentage** — must sum to 100%.
  - **By shares** — integer weights, e.g. 2 shares to a couple, 1 to a single.
  - **By adjustment** — equal split plus a per-person delta (Splitwise's "+/-").
  - **Itemized** — line items assigned to people, with tax and tip distributed
    proportionally to each person's item subtotal.
- FR-E3 Multiple payers on one expense (three people chipped in for the Airbnb).
- FR-E4 Edit any expense; full edit history is retained and viewable.
- FR-E5 Delete an expense (tombstone, recoverable from a 30-day trash).
- FR-E6 Duplicate an expense.
- FR-E7 Recurring expenses (monthly rent, weekly cleaner) with a local
  notification prompting confirmation. Never auto-create silently.
- FR-E8 Attach a receipt photo, stored in app-private storage, never uploaded.
- FR-E9 Search and filter expenses by text, member, category, date range, amount.

### 2.3 Balances and settlement

- FR-S1 Show raw pairwise balances (who owes whom, directly).
- FR-S2 Show simplified settlement (minimum transfers), as a **toggle**, defaulting
  to OFF. Simplification is surprising to first-time users — "why am I paying
  Dave, I never bought anything from Dave?" — so it must be opt-in and
  accompanied by a one-line explanation.
- FR-S3 Record a settlement payment as its own transaction type.
- FR-S4 Partial settlements.
- FR-S5 Per-member detail: total paid, total owed, net, and the expense list
  behind each.
- FR-S6 "Remind" produces a shareable plain-text summary (share sheet), not a
  message sent by Dibs.

### 2.4 Receipt scanning

- FR-R1 Capture via CameraX with an on-screen guide frame and a stability hint.
  Also accept an existing image from the gallery.
- FR-R2 Parse to structured line items: name, quantity, unit price, line total,
  plus subtotal, tax, tip, total.
- FR-R3 **Mandatory review screen.** Parsed output is never committed without the
  user seeing it. This is a financial app; silent AI output is unacceptable.
- FR-R4 Confidence surfacing: any field the parser is unsure about, or that
  fails the arithmetic check, is visually flagged and focused first.
- FR-R5 Assign line items to people by tapping avatars. Support splitting one
  item across several people (fractional consumption).
- FR-R6 Tax and tip distributed proportionally by default; manual override
  available; equal-split-of-tax option.
- FR-R7 Multi-frame capture for long receipts, with overlap de-duplication.
- FR-R8 Fully manual line-item entry as a first-class path, not a fallback.

### 2.5 Import and export

- FR-I1 **Splitwise CSV import.** The per-group export has the header
  `Date,Description,Category,Cost,Currency,<Member1>,<Member2>,...` with one row
  per expense and each member column holding that person's signed net share.
  Import must:
  - Parse the header to discover member names dynamically.
  - Map discovered names to existing or new Dibs members via a mapping screen.
  - Reconstruct payer and split from the signed columns: the positive column(s)
    are the payer's net credit; negatives are debtors' shares.
  - Detect and convert "Total balance" / settlement rows rather than importing
    them as expenses.
  - Show a **preview** with member list, expense count, per-currency totals, and
    a list of skipped rows with reasons, before writing anything.
  - Be idempotent — re-importing the same file must not duplicate.
- FR-I2 CSV export of any group.
- FR-I3 Full encrypted backup: entire op-log + identity, single `.dibs` file,
  written via the Storage Access Framework so the user picks the destination
  (local, SD card, Drive, Nextcloud — Dibs never talks to any of them directly).
- FR-I4 Restore from backup on a new device, including merge-into-existing rather
  than replace-all.
- FR-I5 Backup reminder if the last backup is older than 30 days and the ledger
  has changed.

### 2.6 Sync

- FR-Y1 **Tier 1 (ships in v1): manual.** Join a group by scanning a QR code or
  pasting a `dibs://` link. Share updates as an encrypted bundle through the
  Android share sheet — any channel the user already has works.
- FR-Y2 **Tier 2 (v1.1): relay.** Opt-in, per-group. Encrypted deltas pushed to
  user-configurable public relays. Off by default, with plain-language
  explanation on the opt-in screen.
- FR-Y3 **Tier 3: bring your own relay.** A URL field in settings.
- FR-Y4 A visible per-group sync state: "Up to date", "3 changes to share",
  "Last synced 2 days ago". Never a silent failure.
- FR-Y5 Nothing about sync may block expense entry.

### 2.7 Settings and support

- FR-T1 Theme (system/light/dark), dynamic color, language.
- FR-T2 Default currency, default split mode, default group.
- FR-T3 App lock (biometric / device credential).
- FR-T4 Manage receipt-parser tier, including downloading and deleting the
  optional VLM, with a clear size figure shown before download.
- FR-T5 Storage usage breakdown and a "clear receipt images" action.
- FR-T6 About: version, license, source link, contributor credits, and an
  explicit "Dibs collects nothing" statement.

---

## 3. Non-functional requirements

| ID | Requirement | Target |
|---|---|---|
| NFR-1 | Cold start to interactive group list | < 800 ms on a mid-range 2022 device |
| NFR-2 | Add-expense flow, launch to saved | < 5 s, ≤ 3 taps for the equal-split default |
| NFR-3 | Balance recomputation, 5,000-expense group | < 100 ms |
| NFR-4 | ML Kit OCR pass | < 1.5 s |
| NFR-5 | Optional VLM parse | < 8 s with visible progress and a cancel button |
| NFR-6 | Base APK size, no optional model | < 20 MB |
| NFR-7 | Frame rate on all scroll surfaces | 60 fps, no jank on 1,000-item lists |
| NFR-8 | Offline capability | 100% of features except relay sync |
| NFR-9 | Domain-layer test coverage | > 90% line, 100% of split and settlement math |
| NFR-10 | Accessibility | TalkBack labels everywhere, 4.5:1 contrast, 200% font without clipping, all touch targets ≥ 48 dp |
| NFR-11 | Data at rest | SQLCipher, key in Android Keystore, `allowBackup=false` |
| NFR-12 | Battery | No foreground services, no wakelocks, no periodic polling when sync is off |
| NFR-13 | Localization | Every string externalized; RTL layout correct; en ships first |
| NFR-14 | Reproducible build | Same commit produces same APK hash |

---

## 4. UI requirements

### 4.1 Structure

Bottom navigation, three destinations: **Groups**, **Activity**, **Settings**.
A prominent FAB for "Add expense" on Groups and Group Detail.

Screens:

1. **Groups** — list of group cards, each with name, member avatars, and the
   user's net position color-coded (green = owed, red = owe, gray = settled).
   Empty state offers "Create a group" and "Import from Splitwise" side by side.
2. **Group detail** — tabs: Expenses / Balances / Settle up. Header shows the
   user's net position and a sync-state chip.
3. **Add / edit expense** — amount keypad first and focused. Description, payer,
   and split mode below. Split mode is a horizontally scrollable chip row, not a
   dropdown. A camera button opens the scan flow.
4. **Split editor** — per-mode. Always shows a live "remaining to assign" bar
   that turns green at exactly zero.
5. **Scan review** — receipt thumbnail on the left, editable line items on the
   right. Flagged fields highlighted. Big "Looks right" confirm button.
6. **Item assignment** — line items with member avatar toggles. Long-press an
   item to split it fractionally.
7. **Balances** — sorted list, "you" pinned first, with the simplify toggle.
8. **Settle up** — pick two people and an amount, or accept a suggested transfer.
9. **Import** — file pick, name mapping, preview, confirm.
10. **Settings** — grouped list.
11. **Onboarding** — three screens maximum: what Dibs is, "your data is on this
    phone, back it up", enter your name. Skippable. No account, no permissions
    requested up front.

### 4.2 Visual and interaction rules

- Material 3, dynamic color on Android 12+, a warm default palette otherwise.
- The amount is the largest text on any screen that shows one. Tabular figures.
- Currency always displayed with an explicit symbol and, in multi-currency
  groups, the code as well.
- Destructive actions: confirm dialog, and an undo snackbar where reversible.
- Every list has a designed empty state with a single clear action.
- Every async operation has a loading state, a success state, and a **specific**
  error state. No generic "Something went wrong".
- Optimistic UI: writes land locally and render immediately. There is nothing to
  wait for.
- Haptic feedback on expense save and on settle-up confirm.
- No modal blocking spinners longer than 500 ms; use skeletons.

---

## 5. Technical design

### 5.1 Money

```kotlin
@JvmInline value class Money(val minorUnits: Long)   // paired with a Currency
```

- All arithmetic in `Long`. Division returns `(quotient, remainder)`.
- Minor-unit exponent read from `android.icu.util.Currency`, not assumed to be 2.
  JPY and KRW are 0; BHD, KWD, TND are 3. A hardcoded `* 100` is a bug.
- Display formatting is the only place a locale-aware formatter appears.

### 5.2 Split algorithms and remainder handling

The core correctness problem: $10.00 split three ways is $3.3333…. Someone must
absorb the extra cent.

**Rule: largest remainder method with a deterministic tiebreak.**

1. Compute each participant's exact share as a rational `(numerator, denominator)`.
2. Floor each to minor units.
3. Distribute the leftover minor units one at a time to participants ordered by
   descending fractional remainder; ties broken by ascending member UUID.

This guarantees the sum is exact, the distribution is fair, and — critically —
**every device computes the same answer**, which the UUID tiebreak provides and
a naive "give it to the first person" does not.

The UI must show who absorbed the extra cent, subtly ("Alice +$0.01"), because
otherwise the numbers look wrong to anyone checking by hand.

### 5.3 Itemized split math

For participant `i` with fractional consumption `f(i,j)` of item `j` priced `p(j)`:

```
subtotal(i) = Σ_j  p(j) × f(i,j)
share(i)    = subtotal(i) + (tax + tip) × subtotal(i) / Σ_j p(j)
```

Rounding applied once, at the end, via largest-remainder against the true total.
Never round intermediate values — the errors compound.

### 5.4 Settlement

Net balance per member: `paid − owed`, summing to zero by construction.

Simplification is a greedy max-debtor / max-creditor pairing using two priority
queues, terminating in at most `n−1` transfers. This is not the provably minimal
number of transfers — that problem is NP-hard — and the code comment must say so
plainly rather than claiming optimality.

Determinism requirement: seed both queues sorted by `(amount desc, memberId asc)`
so every device produces the identical transfer list. Two friends seeing
different suggested payments would destroy trust in the app.

### 5.5 The ledger: op-log CRDT

**Do not pull in Automerge with JNI bindings for v1.** The domain doesn't need
general-purpose JSON merging — it needs an append-only log of small, mostly
immutable financial facts. Build this in pure Kotlin:

```kotlin
data class Op(
  val id: OpId,            // BLAKE3 hash of the canonical serialized payload
  val groupId: GroupId,
  val lamport: Long,
  val deviceId: DeviceId,
  val parents: Set<OpId>,  // causal predecessors
  val payload: OpPayload   // sealed: AddExpense, EditExpense, DeleteExpense,
                           // AddMember, Settle, ...
)
```

- Merge = set union of ops. Content-addressed IDs make this idempotent for free.
- Total order for replay: `(lamport asc, deviceId asc, opId asc)`. Never wall
  clock — device clocks are wrong and users travel across time zones.
- Field-level last-writer-wins for edits, using that same total order.
- Deletes are tombstones. Nothing is ever physically removed from the log, which
  makes "restore from trash" trivial.
- State is a fold over the ordered log, cached in Room tables, invalidated and
  recomputed incrementally on new ops.

This is roughly 400 lines of Kotlin, fully unit-testable on the JVM, with no
native dependency. Automerge remains an option later if genuinely needed; the
`Ledger` interface should not leak the implementation.

### 5.6 Sync transport

```kotlin
interface SyncTransport {
    suspend fun push(groupId: GroupId, ops: List<Op>): Result<Unit>
    suspend fun pull(groupId: GroupId, since: VectorClock): Result<List<Op>>
    val capability: TransportCapability   // MANUAL, BEST_EFFORT, REALTIME
}
```

Implementations: `ManualFileTransport` (v1), `RelayTransport` (v1.1),
`NoOpTransport` (tests). The UI reads `capability` to decide what sync affordances
to show; it never branches on a concrete class.

### 5.7 Cryptography

- Per-group symmetric key, 256-bit, generated on group creation.
- Group key travels only inside the QR invite / deep link, never over a relay.
- Payload encryption: XChaCha20-Poly1305 (AEAD). Nonces from `SecureRandom`,
  never reused.
- Backup files: key derived from the user's passphrase via Argon2id
  (or scrypt if Argon2id proves painful on old devices), then the same AEAD.
- Keys at rest in the Android Keystore, hardware-backed where available, with a
  documented software fallback for devices lacking a TEE.
- Threat model to write down in `SECURITY.md`: Dibs protects against a relay
  operator and against someone who steals the backup file. It does **not**
  protect against a compromised device or a malicious group member.

### 5.8 Receipt parsing pipeline

```kotlin
interface ReceiptParser {
    suspend fun parse(image: Bitmap): ParseResult   // items + confidences
    val tier: ParserTier
}
```

Default `MlKitReceiptParser`: ML Kit text recognition produces text blocks with
bounding boxes; a deterministic Kotlin layout parser groups them into rows by
y-overlap, identifies the price column by right-alignment and numeric pattern,
and extracts quantity prefixes. This is boring, fast, free, and debuggable — and
because it's rule-based, its failures are inspectable rather than mysterious.

Optional `VlmReceiptParser`: MediaPipe LLM Inference with a downloaded model,
prompted for strict JSON, output parsed against a schema.

**Arithmetic gate, applied to both.** If
`|Σ items + tax + tip − total| > 0.02`, the result is marked unverified and the
review screen highlights the mismatch. Never silently accept parser output that
doesn't add up.

Fall back down the tiers on failure. Manual entry is the floor and always
reachable in one tap from the review screen.

---

## 6. Edge cases and required handling

Each of these needs a test. Grouped by area.

### 6.1 Money and arithmetic

| # | Case | Required behavior |
|---|---|---|
| 1 | $10 split 3 ways | 3.34 / 3.33 / 3.33, largest-remainder, deterministic recipient, shown in UI |
| 2 | $0.01 split 5 ways | One person gets the cent, four get zero. Warn that some shares are zero |
| 3 | Zero-amount expense | Allowed (a $0 placeholder is a legitimate note), but flagged |
| 4 | Negative amount | Rejected at input. Refunds are a distinct transaction type |
| 5 | Very large amount | Support up to `Long.MAX_VALUE` minor units; validate against overflow on sum |
| 6 | Zero-decimal currency (JPY) | No decimal separator, no fractional cents |
| 7 | Three-decimal currency (KWD) | Correct exponent from ICU |
| 8 | Percentage split summing to 99.99% | Block save; show exact shortfall |
| 9 | Exact-amount split summing to $9.99 of $10 | Block save; offer "add $0.01 to <person>" one-tap fix |
| 10 | All shares set to 0 | Block save with a clear message |
| 11 | Repeated float accumulation | Impossible by construction; property test asserts sum invariance over 10,000 random splits |

### 6.2 Groups and members

| # | Case | Required behavior |
|---|---|---|
| 12 | Two members with the same name | Allowed; disambiguate in UI with a color/initial badge; never merge automatically |
| 13 | Removing a member with history | Block hard delete; offer deactivate |
| 14 | Removing a member with a non-zero balance | Block; require settle-up first, with a "write off" escape hatch that records an explicit adjustment |
| 15 | Member added after expenses exist | New member has no retroactive share; explicitly stated in UI |
| 16 | Group with one member | Allowed (personal expense tracking); hide settlement UI |
| 17 | Group with 100 members | Must not degrade; virtualize pickers; warn above 50 |
| 18 | Deleting the only group | Return to empty state, not a crash |
| 19 | Renaming a member | Propagates everywhere; history retains the rename event |

### 6.3 Concurrency and sync

| # | Case | Required behavior |
|---|---|---|
| 20 | Two devices edit the same expense offline | LWW per field by `(lamport, deviceId)`; both edits visible in history; a "this was edited on another device" note |
| 21 | One device deletes, another edits | Delete wins; the edit is preserved in history and recoverable from trash |
| 22 | Device clock set to 1970 or 2099 | Lamport clocks used for ordering; wall clock only for display; a skew warning if > 24 h from the newest received op |
| 23 | Duplicate op received | Content-addressed IDs make it a no-op |
| 24 | Ops arrive out of causal order | Buffer until parents present; surface as "syncing" not as missing data |
| 25 | Op referencing an unknown member | Quarantine, don't crash; show "incomplete data, waiting for more" |
| 26 | Same person joins a group twice on two devices | Two member records; offer a merge action; never merge silently |
| 27 | Corrupted or tampered sync bundle | Fail AEAD auth, reject wholesale, show a specific error. Never partially apply |
| 28 | Bundle from a different app version | Version field in the envelope; forward-compatible unknown-op skipping with a warning; hard fail on major version mismatch |
| 29 | Massive bundle (10 MB) | Stream-parse, progress indicator, cancellable |
| 30 | Relay unreachable | Queue locally with WorkManager exponential backoff; UI shows pending count; never blocks |
| 31 | Relay purges old events | Peer-to-peer full-snapshot handoff on join; snapshot also recoverable from any member |
| 32 | Two people settle the same debt simultaneously | Both settlements recorded; resulting overpayment shown as a credit with a "possible duplicate settlement" hint |

### 6.4 Receipt scanning

| # | Case | Required behavior |
|---|---|---|
| 33 | Camera permission denied | Gallery and manual entry remain available; no dead end |
| 34 | Blurry / dark / glare image | Pre-capture quality hint; post-capture "try again" with specific advice |
| 35 | Long receipt exceeding the frame | Multi-frame capture with overlap detection and de-duplication of boundary items |
| 36 | Creased or torn receipt | Best effort; unparsed regions flagged for manual entry |
| 37 | Handwritten receipt | Expected to fail; detect low confidence and route straight to manual entry |
| 38 | Non-English receipt | ML Kit Latin script v1; explicitly document unsupported scripts rather than producing garbage |
| 39 | Items sum ≠ printed total | Arithmetic gate flags it; user resolves before commit |
| 40 | OCR hallucinates a digit (8→3) | Gate catches sum mismatches; independent per-field confidence surfaced; review is mandatory regardless |
| 41 | Negative line items (discounts, coupons) | Parse and support as negative items; they reduce the subtotal used for tax proration |
| 42 | Tip written in by hand after printing | Manual tip field always editable on the review screen |
| 43 | Receipt with no line items (total only) | Degrade gracefully to a single-amount expense |
| 44 | Model download interrupted | Resumable; verify checksum; delete partial file |
| 45 | Device with insufficient storage or RAM for the VLM | Detect before download; refuse with an explanation; ML Kit tier keeps working |
| 46 | OCR takes too long | 10 s hard timeout, cancellable, falls back to manual |
| 47 | Same receipt scanned twice | Detect a near-identical hash and ask "did you mean to add this again?" |

### 6.5 Import and export

| # | Case | Required behavior |
|---|---|---|
| 48 | Splitwise CSV in a non-English locale | Header names differ; detect and tell the user to re-export in English rather than silently mis-mapping |
| 49 | CSV with a comma decimal separator | Detect locale from the file; parse accordingly |
| 50 | CSV member names not matching Dibs members | Mapping screen; unmapped names become new members |
| 51 | CSV containing settlement rows | Detect via description heuristics and the balance-row pattern; import as settlements |
| 52 | CSV with a "Total balance" trailer row | Skip; use it as a checksum to verify the import and warn on mismatch |
| 53 | Multi-currency CSV | Group per-currency; never sum across currencies |
| 54 | Malformed / partial CSV | Preview shows exactly which rows will be skipped and why; user confirms before any write |
| 55 | Re-importing the same file | Idempotent via a content hash of each row plus the file |
| 56 | Very large CSV (5,000 rows) | Streamed, progress shown, cancellable, transactional |
| 57 | Restoring a backup onto a device that already has data | Offer merge or replace; default to merge; always snapshot first |
| 58 | Wrong passphrase on a backup | Specific error, no partial write, no lockout |
| 59 | Backup file corrupted | AEAD detects it; refuse cleanly |
| 60 | User loses the backup passphrase | Unrecoverable by design. Say so loudly at backup creation time |

### 6.6 Platform and lifecycle

| # | Case | Required behavior |
|---|---|---|
| 61 | Process death mid-expense-entry | Draft persisted via `SavedStateHandle` and restored |
| 62 | Configuration change / rotation | State survives; no reload |
| 63 | Storage full on save | Specific error, retry, offer to clear receipt images |
| 64 | Database migration failure | Auto-restore the pre-migration backup, report clearly |
| 65 | App killed during import | Import is transactional; either fully applied or not at all |
| 66 | Time zone change mid-trip | Dates stored as UTC instants plus the originating zone; displayed in the user's current zone with the original date preserved for the expense day |
| 67 | DST transition | Instant-based storage makes this a non-issue; test it anyway |
| 68 | System font at 200% | No clipping on any screen |
| 69 | Landscape and tablet | Responsive layout; no letterboxing |
| 70 | Battery saver / Doze | WorkManager only; never a foreground service |
| 71 | Android 8 minimum SDK | All APIs guarded; no crash on the floor version |

### 6.7 Security and abuse

| # | Case | Required behavior |
|---|---|---|
| 72 | Stolen phone | App lock (biometric); SQLCipher at rest |
| 73 | Screenshot of financial data | `FLAG_SECURE` as an opt-in setting |
| 74 | Malicious group member forging ops | Ops are signed by device key; unsigned or invalid-signature ops rejected. Document that Dibs cannot stop a legitimate member from entering false expenses — that's a social problem |
| 75 | Malicious relay dropping or replaying ops | Replay is idempotent; dropping is detected via a vector clock gap and shown as "some changes may be missing" |
| 76 | Deep link with a crafted payload | Strict parsing, size caps, no reflection, fuzz-tested |
| 77 | Backup file shared publicly | Encrypted at rest; passphrase strength meter enforced at creation |

---

## 7. Model track (parallel, not blocking)

Kept in a separate `model/` directory and a separate CI job. The app must ship
without it.

**Datasets.** SROIE (MIT, ~1,000 images, Malaysian scanned receipts) as the
license-safe base. CORD (CC BY-SA 4.0, ~1,000 Indonesian receipts, richer
field-level labels) with share-alike terms respected. Do **not** commit raw
images to the repo — ship download scripts only. FUNSD and several other
document sets are non-commercial-academic and are out of bounds here.

**Honest expectations.** ~1,800 mostly-scanned, mostly-Southeast-Asian receipts
will not produce a model that reliably reads a crumpled US restaurant receipt
photographed in a dim bar. Published SmolVLM fine-tunes on SROIE report character
error rates around 0.31. Treat the VLM as an experiment, and hold ML Kit as the
default path until a held-out set of real photos says otherwise.

**Pipeline.** Download → normalize to a common JSON schema → augment (rotation,
perspective, brightness, JPEG artifacts, synthetic creasing) → LoRA fine-tune a
small VLM → evaluate CER and field-level F1 → quantize to INT4 → export → measure
real latency and RAM on a physical device.

**Evaluation gate.** The VLM tier only ships if, on a held-out set of at least
100 real photographed receipts, it beats the ML Kit rule-based parser on
line-item F1. Otherwise it stays behind a developer flag. Build this eval set
early; it is the single most valuable artifact in the model track.

---

## 8. Definition of done

A milestone is done when: tests pass, `./gradlew check` is clean, the feature
works in airplane mode, TalkBack can navigate it, it renders correctly at 200%
font in light and dark, `PROGRESS.md` is updated, and no invariant in `CLAUDE.md`
was violated.
