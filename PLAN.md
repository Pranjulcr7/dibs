# PLAN — M3 (The app), staged 3a → 3d

Prior plans in git history. Each stage ends with `./gradlew check` green, an
install on the connected device, and a stop for review.

## Architecture decisions that shape all four stages

- **Features depend on `:core:domain` only** (CLAUDE.md dependency diagram), so
  every capability a screen needs is an *interface in the domain*, implemented
  in `:core:data`, bound by Hilt in `:app`. SPEC §5.5 already demands this for
  `Ledger`; settings get the same treatment (`SettingsStore`).
- **New module `:core:ui`** (design system: theme, tokens, shared components,
  money/date formatting). The CLAUDE.md module map doesn't list it — this is a
  recorded extension, not a silent deviation (DECISIONS.md D20): five feature
  modules cannot each own a copy of the theme, and `:app` may contain nothing
  but wiring. Direction stays clean: `:feature:* -> :core:ui -> :core:domain`.
- **Typed DataStore via kotlinx-serialization**, not protobuf codegen
  (DECISIONS.md D21): same androidx DataStore machinery and atomicity, typed
  schema, one less compiler toolchain.
- The **Activity tab** lives in `:feature:groups` (it is a cross-group ledger
  feed); **onboarding** lives in `:feature:settings` (it writes preferences).
- Money is **formatted only via `android.icu`** in `:core:ui`
  (`MoneyFormatter`); the minor-units→decimal move for display uses the
  domain's own exponent table, so display can never disagree with arithmetic.

## Stage 3a — shell, theme, tokens, onboarding, empty states

Build:
- `:core:ui`: full warm M3 palette (light/dark) + dynamic color on 12+,
  `MoneyColors` (owed green / owe red / settled neutral) via CompositionLocal,
  spacing tokens, amount typography with tabular figures, `MoneyFormatter`
  (android.icu), `EmptyState` component, previews (light/dark/200% font).
- `:core:domain`: `UserSettings` + `SettingsStore` interface (Flow-based).
- `:core:data`: DataStore-backed `SettingsStore` implementation + Hilt binding.
- `:feature:settings`: 3-page onboarding (what Dibs is / your data stays on
  this phone, back it up / enter your name), skippable, no permissions asked;
  Settings tab placeholder (real settings in 3d).
- `:feature:groups`: Groups tab with designed empty state ("Create a group" +
  "Import from Splitwise" side by side; import shows a "coming soon" note until
  M4 — DECISIONS.md), Activity tab empty state.
- `:app`: single-activity nav shell — onboarding gate, bottom nav
  (Groups / Activity / Settings), FAB on Groups; theme driven by settings.

Tests first: DataStore round-trip + defaults; OnboardingViewModel (fake store,
Turbine); Robolectric Compose tests — onboarding pager flow (skip and
name-entry paths hit the store), Groups empty state shows both actions;
MoneyFormatter for USD/JPY/KWD incl. tabular rendering path; start-destination
logic. Robolectric runs Compose UI tests on the JVM; no emulator.

## Stage 3b — groups and members

`Ledger` interface extracted to domain (append/observe/state/pending);
`CreateGroup`/group metadata ops added to the op-log (new payloads: group name,
emoji/color, default currency — schema-compatible, quarantine-safe). Screens:
group list (net position per group, color-coded), create group, group detail
scaffold (tabs: Expenses / Balances / Settle up), member management
(add/rename/deactivate with edge-13 blocking), duplicate-name badge (edge 12),
merge prompt fed by `DuplicateMembers` (edge 26). Room projection queries feed
list screens. Tests: ViewModels over fake Ledger; projector tests for new
payloads; Compose tests for list/create/member flows.

## Stage 3c — expense entry and the six split editors

Add/edit expense screen (amount keypad focused first, ≤3 taps for equal split),
split-mode chip row, per-mode editors with the live "remaining to assign" bar
(green at exactly zero), multi-payer, edit history view, duplicate expense.
Every editor validates through `SplitCalculator` — the UI never re-implements
math (edges 8/9/10 surfaced with one-tap fixes; edge 1 "who absorbed the cent"
shown). Draft survival via `SavedStateHandle` (edge 61). Tests: ViewModel state
machines per mode, remaining-indicator invariants (property-checked against
SplitCalculator), Compose tests per editor.

## Stage 3d — balances, settle up, search/filter, recurring, trash, settings

Balances tab (raw pairwise + simplify toggle, off by default, one-line
explanation — FR-S2), settle-up flow with suggested transfers, partial
settlements, member detail (paid/owed/net + drill-down), search & filter,
recurring expenses (WorkManager local notification, confirm-first — FR-E7),
30-day trash with restore, settings (theme, dynamic color, default currency,
app lock hook, about + "Dibs collects nothing"). Edge cases 14–18, 61–63, 66–67
get tests here; TalkBack/200%/dark/tablet passes across all screens close M3.

## Stage gates

Each stage: tests written first; `./gradlew check` green; `:app:installDebug`
on the connected device + launch; PROGRESS.md updated; stop for review.
