# Dibs — Build order

Sequential. Do not begin a milestone until the previous one's exit criteria are
met. Update `PROGRESS.md` after each.

The ordering principle: **the app should be genuinely useful to one person on one
phone by the end of M3.** Sync and AI are enhancements layered on a product that
already works, not prerequisites. This ordering also means that if the project
stalls at any point after M3, what exists is still a shippable app.

---

## M0 — Foundation

Gradle multi-module skeleton per the architecture in `CLAUDE.md`. Version
catalogs, convention plugins, Hilt, ktlint, detekt, JUnit5, GitHub Actions CI
running `./gradlew check` plus an assemble on every PR. Empty Compose app that
launches to a placeholder.

**Exit:** CI green on a fresh clone. Module dependency rules enforced by a build
check, not just convention.

## M1 — Domain core

Pure Kotlin, zero Android. `Money`, `Currency` handling with correct minor-unit
exponents, all six split algorithms, largest-remainder allocation, itemized math
with proportional tax and tip, net-balance computation, greedy settlement
simplification.

**Exit:** > 90% coverage. Property-based tests proving (a) splits always sum
exactly to the total across 10,000 random inputs, (b) settlement always
terminates in ≤ n−1 transfers, (c) net balances always sum to zero, (d) the same
input produces the same output on repeated runs. Edge cases 1–11 from SPEC §6.1
each have a named test. **No emulator required to run any of this.**

## M2 — Ledger and persistence

The op-log CRDT in pure Kotlin. Room + SQLCipher schema, repositories, the fold
from op-log to materialized state with incremental invalidation. Keystore-backed
database key.

**Exit:** Edge cases 20–26 tested. A simulation test that generates random
interleaved op streams across simulated devices and asserts all devices converge
to identical state. Migration test harness in place before any schema exists.

## M3 — The app

Every screen in SPEC §4.1. Full CRUD on groups, members, expenses. All split
modes. Balances and settle-up. Search and filter. Recurring expenses. Trash.
Onboarding. Settings. Empty states, error states, undo.

At this point Dibs is a complete, useful, single-device expense splitter.

**Exit:** A person can run a real weekend trip through it end to end. Edge cases
12–19 and 61–71 tested. TalkBack pass, 200% font pass, dark mode pass, tablet
pass on every screen.

## M4 — Import, export, backup

Splitwise CSV import with the mapping and preview flow. CSV export. Encrypted
`.dibs` backup and restore via SAF. Backup reminders.

Build this before sync — it is the migration path that gets users in the door,
and it is also the safety net that makes every later milestone less risky.

**Exit:** Edge cases 48–60 tested. Round-trip test: real Splitwise export in,
Dibs backup out, restore on a clean install, balances identical.

## M5 — Receipt scanning

CameraX capture with guide frame and quality hints. `ReceiptParser` interface.
`MlKitReceiptParser` with the rule-based layout parser. The arithmetic gate. The
mandatory review screen. Item assignment with fractional consumption.
Multi-frame capture. Manual line-item entry.

**Exit:** Edge cases 33–47 tested. Accuracy measured against 30 real receipts you
photograph yourself — record the number in `PROGRESS.md` whatever it is.

## M6 — Sync tier 1

`SyncTransport` interface. `ManualFileTransport`. QR invite generation and
scanning, `dibs://` deep links, per-group keys, XChaCha20-Poly1305 bundles,
share-sheet export and import. Sync-state chips. Vector-clock gap detection.

**Exit:** Edge cases 27–32 and 72–77 tested. Two physical devices sync a group
end to end with no server involved. `SECURITY.md` written.

## M7 — Polish and release

Performance pass against every NFR target with real measurements recorded.
Baseline profiles. R8 config. Play Store listing, screenshots, privacy policy
(short, because there is nothing to disclose). F-Droid metadata. Signing setup
with the keystore documented and never committed.

**Exit:** All NFRs measured, not estimated. Release build installs and runs
clean on a physical device.

## M8 and beyond — optional tiers

- Relay transport (SPEC §2.6 tier 2), opt-in, per group.
- `VlmReceiptParser` with on-demand model download, gated on the SPEC §7
  evaluation criterion.
- Widgets, Wear, quick-settings tile.
- iOS, sharing the domain design.

---

## Parallel track — model

Runs in `model/` independently. Never blocks the app. Follow SPEC §7. The first
deliverable is not a trained model — it is the **held-out evaluation set of 100+
real photographed receipts**. Build that first; without it there is no way to
know whether any model is worth shipping.
