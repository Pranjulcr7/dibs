# Issue drafts

Drafts to file as GitHub issues once the repository is public. Each entry is a
title plus the body to paste. Labels are in brackets.

---

## Good first issues

### 1. Add a Kotest edge-case test for three-way JPY splits with a tip [good first issue] [tests]

SPEC §6.1 covers zero-decimal currencies, but we have no named test for an
itemized JPY expense with a proportional tip. Add one to
`:core:domain` following the `` `given X when Y then Z` `` naming. A good
first exposure to the money invariants — the split must sum exactly with no
fractional yen.

### 2. Show the currency code next to the symbol when it is ambiguous [good first issue] [ui]

`$` could be USD, CAD, AUD, MXN. When a group's expenses span currencies whose
symbols collide, `MoneyFormatter` should fall back to `formatWithCode`. The
formatter lives in `:core:ui`; add a unit test with USD + CAD in one list.

### 3. Empty state for the Activity tab could suggest a next step [good first issue] [ui]

The Activity feed's empty state says there is no activity but offers nothing to
do. Add a button that jumps to expense entry (mirror how the Groups empty state
offers "Create a group"). Strings go in `strings.xml`.

### 4. Trash screen: show how many days remain before auto-hide [good first issue] [ui]

Trashed expenses hide after 30 days, but the row doesn't say when. Compute the
remaining days from the deletion timestamp already exposed by `TrashViewModel`
and show "hides in N days". Watch the singular/plural.

### 5. Landscape layout for the scan capture screen [good first issue] [ui]

Receipts are tall; phones held sideways get a squashed preview. Give the
capture screen a landscape variant where the camera preview and the buttons
sit side by side. CameraX handles rotation already — this is pure Compose
layout.

### 6. Group emoji picker instead of a free-text field [good first issue] [ui]

Creating/editing a group takes the emoji as free text. A small curated grid
(travel, home, food, party…) would be friendlier. Keep free text as a fallback;
the op payload already handles any string.

### 7. Settlement history rows: relative dates [good first issue] [ui]

Settle-up history shows absolute dates. "3 days ago" reads better for recent
entries. Use `android.icu.text.RelativeDateTimeFormatter` (never hand-roll) and
fall back to the absolute date past 7 days.

### 8. Add a "copy group summary" action [good first issue] [ui]

A share/copy action that renders current balances as plain text ("Alice owes
Bob $12.50 …") for pasting into a chat. Pure read of `GroupState.balances()`
plus the system share sheet.

### 9. Currency chips in Settings: full list behind a search field [good first issue] [ui]

The default-currency picker shows 15 hardcoded codes. The domain's ISO-4217
table knows them all; add a searchable list dialog for the long tail while
keeping the popular chips.

### 10. Backup passphrase dialog: show/hide toggle [good first issue] [ui]

The passphrase fields are always masked. Add the standard visibility toggle
(eye icon) to both fields in `BackupDialogs.kt`, with a content description for
TalkBack.

### 11. Reject absurd dates in the expense editor [good first issue] [ui]

The date picker allows dates decades in the future. Constrain selectable dates
to a sane window (say, 10 years back to 1 year ahead) via the DatePicker's
`selectableDates`, with a test for the boundary.

### 12. Kover report: raise `:core:domain` line-coverage gate from 90% to 92% [good first issue] [build]

Current coverage is ~98%; the gate at 90% leaves slack for regressions. Ratchet
carefully — bump the threshold and make sure `./gradlew check` still passes.

### 13. Add a test for `PairwiseBalances` with a settled-then-reopened pair [good first issue] [tests]

We property-test that pairwise nets equal member nets, but there is no named
test for: A owes B, B records payment, then a new expense reopens the debt.
Add it to `:core:domain` with exact amounts.

### 14. Import preview: humanize the skipped-row reasons [good first issue] [ui]

Skipped rows show parser-internal reasons ("unparseable cost"). Map them to
friendlier strings ("The amount in this row isn't a number") in the UI layer —
the parser's reasons should stay machine-stable.

### 15. Document the golden-vector workflow in CONTRIBUTING [good first issue] [docs]

Contributors who touch op encodings hit the golden-vector failure message and
need context. Add a short CONTRIBUTING section explaining why the vectors
exist, why they must never be regenerated casually, and what a version bump
involves.

---

### 26. ~~Expense editor: disabled-Save hint and amount auto-focus~~ — resolved before filing

Both landed: a new expense opens with the amount focused (3 taps for the
equal-split default, NFR-2 met) and a ready split with a blank description
says "Add a description to save it" under the remaining bar.

## Help wanted

### 16. Translations: extract and verify string completeness [help wanted] [i18n]

All user-facing text lives in `strings.xml` by convention. Before accepting
translations we need a lint check that no feature module hardcodes text, plus a
first non-English locale (help especially welcome for RTL languages —
Arabic/Hebrew layouts are untested).

### 17. Property-test the Splitwise importer against generated exports [help wanted] [tests]

The importer is tested against handcrafted files. Generate random ledgers,
render them in Splitwise's CSV shape, import, and assert balances match to the
cent — including multi-currency groups and deleted members (columns that sum
to zero).

### 18. Fuzz the backup codec [help wanted] [security] [tests]

`BackupCodec.decrypt` must return null (never crash, never partially apply) for
arbitrary input. Wire a simple fuzzer (even Kotest generators over mutations of
a valid file) and run millions of iterations. Findings would be security bugs.

### 19. ~~Rehydrate group state on cold open~~ — resolved before filing

Fixed by the `state_snapshots` cache (DECISIONS.md D40): cold-opening the
5,007-op group went from 1,222 ms to 98–102 ms on the release build. What
remains open of the original idea is only the guard rail: a Macrobenchmark
module so a regression here fails CI instead of a user's cold start. That
part is still welcome — see #12/#19 history in git for context.

### 20. Screen-reader pass by a daily TalkBack user [help wanted] [accessibility]

We test with TalkBack but none of us uses it daily. A structured pass over the
main flows (create group → add expense → settle up → back up) by someone who
relies on a screen reader would surface real issues automated checks cannot.

### 21. SQLCipher → Android Keystore key rotation [help wanted] [security]

The database key is generated once and wrapped forever. Design and implement
rotation (new key, re-encrypt, atomically swap, crash-safe at every step) with
a migration-style test. Needs care around the pre-migration backup invariant.

### 22. Receipt-layout corpus: annotated photos of *photographed* receipts [help wanted] [ocr]

Public datasets (SROIE, CORD) are scans of Southeast Asian receipts; our users
photograph crumpled US/EU receipts at an angle. We need a small, freely-licensed
corpus of receipt *photos* with line-item boxes to evaluate the parser. Photos
must contain no personal data.

### 23. F-Droid build flavor without ML Kit [help wanted] [build]

ML Kit is a closed-source binary, which F-Droid rejects. We need a `foss`
flavor where the scan tier is compiled out (`NoOpReceiptParser` only), a
reproducible build, and the metadata for an F-Droid submission.

### 24. Wear OS / widget quick-add exploration [help wanted] [design]

"Add expense" is the highest-frequency action. A home-screen widget (and maybe
a Wear tile) that opens the prefilled editor would cut the friction. Design
exploration first — must not add background network or battery cost.

### 25. Threat-model review of the identity and invite design [help wanted] [security]

SPEC §5.9 documents the key hierarchy, signed membership ops, and invite flow.
We would value an adversarial review by someone with applied-crypto experience:
invite link leakage, member-merge edge cases, multi-device implications, replay
across groups. Findings via SECURITY.md, please.
