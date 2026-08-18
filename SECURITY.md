# Security policy

## Reporting a vulnerability

Please report vulnerabilities **privately** via GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository (Security → Report a vulnerability), or by email to
p_rout@gosentrix.com. Please do not open public issues for security problems.

You can expect an acknowledgement within a few days. Coordinated disclosure is
appreciated; there is no bug bounty.

## Threat model

Dibs is a local-first app with no server. What that buys, and what it doesn't:

**Dibs protects against:**

- **A relay operator** (when opt-in relay sync ships): all synced payloads are
  end-to-end encrypted (XChaCha20-Poly1305); the group key travels only inside
  the QR invite / link, never over a relay. A relay sees ciphertext and traffic
  metadata only.
- **Someone who obtains your backup file:** backups are encrypted with a key
  derived from your passphrase (Argon2id). A lost passphrase is unrecoverable by
  design.
- **Casual device theft:** the database is encrypted at rest (SQLCipher, key in
  the Android Keystore) and the app supports biometric lock.

**Dibs does not protect against:**

- **A compromised device.** If your unlocked phone or its OS is compromised,
  your data is readable. No app-level design changes that.
- **A malicious group member.** Anyone legitimately in a group can read the
  group's data and enter false expenses. Ops are signed per device, so members
  cannot be *impersonated* — but honesty about amounts is a social matter, not a
  cryptographic one.

## Scope notes

- No data ever leaves the device except user-initiated backup files and
  encrypted sync bundles.
- This file will be expanded with concrete algorithm and key-management details
  as the sync milestone (M6) is implemented.
