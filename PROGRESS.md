# PROGRESS

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
