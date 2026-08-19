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

## Cryptographic design

Key hierarchy — everything grows from one 32-byte master key, generated
randomly on first run and stored wrapped by an Android Keystore AES-GCM key
(hardware-backed where the device has a TEE):

- **Identity key:** HKDF(master, "dibs-identity-v1") → Ed25519 keypair. Stable
  across groups; its fingerprint is the `#a1b2` suffix in member handles.
  Display names are self-chosen and never treated as verified — only the
  fingerprint is.
- **Transport keys:** HKDF(master, "dibs-transport-v1" | groupId), one per
  group, so group peers cannot be correlated across groups by key material.
- **Database:** SQLCipher with a random 256-bit key, itself wrapped by the
  Keystore. Stealing the database file without the device yields ciphertext.
- **Backups:** Argon2id (t=3, m=32 MiB, p=1) derives a key from your
  passphrase; XChaCha20-Poly1305 seals the payload with the entire file header
  (magic, version, salt, KDF parameters) bound as AEAD associated data —
  tampering with any byte fails authentication wholesale. Passphrase strength
  is enforced at creation. A wrong passphrase and a corrupted file are
  deliberately indistinguishable, and a failed restore writes nothing.

Membership integrity: invites, claims, approvals, and removals are Ed25519-
signed operations validated inside the deterministic ledger fold. Invalid or
forged ops are quarantined, never applied — every device reaches the same
verdict with no server to arbitrate.

## Scope notes

- No data ever leaves the device except user-initiated backup files and
  encrypted sync bundles.
- Sync transport details (encrypted bundles, QR invites) will be documented
  here as M6 lands.
