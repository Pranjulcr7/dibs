# Changelog

All notable changes to Dibs will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Sharing a group between phones with no server (M6): invite by QR code or by
  one file that carries the invite and the group's data; join by opening the
  file, scanning the code, or pasting a link; share and open update files;
  a per-group "changes to share / up to date" indicator. Invites are single
  use, expire after seven days, and can be cancelled; groups can require
  approval for new members; members with no history can be removed.
- Several payers on one expense, entered per person with a running check that
  the payments add up.
- A hint when the same repayment was probably recorded on two phones.
- `docs/requirements.yaml`, mapping every numbered requirement to its tests,
  with a build check; and `scripts/e2e`, which drives the installed app on two
  emulators.

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

### Fixed

- Editing an expense that had several payers (for example one imported from
  Splitwise) rewrote it to a single payer and changed everyone's balance.
- A group's status line loaded the whole history on every update, undoing the
  fast open of large groups.
- A person who skipped entering their name was shown to other members as
  "You". Inviting now asks for a name first.
- A phone that lost a race for a single-use invite, or was removed from a
  group, kept accepting expenses nobody else would ever receive. It now says it
  is not a member.
- Changes written by a newer version of Dibs were skipped without a word; the
  import now says so.
