# PLAN — M0 + M1

Scope: M0 (foundation) and M1 (domain core) only. Stop at the M1 boundary.

---

## 1. Module structure

```
dibs/
├── build-logic/                 # included build with convention plugins
│   └── convention/
├── gradle/libs.versions.toml    # single version catalog
├── app/                         # navigation host + DI wiring, Compose placeholder
├── core/
│   ├── domain/                  # PURE Kotlin/JVM. No Android. M1 lives here.
│   ├── data/                    # Android library, empty in M0 (Room/SQLCipher in M2)
│   ├── ocr/                     # Android library, empty in M0 (ReceiptParser in M5)
│   └── sync/                    # Android library, empty in M0 (SyncTransport in M6)
└── feature/
    ├── groups/                  # Android libraries, empty in M0 (screens in M3)
    ├── expense/
    ├── settle/
    ├── scan/
    └── settings/
```

Dependency direction enforced as in CLAUDE.md: `:app -> :feature:* -> :core:domain`,
`:core:data|ocr|sync -> :core:domain`. `:core:domain` depends on nothing.

## 2. Gradle setup approach

- **Gradle 8.14.x wrapper**, JDK 17 toolchain, Kotlin 2.2.x, AGP 8.11.x.
- **Version catalog** (`gradle/libs.versions.toml`) is the only place versions appear.
- **Convention plugins** in `build-logic/convention` (an included build):
  - `dibs.kotlin.jvm` — pure JVM modules: Kotlin JVM, JUnit5 platform, explicit
    JDK 17, and the **domain purity check** (below).
  - `dibs.android.library` — AGP library defaults (compileSdk 36, minSdk 26),
    Kotlin Android, lint.
  - `dibs.android.application` — same for `:app`.
  - `dibs.android.compose` — Compose compiler + BOM wiring.
  - `dibs.hilt` — Hilt + KSP.
  - `dibs.quality` — ktlint + detekt, applied to every module from the root.
- **Domain purity check** — a `verifyPureJvm` task registered by `dibs.kotlin.jvm`
  and wired into `check`. It fails the build if:
  1. any resolved compile/runtime dependency has a group matching
     `androidx.*`, `com.android.*`, `com.google.android.*`, or `com.google.firebase.*`;
  2. the module declares any `project(...)` dependency (`:core:domain` must sit at
     the bottom of the graph);
  3. any source file imports `android.` or `androidx.`.
  Because `:core:domain` is a plain Kotlin JVM module, applying any Android plugin
  there is itself impossible without also switching plugins — condition (1)+(3)
  catch the realistic regressions (a transitive Android artifact or import).
- **CI** (`.github/workflows/ci.yml`): on every PR and push to main — checkout,
  Temurin JDK 17, Gradle action with caching, `./gradlew check` then
  `./gradlew :app:assembleDebug`. Runs on PRs from forks. A `release.yml` builds
  on tags and attaches the APK.
- Open-source scaffolding per OPEN_SOURCE_SETUP.md §3: LICENSE (Apache-2.0),
  README, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, ARCHITECTURE, PRIVACY,
  CHANGELOG, issue/PR templates, dependabot, .gitignore, .editorconfig. SPDX
  header in every source file.

## 3. Domain types (M1, package `app.dibs.core.domain`)

| Type | Shape | Notes |
|---|---|---|
| `Money` | `@JvmInline value class Money(val minorUnits: Long)` | All arithmetic overflow-checked (`Math.addExact`). No `Double` anywhere. |
| `CurrencyCode` | value class over the ISO-4217 alpha code | |
| `Currencies` | object with the ISO-4217 **minor-unit exponent table** | Embedded pure-Kotlin table (default 2; JPY/KRW 0; KWD/BHD/TND 3, …). Not `android.icu` — the domain is pure JVM and exponents must be identical on every device regardless of platform ICU version. Formatting (display) still uses `android.icu` in the app layer. → DECISIONS.md |
| `MemberId`, `ExpenseId`, `GroupId` | value classes over canonical UUID strings, `Comparable` | The stable sort key for every deterministic tiebreak. |
| `SplitSpec` | sealed: `Equal`, `ByExactAmounts`, `ByPercentage`, `ByShares`, `ByAdjustment`, `Itemized` | Percentages are integer **basis points** summing to 10 000 — no floats. → DECISIONS.md |
| `LineItem` | price + per-member integer consumption weights | Fractional consumption ("2 of us shared the nachos") = weights within the item. |
| `Allocation` | member, amount, `roundingAdjustment` | Result rows sorted by `MemberId`; the +1-cent absorber is visible for the UI. |
| `SplitResult` | sealed: `Success` / typed failures | Failures carry exact shortfall (edge cases 8, 9, 10). |
| `Expense` | total, currency, `paidBy: Map<MemberId, Money>` (multi-payer), `owedBy` | Payments must sum to total. |
| `SettlementPayment` | from, to, amount, currency | FR-S3. |
| `Transfer` | from, to, amount | Output of settlement simplification. |

Algorithms:

- **`LargestRemainder.allocate(total, weights)`** — exact shares as rationals via
  `BigInteger` (no overflow at `Long.MAX_VALUE`), floor, then distribute leftover
  units by descending fractional remainder, ties by ascending member UUID.
  Every split mode funnels through this one function.
- **Itemized math** — exact rational per-member subtotals; tax + tip prorated by
  subtotal; **one** rounding at the end via largest-remainder against the true
  total (items + tax + tip). Never rounds intermediates.
- **`Balances.net(expenses, settlements)`** — per-currency `paid − owed`, sums to
  zero by construction; never sums across currencies.
- **`Settlement.simplify(balances)`** — greedy max-debtor/max-creditor pairing,
  seeded sorted by `(amount desc, memberId asc)`, ≤ n−1 transfers. The code
  comment states plainly this is not provably minimal (that problem is NP-hard).

## 4. Tests (written first; JUnit5 + Kotest property; ≥10 000 iterations each)

Property tests:

1. **Sum invariance** — for every split mode, over random totals (0…10^12),
   currencies (incl. 0- and 3-decimal), and 1…20 members: allocations sum
   exactly to the total. (Edge 11.)
2. **Largest-remainder bounds** — every allocation differs from the exact
   rational share by less than one minor unit; leftover distribution count ≤ n−1.
3. **Net balances sum to zero** — random multi-payer expenses + partial
   settlements, per currency.
4. **Settlement terminates in ≤ n−1 transfers**, transfers are all positive,
   and applying them zeroes every balance.
5. **Determinism / byte-identical output** — for each algorithm: run twice, and
   run with the input collections shuffled; canonical serialization of the result
   is byte-identical.
6. **Overflow safety** — sums near `Long.MAX_VALUE` either succeed exactly or
   fail with a typed overflow error, never wrap. (Edge 5.)

Named edge-case tests (SPEC §6.1, one each): 1 ($10/3 → 3.34/3.33/3.33 with
deterministic recipient), 2 ($0.01/5 → one cent, four zeros, zero-share flag),
3 (zero-amount allowed), 4 (negative rejected), 5 (Long.MAX_VALUE, overflow on
sum), 6 (JPY: no fractional units, ¥1000/3 → 334/333/333), 7 (KWD exponent 3
from the table; BHD too), 8 (99.99% blocks with exact shortfall), 9 ($9.99 of
$10 blocks with remaining $0.01), 10 (all shares zero blocks), 11 (the sum-
invariance property above).

Plus unit tests: UUID tiebreak order, itemized proportional tax/tip vs
hand-computed fixtures, discount line item reduces proration base, KRW zero-
decimal, adjustment mode, multi-payer validation, settlement fixtures.

## 5. Order of work

1. M0: build-logic + catalog + `:core:domain` compiling with purity check green.
2. M0: Android modules, placeholder Compose app, quality plugins, CI, OSS files.
   Commit per unit. `./gradlew check` green.
3. M1: write the test suite (red), then implement until green. Three-strike rule:
   any property failure I can't fix in three attempts goes to BLOCKED.md.
4. `./gradlew check`, PROGRESS.md, DECISIONS.md. Stop.
