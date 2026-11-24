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
  group's data and enter false expenses. Membership changes and the sender of
  every update file are signed, so a non-member cannot inject anything and a
  member's *identity* cannot be taken over — but honesty about amounts is a
  social matter, not a cryptographic one.

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

## Sync between phones

There is no server. Phones exchange files that a person sends through a
channel they already use.

- **Update files** (`.dibssync`) are the `dibs-sync-v1` bundle: a plaintext
  header (format version, group id, sender device and identity key, sender
  vector clock, op count) that is also the AEAD associated data; a body sealed
  with XChaCha20-Poly1305 under the group key; and an Ed25519 signature by the
  sender's identity key over BLAKE3(header ‖ ciphertext). Any changed byte
  fails wholesale; nothing is ever partially applied.
- **Who may send.** A bundle is applied only if its signer is a claimed,
  non-removed member of the group in the state that results from a dry-run
  fold of the bundle's own ops. A removed member's later bundles are refused.
  A group in which nobody has claimed a member yet has nobody to authenticate
  against, and syncs ungated.
- **Invite links** (`dibs://join?…`) carry the group id, the 256-bit group key,
  an invite id and the inviter's signature. **Whoever holds the link can read
  the group** and, while the invite is unused, unexpired and not cancelled,
  join it. Send invites only over a channel you trust; cancel one that went to
  the wrong place.
- **Invite files** (`.dibsinvite`) are an invite link, in the clear, followed
  by a sealed update file. They are exactly as sensitive as the link.
- **Joining** writes a `ClaimMember` op signed by the joiner's identity key
  and naming the invite. The fold enforces single use, expiry, cancellation and
  optional approval, identically on every phone. If two people race one
  single-use invite, exactly one wins on every phone; the other is told their
  phone is not a member.
- **Files from outside are untrusted input.** Links are parsed strictly with
  size caps; opened files are read with a 24 MB cap; the parsers for links,
  invite files, bundles and backups are tested against arbitrary and mutated
  bytes and must return a refusal, never throw.

Known limits, stated plainly:

- A member can read everything in the group and can enter false expenses.
  That is a social problem, not one cryptography solves.
- The group key does not rotate. Removing a member stops their bundles being
  accepted; it does not stop them reading update files they still receive.
- Opening a link stores its key before any data has arrived. Someone who
  already knows a group's id can send a link with a wrong key and block that
  phone from joining that group; the block is reported, never silent (D46).
- Dibs registers as a handler for generic binary files, because chat apps
  strip custom file types. It checks the first bytes itself and refuses
  anything that is not a Dibs file.

## Scope notes

- No data ever leaves the device except user-initiated backup files, invite
  files and encrypted update files.
- No manifest in any build declares the `INTERNET` permission.
