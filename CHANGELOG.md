# Changelog

All notable changes to Dibs will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Multi-module Gradle foundation with convention plugins, CI, and a build check
  that keeps the domain core free of Android dependencies (M0).
- Domain core: `Money` (integer minor units, correct ISO-4217 exponents), six
  split modes with largest-remainder allocation, itemized math with proportional
  tax and tip, net balances, and deterministic greedy settlement (M1).
- Append-only op-log ledger: content-addressed ops, causal buffering,
  deterministic fold with field-level last-writer-wins and tombstones, proven
  convergent across simulated devices; Room persistence with incremental
  refolds, pre-migration backups, and an SQLCipher key wrapped by the Android
  Keystore (M2).
- Full expense UI: groups, members, all six split modes with a live
  remaining-to-allocate bar, multi-payer entry, categories, search and filters,
  balances with exact pairwise who-owes-whom and an opt-in simplify view,
  settle-up with suggested transfers and write-offs, 30-day trash, archive,
  ad-hoc one-off splits, recurring-expense reminders, biometric app lock, and
  screenshot blocking (M3).
- Splitwise CSV import with member mapping, preview, and duplicate-file
  protection; re-importable CSV export; encrypted `.dibs` backup and
  restore-as-merge (Argon2id + XChaCha20-Poly1305) with passphrase strength
  enforcement and a 30-day backup reminder (M4).
- Receipt scanning, fully on-device: CameraX capture with multi-frame support,
  gallery import, ML Kit text recognition feeding a rule-based layout parser
  with an exact-arithmetic gate, a mandatory review screen, and hand-off into
  the itemized editor. Manual entry stays the primary path (M5).
- Identity model (SPEC §5.9): one master key deriving a stable Ed25519
  identity (fingerprint handles like "Priya #a1b2") and uncorrelatable
  per-group transport keys; signed invite/claim/approve/remove ops validated
  deterministically in the ledger fold, with golden vectors and convergence
  race tests. Join/invite UI arrives with sync (M6).
