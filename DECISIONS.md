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
