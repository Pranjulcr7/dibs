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
- Identity model (SPEC §5.9): one master key deriving a stable Ed25519
  identity (fingerprint handles like "Priya #a1b2") and uncorrelatable
  per-group transport keys; signed invite/claim/approve/remove ops validated
  deterministically in the ledger fold, with golden vectors and convergence
  race tests. Join/invite UI arrives with sync (M6).
