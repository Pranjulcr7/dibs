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
