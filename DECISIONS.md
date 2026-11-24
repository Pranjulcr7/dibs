# DECISIONS

Choices made where SPEC.md was ambiguous, with the reasoning. Per CLAUDE.md,
each picks the option that is simpler for the end user.

## M1 — Domain core

### D1. ISO-4217 exponents come from an embedded table, not `android.icu`

SPEC §5.1 says to read the minor-unit exponent from `android.icu.util.Currency`.
That conflicts with two invariants: `:core:domain` has zero Android imports
(CLAUDE.md #4), and two devices must compute byte-identical state (#6) — platform
ICU data varies by OS version, a compiled-in table does not. The domain therefore
carries an explicit table of non-2 exponents (zero-decimal: JPY, KRW, VND, ISK, …;
three-decimal: KWD, BHD, TND, OMR, JOD, IQD, LYD; four-decimal: CLF, UYW),
defaulting to 2 for anything unknown. `android.icu` remains the *formatter* in the
UI layer, which is what SPEC §5.1's last line actually requires. This is a
reconciliation, not a contradiction, so it lives here rather than BLOCKED.md.

### D2. Percentages are integer basis points

"By percentage" shares are stored as basis points (100.00% = 10 000), matching
the two decimal places users can type, so the sums-to-100% check is exact and no
float ever touches a money path. 33⅓% is not representable — neither Splitwise
nor a text field offers it, and a user wanting thirds picks the equal or shares
mode.

### D3. Adjustment mode semantics

"Equal split plus a per-person delta": deltas (which may be negative) are set
aside first, the remainder is split equally by largest remainder, then each
participant's delta is added back. Deltas exceeding the total, or any final
share below zero, block the save with a typed error. Members named only in the
delta map count as participants.

### D4. Itemized splits with a zero item subtotal

If all line items are zero-priced but tax/tip is nonzero, there is no subtotal to
prorate against, so the tax+tip is divided equally among the item consumers — the
only outcome a user could predict. Mixed-sign items that cancel to a zero
subtotal fall into the same rule once every member's own subtotal is
non-negative; any negative per-member subtotal blocks the save instead.

### D5. Discount line items

Negative-priced items are allowed (SPEC §6.4 case 41) and reduce the subtotal
used for proration. A discount that would push some member's own subtotal below
zero is rejected with a typed error naming the member, because a negative share
on an expense is a refund, and refunds are a distinct transaction type
(SPEC §6.1 case 4).

### D6. Zero-weight participants are kept, at zero

A member with 0% / 0 shares / a zero exact amount stays in the result with a
zero share (and is listed in `zeroShareMembers` for the UI to flag) rather than
being dropped — dropping them would silently change who appears on the expense.
All-zero *shares*, however, block the save (case 10), because "by shares" with
no shares has no meaning. An all-zero *total* (case 3) succeeds with every
member at zero.

### D7. Test naming convention in Kotest

CLAUDE.md specifies backticked `` `given X when Y then Z` `` names, which is
JUnit method syntax. Domain tests use Kotest specs (needed for 10k-iteration
property tests), where test names are strings; the same `given/when/then`
sentence convention is kept in those strings.

### D8. Multiple payers modeled now

`Expense.paidBy` is a map (FR-E3) even though M3's first UI will mostly write
single-payer expenses — retrofitting multi-payer into the ledger later would be
a breaking op-log change, and the balance math is identical either way.

## M2 — Ledger and persistence

### D11. Op ids hash the whole op, not just the payload

SPEC §5.5 words the id as a "BLAKE3 hash of the canonical serialized payload".
Hashed that way, two byte-identical payloads collide — and identical payloads
recur legitimately ("rename it back to A"), so the second op would vanish under
set-union merge and reverts would be impossible. Ids therefore hash the whole
op: group, device, lamport, wall clock, sorted parents, payload.

### D12. Canonical encoding is hand-rolled and versioned, not a serialization library

kotlinx-JSON writes properties in declaration order; a refactor could silently
change every op id and fork every group's history. Op identity comes from an
explicit format (`dibs-op-v1`): fixed field order per payload type,
length-prefixed strings, all maps/sets sorted. JSON is used only as the *storage*
format in Room, where field order doesn't matter. BLAKE3 comes from
commons-codec (pure Java), keeping the domain JVM-pure.

### D13. The money block edits atomically

`EditExpense` treats currency+total+payers+split as one LWW field. They are
mutually constrained (payments must equal shares); letting two devices win
different halves would manufacture an unbalanced expense that nobody entered.
Description, note, and date are independent LWW fields.

### D14. Delete wins by construction, restore is explicit

A tombstone is only cleared by `RestoreExpense`. Concurrent edits still fold
into the fields and history, so restoring from trash returns the expense *with*
those edits (SPEC §6.3 case 21's "preserved in history" made concrete).

### D15. Invalid and unknown-reference ops quarantine instead of crashing or dropping

Ops referencing unknown members/expenses, or internally unbalanced ones, land in
a typed quarantine list that is part of canonical state — identical on every
device — and never count toward balances (case 25). The choice is deterministic
because it is made in replay order.

### D16. Duplicate members are detected, never merged

`DuplicateMembers.candidates` feeds the M3 merge prompt; a merge op does not
exist yet by design — merging destroys information and must be an explicit user
action (case 26).

### D17. Repository lamport/parents live in the domain

`GroupLedger` owns append rules (lamport = max seen + 1, parents = heads);
the data layer and the convergence simulation both use it, so no writer can
drift from the rules that make convergence hold.

### D18. SQLCipher and Keystore are exercised on device, not in Robolectric

Robolectric can load neither the SQLCipher native library nor a real Keystore.
JVM tests run Room in-memory unencrypted with the key-wrapping logic tested
against a fake cipher; `buildEncrypted` (SQLCipher factory + Keystore-wrapped
passphrase + pre-migration backup) is the single production entry point and
gets its on-device verification in M3. The pre-migration backup uses a sidecar
version file because SQLCipher encrypts the SQLite header where `user_version`
would otherwise be readable.

### D19. No classes that collide with Android's boot-classpath commons-codec

Android devices carry an ancient `org.apache.commons.codec` inside
`/system/framework/org.apache.http.legacy.jar`, and on real hardware (verified
on a Galaxy S24 Ultra, Android 16) the boot-classpath `binary.Hex` shadows the
bundled 1.17.x jar — its pre-1.4 API lacks `encodeHexString`, so op-id
computation threw `NoSuchMethodError` on device while every JVM test passed.
Hex encoding is now hand-rolled in `OpFactory` (ids unchanged: lowercase hex is
lowercase hex). `digest.Blake3` stays with commons-codec because the framework
jar has no copy of it to shadow — proven by the same on-device test, which now
guards this whole startup path. Rule going forward: nothing in the app may
depend on commons-codec classes that also exist in the legacy framework jar
(`binary.*`, `digest.DigestUtils`, `language.*`, `net.*`). Since 2026-08-18
this is build-enforced: `verifyNoShadowedCodecImports` (registered by every
convention plugin, wired into `check`) fails the build on any such import,
with the failure message explaining the shadowing.

### D22. Golden vectors freeze the canonical encoding

`GoldenVectorsTest` in `:core:domain` pins the exact canonical encoding string,
the BLAKE3 op id, and a full ledger fold's `canonicalBytes()` against constants
generated once from M2 behavior. Any drift fails with an explicit "this breaks
sync with all previously released versions" message. The constants are the
compatibility contract; they are never regenerated to make a failing build
pass — an intentional format change bumps `dibs-op-v1` and ships a migration.

## M3 — The app

### D20. `:core:ui` added to the module map

CLAUDE.md's diagram doesn't list a shared UI module, but five feature modules
cannot each own the theme, and `:app` may hold nothing but wiring. `:core:ui`
(design system, formatting, shared components) extends the map in the existing
dependency direction: `:feature:* -> :core:ui -> :core:domain`. A recorded
extension, not a silent deviation.

### D21. Typed DataStore via kotlinx-serialization, not protobuf codegen

Same androidx DataStore machinery and atomicity, typed schema, corruption
recovery, unknown-field tolerance — without a second code generator in the
build. CLAUDE.md's stack table says "DataStore (Proto)"; the intent (typed,
transactional preferences) is preserved, the wire format is JSON.

### D23. "Which member is me" is device-local, never in the op-log

The op-log records members; *which one is this device's user* differs per
device, so the mapping lives in local settings (`UserSettings.selfMembers`),
written when this device creates a group. Groups joined via sync (M6) will set
it during the join flow. Without a mapping the UI simply omits "you owe/are
owed" rather than guessing.

### D24. Screen placement not in CLAUDE.md's module list

The Activity tab lives in `:feature:groups` (it is a cross-group ledger feed);
onboarding lives in `:feature:settings` (it writes preferences). Group "delete"
does not exist in 3b — deactivation and archive (3d) are the sanctioned paths,
mirroring members (edge 13). Member merge remains detection-only (D16) until a
merge op exists.

## M3.5 — Identity model (SPEC §5.9)

### D25. One master key, two derived families

A single 256-bit master key derives (a) the Ed25519 identity keypair via
`HKDF(master, "dibs-identity-v1")` and (b) per-group transport keys via
`HKDF(master, "dibs-transport-v1" ‖ groupId)`. Reasoning: the identity key is
the stable human-facing handle across groups, so it must never be visible to a
relay or two groups become linkable; transport keys are what a relay observes,
and deriving them independently per group makes cross-group correlation
impossible by construction. Deriving both from one master key means backup and
recovery reduce to one secret: restore the master key, and the same identity
and all transport keys re-emerge deterministically. HKDF is hand-rolled
(30 lines) and pinned by RFC 5869 test vectors, because a library swap that
changed derivation bytes would orphan every identity.

### D26. Ed25519 via net.i2p.crypto:eddsa

The JDK's Ed25519 arrives in Java 15 and Android 13; minSdk is 26. The eddsa
library is small, pure Java, seed-deterministic (needed for master-key
derivation *and* for golden vectors, since RFC 8032 signing is deterministic),
and shares no package with the boot-classpath commons-codec (D19). Revisit at
M6 if a stronger need (e.g. X25519 agreement) argues for a fuller library.

### D27. Signatures live inside payloads, not the Op header

Adding a signature field to `Op` would change the canonical encoding of every
existing op — exactly what the golden vectors forbid. Signed ops instead carry
key + signature as payload fields, over versioned length-prefixed messages that
include the groupId (a signature can never be replayed into another group).
New payload types are additive to the encoding; old vectors stand.

### D28. Invite expiry compares op-embedded clocks, never a local clock

The fold may only read op contents and replay-order state (invariant 6), so
expiry checks the *claim op's* wall clock against the invite's deadline. A
liar's clock can dodge expiry — expiry is a convenience gate; the security
gates are the signature, the invite's existence, its revocation, and its use
limit, all of which are deterministic and clock-free.

### D29. Join-flow semantics chosen for determinism

Bootstrap: a claim without an invite is valid only while the group has no
claimed members — the creator's self-claim; races resolve by replay order.
One key binds at most one member per group (a second claim quarantines as
KeyAlreadyBound). Concurrent claims of one member: replay-order winner, loser
quarantined. Claim racing a revoke: replay order decides, byte-identically in
both merge orders. `RemoveMember` is quarantined for members with any
financial history (edge 13) — deactivation is the path for them; approval mode
is a group-level LWW toggle (`SetJoinPolicy`) rather than a frozen-encoding
change to CreateGroup.

### D30. Expense dates are civil dates, not instants

`ExpenseFields.dateEpochDay` stores the expense *day* as an epoch-day number.
SPEC edge cases 66/67 assumed instant+zone storage; a civil date is what users
mean by "the dinner on the 14th", cannot shift across timezones or DST, and is
simpler — cases 66/67 are superseded. Op `wallClockMillis` stays display-only.

### D31. Pre-release canonical-format exception

The golden-vector rule ("never regenerate; bump the version and migrate")
protects released users, of which there are none yet. Until the first tagged
release, a canonical-format change may be made ONCE per need by bumping the
format tag, regenerating all vectors in the same commit, and noting it here.
First use: `dibs-op-v2` adding `category`/`receiptRef`/`splitMode` to
`ExpenseFields` (AUDIT T2). Second and final use, same day, still under v2:
`RecordSettlement.kind` (write-offs, edge 14), `CreateGroup.adHoc` (FR-G5),
and `ExpenseEvent.wallClockMillis` in the state rendering (trash age). The
field set is now considered complete; at first release this exception expires
permanently.

## M3 — Screens

### D32. Editing an equal split reopens as equal; every other mode reopens as exact

An equal split is a *policy* ("split it evenly among these people"), so edits
re-run the policy over the new amount. Exact, percent, shares, adjustment and
itemized results are *outcomes* the user already reviewed; reopening them as
their original mode would silently recompute money on an amount change. They
reopen as exact allocations, and the user re-picks a mode deliberately.

### D33. Recurring rules are device-local settings, not ledger ops

A repeat rule creates nothing by itself — the notification opens a prefilled
editor and a human confirms. Since the rule never changes shared state, it
does not belong in the op-log (which would force every device to agree on
scheduling), and it lives in DataStore beside the theme.

## M4 — Import, export, backup

### D34. Splitwise import always creates a new group

Mapping imported members onto an existing group's members is a merge problem
with silent-money-corruption failure modes (wrong person matched → wrong
debts). SPEC doesn't require it, and the workaround is trivial (import, then
keep using the new group). The file+row stableIds make the import idempotent
globally: the same file is refused anywhere, not just in its own group.

### D35. Restore merges with local-wins settings; identity adopted only if absent

Op sets union by content address, so ledger restore is naturally additive.
For settings there is no CRDT, so the local value wins and the backup only
fills gaps (blank display name, unknown self-members). The identity master
key is adopted only when the device has none — overwriting a live identity
would orphan every claim it has signed. Restoring someone else's backup onto
an established device therefore adds their groups but cannot impersonate them.

### D36. "Ledger has changed" for the backup reminder is an op-count comparison

FR-I5 needs "changed since last backup". The log is append-only, so the total
op count moving is exactly equivalent to a change — no dirty flags to keep in
sync, no content hashing, no clock beyond the 30-day staleness check.

## M5 — Receipt scanning (scoped)

### D37. Price detection is two-pass: strict decimals, then integer mode

Prices must print exactly two decimals ("12.34") — the cheapest defense
against quantities and dates becoming money. Zero-decimal currencies never
print cents, so when a receipt yields fewer than two decimal prices the
parser re-reads it in integer mode (grouped "25,000"/"60.000", or bare
integers of ≥3 digits). Inference is per receipt, so decimal receipts can
never regress. Found by the CORD corpus: before this, IDR receipts parsed as
having no prices at all.

### D38. Scanning is an accelerator, not an entry point of its own

Manual entry keeps the one-tap FAB (FR-R8); "Scan receipt" lives in the group
overflow menu and every scan failure routes to gallery or manual entry. The
reviewed result is handed to the same itemized editor manual entry uses —
one assignment UI, not two.

### D39. Receipt images are named by parse fingerprint

The stored photo's filename is BLAKE3 over the normalized parsed content
(labels, amounts, totals). That one choice gives edge 47 for free: scanning
the same paper twice produces the same fingerprint, so "already added?" is a
prefix check over `receiptRef`, with no image hashing and no new op fields.

## Quality pass — performance

### D40. Cold-open state snapshots are a cache with a row-count validity gate

Opening a 5,000-op group cold cost ~1.2 s on release (op-row load + JSON
decode + full fold), against NFR-3's 100 ms. Fix: `state_snapshots` (schema
v2) stores one serialized `GroupState` per group plus the fold checkpoint,
heads, and max lamport. Three load-bearing choices:

- **The op log stays the only source of truth.** A snapshot is validated by
  `opRowCount == COUNT(ops)` — sound because ops are append-only, so an equal
  count proves an identical op *set* — plus a codec version; any mismatch or
  parse failure silently refolds. Deleting the table is always safe.
- **Reads never load the op log.** The cache keeps `log = null` after a
  restore; the first *write* loads it (`withLogLocked`), re-verifies the
  checkpoint, and chains lamport/parents from the persisted heads. UI-only
  sessions never pay the op decode at all.
- **The codec is lossless and versioned**, proven by a roundtrip test whose
  fixture covers every optional branch (claims, invites, quarantine, trash,
  rename history); `GroupStateCodec.VERSION` bumps on any shape change so old
  snapshots refold instead of misparsing.

Measured on the S24 Ultra with the 5,007-op group: cold open 1,222 ms → 98–102
ms (release), 2,632 ms → ~450 ms (debug).

## M6 — Sync tier 1

### D41. Manual-first transport; signed-and-sealed bundles; group keys live in the encrypted database

The "relay decision" resolves the way the SPEC's tiers already lean: **v1
ships manual sync only** (FR-Y1) — encrypted bundles through the share sheet,
QR/`dibs://` invites — and every relay question (FR-Y2/Y3) moves wholesale to
M8. No transport code may assume a server exists.

- **`SyncTransport` stays the SPEC §5.6 shape** with `capability = MANUAL`;
  the UI branches on capability, never on class. `ManualFileTransport.push`
  succeeds without doing anything (sharing is an explicit user act) and
  `pull` returns nothing (bundles arrive through the import flow); the
  interface earns its keep the day a relay implements `BEST_EFFORT`.
- **Bundle envelope** (`dibs-sync-v1`): a JSON header (format version, group,
  sender device + identity key, sender vector clock, op count) is the AEAD
  associated data for an XChaCha20-Poly1305 body keyed by the group key, and
  an Ed25519 signature by the sender's identity key covers BLAKE3(header ‖
  ciphertext) — AUDIT T1's "bundles are signed by the sender". Tampering
  anywhere fails wholesale (edge 27). Each op's payload is serialized as its
  own JSON string inside the body, so an unknown op type from a newer minor
  version skips with a count instead of sinking the bundle (edge 28); a major
  version mismatch is a hard, specific error.
- **Group keys** are 256-bit `SecureRandom`, created lazily on first invite,
  stored in a table of the SQLCipher database (whose passphrase is already
  Keystore-wrapped — same at-rest protection as everything else), carried in
  encrypted backups, and adopted from join links. Adopting a *different* key
  for a group that already has one is a loud error, never a silent overwrite.
- **The sender's vector clock rides in the header**, so a receiver can say
  "some changes may be missing" (edge 75/FR-Y4) by comparing it against the
  local log after ingest — no relay arbitration, no wall clocks.

### D42. An invite is one file: the link in front of the group's data

An invite link carries the group's key, not its contents. A joiner who has
only the link sees an empty group until someone separately sends an update
file — two hand-offs in the right order, which is exactly the kind of thing
that has to be explained. "Send invite" therefore shares a single
`.dibsinvite` file (`InviteFile`): the link, then a sealed sync bundle.
Opening it joins in one step with the expenses already there. The QR code
still carries the link alone, because a bundle does not fit in one; that path
is for people standing together, and the join screen then asks for the
updates. The link is in the clear inside the file exactly as it would be in a
chat message, which the invite dialog says in plain words.

### D43. The joiner checks the invite before writing; the fold still decides

`JoinCheck` mirrors the fold's invite rules (unknown, revoked, expired, used
up, already a member) on the joiner's phone so the join screen can explain a
problem and append nothing — a quarantined claim is permanent clutter in a
shared append-only log. It is never more permissive than the fold (a test
pins this), and after appending a claim the join flow re-reads folded state
to confirm it applied, because another phone's claim can still win the race.

### D44. Edge 74 as built: bundle senders are authenticated, not devices

SPEC edge 74 described a signed `AnnounceDevice` op binding a device id to an
identity key. That op was never built and is not needed: what D41 ships is a
bundle signed by the sender's identity key, accepted only if that key belongs
to a claimed, non-removed member in the state *after* a dry-run fold of the
bundle's own ops. `Op.deviceId` stays unauthenticated metadata used only for
ordering ties. SPEC edge 74 now says this.

### D45. Edge 29: large bundles are bounded, not streamed

A bundle is one XChaCha20-Poly1305 message and cannot be authenticated until
all of it has been read, so "stream-parse" would need a chunked AEAD format —
a major version bump. Instead, files opened from outside the app are read off
the main thread with a hard 24 MB cap (a 5,000-expense group seals to a few
MB), with a progress state, and anything larger is refused. Revisit only if
real groups approach the cap.

### D46. The group key is adopted when the link is opened

Opening an invite link stores the group's key before any data has proved the
link genuine. The alternative — holding the key in memory until a bundle
decrypts under it — would lose the join if the app is killed while the person
is waiting for the updates file. The cost: someone who already knows a group's
id (a member or a past invitee) could send a link with a wrong key and block
that phone from joining that group ("doesn't match a group already on this
phone"). They already hold the real key, so nothing is exposed; the block is
loud, not silent, and never overwrites a key.

### D47. QR scanning uses zxing, in both flavors

Invite codes are decoded from CameraX analysis frames by zxing (Apache-2.0,
pure Java) in `:feature:scan`. ML Kit barcode scanning would have made QR
joining unavailable in the foss flavor.

### D48. A name is asked for at the first invite, not at first launch

Onboarding stays skippable (SPEC §4.1), so the creator's member can be the
placeholder "You". That is fine alone and wrong the moment the group is
shared — everyone else would see a member called "You". Creating an invite
with no display name set asks for one and renames the member.

### D49. A phone that is not a member is told so

After a race for a single-use invite, the loser's phone holds a quarantined
claim the others never receive (its bundles are refused as coming from a
non-member). The devices have different op sets, so invariant 6 is not in
play, but the loser must not be left adding expenses nobody will get: the
group header says the phone is not a member and to ask for a new invite. The
same notice covers a removed member.

### D50. docs/requirements.yaml replaces AUDIT.md's tables

Every FR, NFR, edge case and flow has one entry with a status and the tests
that cover it; `verifyTraceability` (in `check`) fails when an id is missing
or a named test no longer exists. The file is a fixed-key YAML subset read by
a 40-line parser, so the build gains no dependency. AUDIT.md is kept as a
dated snapshot.

### D51. End-to-end checks are black-box, over adb

ROADMAP P1 planned instrumented Compose tests. What was built instead is
`scripts/e2e`: a driver that reads the screen with `uiautomator` and acts
with `input`, run against the installed debug APK on two emulators. It knows
nothing about the app's internals, exercises the real encrypted database,
intent filters and file provider, and can move a file from one phone to the
other — which an instrumented test inside one app process cannot. It is
slower than Compose tests and is not in CI yet.

## M0 — Foundation

### D9. compileSdk/targetSdk 36

"Latest stable" per CLAUDE.md at the time of M0. Raised deliberately, not by
dependabot.

### D10. Purity check scope

`verifyPureJvm` (registered by the `dibs.kotlin.jvm` convention plugin, wired
into `check`) fails on: resolved *or merely requested* artifacts from
`androidx.*` / `com.android*` / `com.google.android*` / `com.google.firebase*` /
`com.google.mlkit*` / `org.robolectric*` on the compile, runtime, or test
classpaths; any project dependency (`:core:domain` must sit at the graph's
bottom); and any `android.`/`androidx.` source import. "Requested" matters
because an Android AAR fails variant matching on a JVM module before it ever
resolves, which would otherwise dodge the resolved-artifact check.
