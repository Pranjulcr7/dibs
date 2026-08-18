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
