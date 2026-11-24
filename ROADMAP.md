# ROADMAP — from here to a released, cross-platform, agent-ready Dibs

Written 2026-11-24 from a full review of the repo, the planning documents, a
`./gradlew check` run on both the working tree and the last commit, and three
research passes (adoption, Kotlin Multiplatform, MCP). It is the master plan;
`PLAN.md` stays the per-phase working plan, rewritten at the start of each phase
as before.

Read `CLAUDE.md`, `SPEC.md`, `DECISIONS.md` and `PROGRESS.md` first. This file
does not repeat them.

---

## 1. Where the project stands (verified, not assumed)

| Fact | Evidence |
|---|---|
| Last commit `b756065` (2026-08-27) passes `./gradlew check` | Clean export of HEAD: BUILD SUCCESSFUL, 512 tests, 0 failures |
| The working tree fails `./gradlew check` | 6 task failures, all caused by the uncommitted M6 work (below) |
| M0–M5 shipped; M6 half done; M7 not started | `PROGRESS.md`, commit log, code |
| No tagged release, no translations, one instrumented test file | `git tag`, no `values-*` dirs, only `EncryptedDatabaseTest` |
| Origin is `github.com/Pranjulcr7/dibs`; README badges and clone URL say `dibs-app/dibs` | `git remote -v` vs `README.md` |

**What the uncommitted work broke** (12 modified files, 2 new):

1. `MembersViewModel` and `GroupDetailViewModel` gained constructor parameters;
   `MembersViewModelTest` and `SettleTest` were not updated and no longer compile.
2. ktlint (import order, parameter spacing) and detekt (`SpreadOperator`) fail in
   `GroupDetailScreen.kt` and `MembersScreen.kt`. Both files also use inline
   fully-qualified names, a `Toast`, and do file and intent work inside
   composables, against the "stateless composables" convention.
3. `:core:domain` coverage is 89.69% against the 90% gate, because
   `BundleSync.kt` moved outcome types into the domain with no domain test.
4. `:feature:settings:lintAnalyzeDebugUnitTest` failed on a missing KSP output.
   It did not fail on the clean HEAD run, so treat it as a symptom of the broken
   tree; investigate only if it survives P0.
5. `GroupDetailViewModel` now calls `ledger.allOps(groupId).size` on every state
   emission. That loads the op log on a read path and defeats D40's "reads never
   load the op log", which is what made cold open meet NFR-3.

**What sync can and cannot do today.** Creating an invite QR/link and sharing or
importing an update file exist (uncommitted). Nothing handles an incoming
`dibs://join` link: the manifest declares the intent filter but `MainActivity`
never reads `intent.data`. There is no QR scanner, no join screen, no file
association for update files, and no UI for revoke, approve, remove, or join
policy, although all of those ops are validated in the fold.

**Requirements that look unimplemented.** These come from searching code and
string resources, not from running the app. Confirm each one in P1 before
building anything.

| Requirement | Finding |
|---|---|
| FR-G1 group colour | No picker |
| FR-G3 remove member | Op exists in the fold; no UI |
| FR-G4 delete group | No typed-confirm delete, no pre-delete backup |
| FR-S5 member detail | No screen |
| FR-S6 remind | No shareable summary |
| FR-T1 language, FR-T2 default split mode | No setting |
| FR-T5 storage usage, clear receipt images | Deferred in PROGRESS, still absent |
| FR-R6 equal split of tax | Not found |
| Edge 17 (100 members), 32 (duplicate settlement hint), 63 (storage full) | Not found |
| Edge 22 clock-skew warning | Domain logic exists, never surfaced |
| Edge 26 member merge | Detection only (D16); merge op still absent |
| Edge 64 migration auto-restore | Backup is taken; automatic restore not found |
| SPEC §4.2 haptics, undo snackbar | Not found |
| NFR-13 RTL, edge 69 tablet/landscape, edge 71 API 26 | Untested |
| NFR-14 reproducible build, release signing, baseline profiles | Not started |
| M5 exit: accuracy on 30 photographed receipts | Never measured |

---

## 2. Conflicts with CLAUDE.md, and decisions only the owner can make

`CLAUDE.md` says to raise conflicts, so here they are. Nothing in this plan
edits `CLAUDE.md` silently; each amendment is a named step that waits for the
owner's yes.

### 2.1 Conflicts

| Request | What CLAUDE.md says today | Proposed resolution |
|---|---|---|
| iOS support | "iOS (later, separate effort)" is on the out-of-scope list; the app is "a native Android app" | Amend at the start of P6. Text in §2.3 |
| MCP server | "Any server, backend, cloud function, or hosted API" is out of scope | A stdio process the user's own AI client launches on their own computer is not a hosted backend. Record that distinction (new decision), and never build a remote connector |
| iOS port needs new stack choices | Hilt, `android.icu`, `strings.xml`, JUnit5, Robolectric, "pure Kotlin/JVM" domain are fixed | Amend the stack table and invariant 4 in P6 |
| Frictionless sync | "No backend server exists, and none will be added" | SPEC FR-Y2 already allows opt-in public relays the project does not run. P9 stays inside that |

### 2.2 Owner decisions

Each has a recommendation so work is never blocked waiting; record the answer
in `DECISIONS.md`.

1. **Approve the CLAUDE.md amendments in §2.3.** Needed before P6.
2. **Developer accounts.** Google Play ($25, one-time) and Apple ($99/year; fee
   waivers go only to nonprofit, education or government entities). Needed for P4 and P8.
3. **Android developer verification.** Google is enforcing identity
   verification for all apps on certified devices globally in 2027; unregistered
   apps will need a developer-mode flow with a one-day wait. Recommendation:
   register (the Play account covers it), because the target user will not
   complete that flow. F-Droid publicly advises developers not to register;
   this is a values call.
4. **A domain and a static page for invite links** (P5). Recommendation: yes.
   `dibs://` links are not tappable in most chat apps; an `https://` link with
   the secret in the URL fragment never sends the key to the host.
5. **Payment hand-off** (P5): a "pay with UPI/PayPal/Venmo" link that opens
   another app. Dibs would still never move money, but it brushes the
   "no payment processing" line. Recommendation: allow plain outbound links and
   copyable payment details, nothing more.
6. **Exchange rates** (P5). Invariant 3 forbids network calls except opt-in
   sync, so rates are manual entry only unless the invariant is amended.
   Recommendation: manual only.
7. **Relay sync and the INTERNET permission** (P9). Today no manifest declares
   it, which is a provable privacy claim. Any relay ends that proof for the
   build that includes it. Recommendation in P9.
8. **Contact addresses.** `SECURITY.md` lists `p_rout@gosentrix.com`. Confirm
   that is a real inbox you read, or replace it. Same for `CODE_OF_CONDUCT.md`.
9. **Repo home.** Either move the repo to a `dibs-app` org or fix every
   `dibs-app/dibs` URL to `Pranjulcr7/dibs`.

### 2.3 Proposed CLAUDE.md amendments (apply in P6, after approval)

- "What Dibs is": "A native Android app" becomes "An Android and iOS app built
  from one Kotlin Multiplatform codebase".
- Invariant 4: "`:core:domain` is pure common Kotlin: no Android, no JVM-only,
  and no Apple imports. It must compile and test on the JVM and on the iOS
  simulator without an emulator."
- Invariant 6 gains: "Byte-identical includes across platforms. Cross-platform
  golden vectors are part of the compatibility contract."
- Stack table: DI, formatting, resources and test rows change per P6.
- Out of scope: remove the iOS line. Reword the server line to "Any hosted
  server, backend, cloud function or API operated by the project. A local
  process started by the user on their own machine (the MCP companion) is
  allowed; it must open no listening socket."

---

## 3. Rules of engagement for the implementing agent

These apply to every phase and override convenience.

1. **One phase at a time.** Do not start a phase until the previous exit gate is
   met and `PROGRESS.md` says so with evidence.
2. **Evidence or it did not happen.** Every "works" claim in `PROGRESS.md`
   carries the command that proved it and its result (test counts, timings,
   screenshot paths). If a check could not be run, say so in those words.
3. **Tests first** for anything in `:core:domain`, and for every bug: write the
   failing test, then the fix.
4. **Never regenerate golden vectors to make a build pass.** The D31 pre-release
   exception ends at the P4 format freeze; after that, format changes are new
   additive payload types only.
5. **Three attempts, then `BLOCKED.md`.** No workarounds for a failing
   invariant or property test.
6. **Record every ambiguity resolution** in `DECISIONS.md` (next free number is
   D42). If SPEC is wrong or contradicts an invariant, stop and say so.
7. **Research facts in this file are dated 2026-11-24.** Before acting on any
   library version, store policy or protocol detail, check the current
   documentation. §9 marks which facts were read at source and which were not.
8. **Do not touch** release keystores, store accounts, or anything in §8; ask
   the owner.
9. **Commit per logical unit**, conventional commits, never to `main` while it
   is red.

### 3.1 The verification ladder

Every flow in §4 must be covered at each level that applies to it.

| Level | What | Tooling | Runs |
|---|---|---|---|
| L1 | Domain unit and property tests | Kotest, JUnit5 (later commonTest) | Every `check` |
| L2 | ViewModel and Compose tests over the real fold | Robolectric, Turbine | Every `check` |
| L3 | Screenshot tests: light, dark, 200% font, RTL, tablet width | Roborazzi or Paparazzi (pick one in P1) | Every `check` |
| L4 | End-to-end on emulators through the real encrypted database | `:app` instrumented Compose tests on Gradle Managed Devices, API 26 and latest | CI on PR |
| L5 | Hardware evidence: camera, biometrics, performance, two real phones | adb-driven or by hand, recorded in `PROGRESS.md` | Before each release |

### 3.2 Traceability

P1 creates `docs/requirements.yaml`: every FR, NFR, edge case (1–77) and flow
(§4) with a status of `covered`, `manual-evidence`, `superseded` or `open`. A
build check, `verifyTraceability`, wired into `check`, fails when a `covered`
ID is not named by at least one test (in the test name or a `@Covers("edge-27")`
annotation). `open` items are the backlog. This replaces the stale tables in
`AUDIT.md`.

---

## 4. Flow catalog

"Verify every flow" means this list. Each row needs L2 at minimum; the last
column names what else is required. P1 turns each into tests or `open` items.

| ID | Flow | Also needs |
|---|---|---|
| F01 | First run: three onboarding pages, skip, enter name; no permission asked | L3, L4 |
| F02 | Create group: name, emoji, colour, currency; creator self-claims | L4 |
| F03 | Members: add, rename, deactivate, reactivate, remove (no history only), duplicate-name badge | L3 |
| F04 | Add an equal-split expense in three taps, under five seconds | L4, L5 timing |
| F05 | Exact, percent, shares, adjustment, itemized: live remaining bar, blocked saves (edges 8–10), one-tap fix, one-cent note | L1, L3 |
| F06 | Multi-payer expense | L1 |
| F07 | Edit an expense; history view; "edited on another device" note | L4 |
| F08 | Delete, trash, restore; hidden after 30 days | L4 |
| F09 | Duplicate an expense | — |
| F10 | Recurring rule: notification, prefilled editor, explicit confirm; never auto-create | L4 |
| F11 | Search and filter by text, member, category, date range, amount | L3 |
| F12 | Balances: pairwise, simplify toggle with explanation, member detail drill-down | L1, L3 |
| F13 | Settle up: suggested, manual, partial, write-off, history | L1, L4 |
| F14 | Remind: shareable text summary | — |
| F15 | "Just this once" ad-hoc split | L4 |
| F16 | Archive and unarchive; delete group with typed confirmation and pre-delete backup | L4 |
| F17 | Scan: capture, review, itemized editor, save; gallery; multi-frame; permission denied; duplicate receipt; timeout; the foss build with no OCR | L4 (gallery path), L5 (camera) |
| F18 | Splitwise import: mapping, preview, skipped rows, checksum warning, re-import refused, 5,000 rows, non-English header | L1, L4 |
| F19 | CSV export, then re-import | L1 |
| F20 | Backup: create with strength meter; restore on a fresh install; restore as merge; wrong passphrase; corrupted file; reminder banner | L4 |
| F21 | Invite: create, show QR, share link | L3 |
| F22 | Join: open link or scan QR, preview, claim an existing member or create one | L4 two emulators, L5 two phones |
| F23 | Share updates and import updates; every `ImportOutcome` message; "some changes may be missing" | L4 |
| F24 | Two devices offline: concurrent edits, delete against edit, double settlement; both converge | L1, L4 two emulators |
| F25 | Invite lifecycle: expiry, revoke, reuse refused, approval mode, "joined via your invite" with remove | L1, L4 |
| F26 | Settings: theme, dynamic colour, language, display name, default currency, default split mode, default group | L3 |
| F27 | App lock and screenshot blocking | L5 |
| F28 | Storage usage, clear receipt images, About | — |
| F29 | Lifecycle: process death mid-draft, rotation, 200% font, dark, RTL, tablet and landscape, TalkBack, API 26 | L3, L4 |
| F30 | Failure paths: storage full, migration failure with automatic restore, everything in airplane mode | L4 |

Later phases add F31 (relay sync), F32 (every flow above on iOS) and F33 (MCP
tool flows).

---

## 5. Phases

Order and dependencies:

```
P0 green tree → P1 verification harness → P2 finish sync → P3 close SPEC gaps
→ P4 Android 1.0 → P5 adoption pack (1.1)
→ P6 shared Kotlin core ─┬→ P7 MCP, read-only
                          ├→ P8 iOS app
                          └→ P9 relay sync → P10 MCP writes and OS assistants
```

P0–P4 are the old M6 and M7, finished properly. Sizes are relative:
S is days, M is a week or two of agent work, L is several weeks, XL is more.

### P0 — Get back to green (S)

The uncommitted work belongs to the owner. Do not discard or stash it; finish it.

1. Update `MembersViewModelTest` and `SettleTest` for the new constructors, with
   fakes for `GroupKeyStore`, `IdentitySource`, `BundleSync`, `SyncMetaStore`.
2. Add tests for `MembersViewModel.createInvite` (bootstrap self-claim,
   `SelfNotClaimed`, expiry from the injected clock) and for
   `GroupDetailViewModel` export and import (each outcome emitted once).
3. Add `Ledger.opCount(groupId)` backed by a count query; use it for the sync
   chip. Add a repository test proving a state read does not load the op log.
4. Move file, intent, QR-bitmap and message-mapping code out of the composables
   into the ViewModel or small helpers; replace the `Toast` with the existing
   snackbar host; fix ktlint and detekt without suppressions.
5. Add a domain test for `SyncStatus.of` and the outcome types so coverage
   clears 90% honestly.
6. Commit the result in logical units.

**Exit:** `./gradlew check` green; `:app:assembleDebug` for both flavors;
`PROGRESS.md` entry with the test count.

### P1 — Verification harness and an honest backlog (M)

1. Build `docs/requirements.yaml` and `verifyTraceability` (§3.2). Walk every
   FR, NFR and edge case against the code and tests; correct the table in §1.
2. Pick and wire a screenshot-test library; cover every screen in light, dark,
   200% font, RTL and tablet width.
3. Add `:app` instrumented tests on Gradle Managed Devices (API 26 and latest),
   with a Hilt test application over the real SQLCipher database. First flows:
   F01, F02, F04, F08, F18, F20.
4. Turn on Compose accessibility checks in the instrumented suite (labels,
   48 dp targets, contrast).
5. Add `scripts/e2e/two-device.sh`: boots two emulators, installs the app, and
   moves files between them over adb. P2 fills in the scenarios.
6. CI: run L1–L3 on every push, L4 on pull requests; cache the emulator image.
7. Mark `AUDIT.md` as superseded by `docs/requirements.yaml`.

**Exit:** traceability check in `check`; every flow in §4 is `covered`,
`manual-evidence` or `open` with a named gap; CI green.

### P2 — Finish sync tier 1 (old M6) (L)

Goal: two people who have never used Dibs get a shared group working with no
server, and neither needs anything explained.

1. **Join flow.** Handle `dibs://join` in `onCreate` and `onNewIntent`. New
   join screen and ViewModel: strict link parse, adopt the group key (a
   different key for a known group is a loud error), group preview, then claim
   an unclaimed member or create a new one with a signed `ClaimMember`, and set
   `selfMembers`.
2. **The missing-data problem.** An invite link carries the key, not the
   group's data, so a joiner sees nothing until a bundle arrives. Requirement:
   joining takes one action when the invite arrives as a file (invite and
   bundle travel together) and at most two when it arrives as a QR code. After
   claiming, prompt the joiner to send their update back so others see the
   claim. Design it, pick the simpler option for the user, record the decision.
3. **QR scanning** with zxing-core and CameraX image analysis, so the foss
   flavor stays free of proprietary code.
4. **Update files.** Register a file extension and MIME type; accept open-with
   and share-to-Dibs; cap sizes; stream-parse large bundles with progress and
   cancel (edge 29).
5. **Sync state.** The chip shows "up to date", "n changes to share", "last
   synced …", pending, and "some changes may be missing" (FR-Y4, edge 75).
6. **Membership management.** List and revoke invites; approval-mode toggle;
   approve pending joiners; remove member; an in-app "X joined via your invite"
   notice with a remove action.
7. **Surface what the domain already knows.** Clock-skew warning (edge 22),
   duplicate-settlement hint (edge 32), duplicate-member notice (edge 26).
   Member merge stays out of 1.0 unless a merge op is designed that reconciles
   claimed identities (AUDIT T8); say so in the UI copy and the SPEC.
8. **Reconcile SPEC edge 74.** The SPEC describes a signed `AnnounceDevice` op;
   the code authenticates the bundle sender against claimed members in a
   dry-run fold (D41). Make the SPEC and `SECURITY.md` describe what ships.
9. **Hardening.** Property-fuzz `InviteLink.parse`, `SyncBundles.decode` and
   `BackupCodec.decrypt`: arbitrary bytes never crash and never partially apply
   (edges 27, 59, 76).
10. Write the sync section of `SECURITY.md`.

**Verification:** edges 27–32 and 72–77 each have a named test. The two-emulator
script runs F22–F25 end to end and asserts both sides fold to byte-identical
`canonicalBytes()` (expose a debug-only export for the assertion). One pass on
two physical phones, recorded with screenshots.

**Exit:** MILESTONES M6 exit criteria met, with evidence.

### P3 — Close the remaining SPEC gaps and run the quality passes (M)

Work the `open` items from P1. Expected content: the table in §1, plus:

- TalkBack walk of the main flows, 200% font, dark, RTL, tablet and landscape
  on every screen; fix what the screenshots and accessibility checks show.
- API 26 emulator run of the whole L4 suite (edge 71).
- Storage-full and migration-failure paths with injected faults (edges 63, 64).
- 100-member group: virtualized pickers, a warning above 50 (edge 17), and a
  scroll benchmark.
- M5's unmet exit criterion: parser accuracy on 30 real photographed receipts.
  The owner supplies the photos (§8); record the number whatever it is.

**Exit:** no `open` item without an owner-approved deferral; M3 and M5 exit
criteria met.

### P4 — Android 1.0 (old M7) (M)

1. **Format freeze review.** This is the last moment a canonical encoding can
   change (D31). Review every payload against P5–P10 for fields that cannot be
   added later as new payload types. Two items must be settled here:
   - **One Ed25519 verification rule.** Libraries differ on non-canonical
     signatures and small-order keys. The fold's accept/reject decision is
     consensus state, so pin the strict rule now, enforce it explicitly in the
     current JVM code, and add vectors for the edge cases. Otherwise an iOS
     device could later quarantine an op that Android accepted.
   - **The backup header** stores an Argon2 parallelism value; reject anything
     but 1, since the library planned for iOS cannot vary it.
   Then declare the freeze in `DECISIONS.md`.
2. **Release engineering.** Release signing from secrets (keystore never in the
   repo; the owner holds it, §8); reproducible build for both flavors (NFR-14),
   proven by building twice in CI and comparing hashes; baseline profile and a
   macrobenchmark module that fails CI on a cold-start or 5k-group regression.
3. **Measure every NFR again** on the release build, including a mid-range
   2022 device (the open `BLOCKED.md` item).
4. **Stores.**
   - GitHub Releases: signed APKs for both flavors on every tag.
   - IzzyOnDroid first (developer-built APK, 30 MB limit), then F-Droid with
     reproducible, developer-signed builds from the foss flavor.
   - Play: listing, Data safety form, financial features declaration. New
     personal accounts need 12 testers opted in for 14 continuous days before
     production; start the closed test at the beginning of this phase.
5. **Repository readiness.** Fix URLs and contacts (§2.2 items 8–9); rewrite
   the README install section; file the drafts in `ISSUES.md` as real issues;
   set up Weblate (free for libre projects, reads `strings.xml`); add a
   `fastlane/metadata` tree for store text and screenshots.
6. **Launch**, in the order OPEN_SOURCE_SETUP §8 gives, only after the APK is
   downloadable. Lead with "no daily limit, no ads, no account": the daily cap
   is the most common Splitwise complaint and the reason its open-source
   alternatives exist.

**Exit:** MILESTONES M7 exit criteria; `v1.0.0` tag; the same APK hash from two
independent builds; a store or repo listing a stranger can install from.

### P5 — Adoption pack, 1.1 (M)

Ranked by the evidence in §9. All of it is Android-only UI that P6 later moves
to shared code, so keep ViewModels free of Android types as you go.

1. **Tappable invite links.** An `https://` link whose fragment carries the
   invite, served by a static page that opens the app or explains how to get
   it. Verified App Links. The page stores and receives nothing.
2. **Calculator in the amount field** (integer arithmetic on minor units only).
3. **Per-expense currency with a manual rate**, for "settle this trip in one
   currency". The rate is an integer ratio entered by the user and recorded
   with the settlement; balances stay per-currency in the ledger.
4. **Settle-up hand-off**, if approved: copyable payment details, a UPI link or
   QR, generic pay links.
5. **Share and export**: balances as text and as a PDF; statistics by category
   and month; custom categories.
6. **Splitwise import as the first-run hero path**: sample-file detection,
   clearer errors, a property test against generated exports (ISSUES #17).
7. **Translations**: first locales through Weblate, including one RTL language.

Each item ships with L1–L4 coverage and a `docs/requirements.yaml` entry.

**Exit:** `v1.1.0` on every channel from P4.

### P6 — Shared Kotlin core (L)

Precondition: the §2.3 amendments are approved. Goal: `:core:domain`,
`:core:sync` and `:core:data` compile for Android, desktop JVM and iOS, and
produce byte-identical results on all three. No iOS UI yet.

**Spikes first, one day each, results in `DECISIONS.md`.** Stop if one fails.

| Spike | Question |
|---|---|
| Encrypted database on iOS | Can Room's KMP runtime open an encrypted file on iOS through a custom SQLite driver (SQLCipher or SQLite3MultipleCiphers)? Verify by opening the file with the cipher's own CLI: `PRAGMA key` against plain SQLite can appear to succeed. Zetetic ships no such driver |
| Typed settings on iOS | Does `datastore-core` with an Okio-based kotlinx-serialization serializer work on iOS? Official docs only promise Preferences DataStore |
| Crypto byte compatibility | Does a libsodium binding decrypt backups and bundles produced by today's Tink and BouncyCastle code, and agree on every Ed25519 vector from P4? |

**Work, in order:**

1. **Toolchain on Android only.** Replace Hilt (its processor is reported not
   to read newer Kotlin metadata, and it has no multiplatform support) with
   Koin or Metro; then upgrade Kotlin, KSP and AGP and rewrite the convention
   plugins for the multiplatform library plugin, which has no flavors or build
   types. The `foss` flavor stays in `:app`. Ship an Android release from this
   state before going further.
2. **`:core:domain` to common Kotlin.** The JVM-only surface is small:
   `BigInteger` (split math), commons-codec BLAKE3 (op ids, handles), i2p EdDSA,
   `javax.crypto` HMAC, `java.time.LocalDate`, `TreeMap`, `PriorityQueue`,
   `Base64`, `MessageDigest`.
   - Before changing anything, generate a larger golden corpus from the current
     build: op encodings and ids, folded state bytes, split allocations,
     settlement plans, handles, invite links.
   - No maintained multiplatform BLAKE3 was found; vendor a small pure-Kotlin
     one pinned to the official test vectors.
   - Replace `BigInteger` with a small exact helper, differential-tested
     against `java.math.BigInteger` on the JVM over the existing 10,000-case
     properties.
   - Move the property tests to common tests so they run on the iOS simulator.
   - Rewrite `verifyPureJvm` as a purity check on the common source set.
3. **`:core:sync`** (its main code already has no Android imports): crypto
   behind a provider interface; fixtures of Android-produced bundles checked in
   and decoded on every target.
4. **`:core:data`**: Room on its multiplatform runtime; the Android database
   file and its SQLCipher key path unchanged, proven by a migration test from a
   1.x database; desktop and iOS drivers per the spike; backup fixtures decoded
   on every target.
5. CI: a macOS job running the common tests on the iOS simulator (free for
   public repositories).

**Exit:** the full golden corpus passes on JVM, Android and iOS simulator; a
1.x Android install upgrades with data intact; an Android release ships from
the new toolchain with no behaviour change.

### P7 — MCP companion, read-only (M)

A desktop program, `dibs-mcp`, that an AI client launches over stdio. It opens
no socket and makes no network calls.

1. **It is a replica, not a remote control.** The user gives it a group invite
   link; it stores the group key in the OS keychain and reads update files from
   a folder the user chooses. It folds them with `:core:domain` in memory. It
   never needs the backup passphrase and never holds the phone's identity key.
   Reading an encrypted backup was rejected for exactly that reason: a backup
   contains the master key.
2. **Tools** (all read-only, `openWorldHint: false`, deterministic order):
   `dibs_list_groups`, `dibs_list_expenses` (filters, cursor pagination,
   concise or detailed), `dibs_get_expense`, `dibs_get_balances` (optional
   simplified plan), `dibs_export_csv`, `dibs_sync_status`.
3. **Output rules.** Money is integer minor units plus a currency code and a
   formatted string, never a JSON float. Every result states how fresh the data
   is. Names and descriptions come back as delimited data, because any group
   member can write them and another member's agent will read them.
4. **SDK.** Use the official Kotlin SDK behind a thin adapter so tool handlers
   contain no SDK types; it is pre-1.0 and has had breaking releases. The Java
   SDK is the fallback.
5. **Packaging.** A self-contained binary per OS (bundled runtime; GraalVM
   native image only if a spike shows the crypto and SDK work under it),
   shipped as an `.mcpb` bundle and a plain CLI install for Claude Code, Cursor
   and VS Code, with a registry entry.
6. **The phone side.** A "Connect an AI assistant" screen that explains in plain
   words what is shared, and that whatever the assistant reads goes to that
   assistant's provider. This is the user's choice, but it is the first time
   Dibs data leaves a device in readable form, so the wording matters.

**Verification:** tool handlers unit-tested against a fixture ledger; MCP
Inspector run; an agent evaluation of about 20 realistic questions scored on
the returned numbers against the folded state, not on prose; a prompt-injection
test with hostile expense descriptions.

**Exit:** install on a clean macOS and Windows machine, connect from two
clients, and answer "who owes whom in <group>" correctly.

### P8 — iOS app (XL)

Shared UI with Compose Multiplatform. Rewriting 5,800 lines of Compose in
SwiftUI would give a solo maintainer two UIs to keep in step forever.

1. Move `:core:ui` and `:feature:*` to common code: resources to the
   multiplatform resources system, formatters behind `expect`/`actual`
   (`NSNumberFormatter` and `NSDateFormatter` on iOS, fed decimal strings so no
   `Double` enters a money path), multiplatform navigation and ViewModels.
2. `:iosApp` Xcode project: Keychain-held database key, file and link handling
   for invites and update files, local notifications for recurring reminders,
   Face ID lock, privacy manifest.
3. Receipt scanning behind the existing `ReceiptParser` interface: Apple's
   Vision text recognition feeding the same rule-based parser, the system
   document scanner for capture. `NoOpReceiptParser` remains the fallback.
4. Known iOS costs to budget for: text input in the amount field (try the
   native text input mode), keyboard and inset handling, VoiceOver and Dynamic
   Type passes, 10–30 MB of added size, a look that is not fully native on
   current iOS.
5. iOS cannot run periodic background work on demand; sync happens on open and
   on user action. Say so in the sync chip.
6. App Store: non-exempt encryption declaration (standard algorithms not
   supplied by the OS), privacy policy URL, purpose strings, TestFlight.

**Verification:** F32 means every row of §4 on iOS: common UI tests on the
simulator, screenshot tests, a VoiceOver walk, and the cross-platform case that
matters most, an Android phone and an iPhone joining one group and folding to
identical bytes.

**Exit:** TestFlight build; Android and iPhone complete F22–F25 together;
App Store submission accepted.

### P9 — Sync that does not need a file hand-off (L)

Every low-friction splitter found in research relies on an always-on endpoint.
With only manual hand-off, each sync is a file sent over a chat app. This phase
is the largest single adoption lever after iOS; the owner may want it before P8.

1. **Design spike.** Compare public Nostr relays (nothing for the project to
   run; retention not guaranteed, so the full-snapshot hand-off of edge 31 is
   mandatory), iroh (direct connections, but both phones must be online at
   once), and a user-supplied relay URL (FR-Y3). Recommendation going in:
   Nostr-style public relays as the opt-in default, with FR-Y3 alongside.
2. Implement `RelayTransport` in shared code behind `SyncTransport`
   (`BEST_EFFORT`), per group, off by default, with the plain-language opt-in
   screen FR-Y2 requires. Relays see ciphertext and per-group transport keys
   only; the group key never travels over a relay.
3. Keep the provable-offline option: network code lives in one module, a build
   check forbids network APIs anywhere else, and the foss build can exclude it.
4. Edges 30, 31 and 75 with fault injection: unreachable relay, purged
   history, dropped and replayed events.

**Exit:** two phones on different networks converge without exchanging a file;
everything still works with sync off and in airplane mode.

### P10 — MCP writes, and the phone's own assistants (M)

1. **Write tools as draft then commit.** `dibs_draft_expense`,
   `dibs_draft_settlement`, `dibs_draft_edit_or_delete`,
   `dibs_draft_splitwise_import` validate and return a preview with exact
   allocations; `dibs_commit_draft` (marked destructive, with an idempotency
   key that determines the op id) signs and appends. No tools for invites,
   keys, member removal or restore.
2. The companion becomes a real member device with its own identity key,
   joined through a normal invite, so every agent-written op is attributable
   in history and the phone can remove it. With P9 it syncs like any device;
   without P9 it writes update files.
3. **On-phone assistants.** No mainstream mobile AI app can reach an MCP server
   on the same phone. The equivalents are Android AppFunctions (Android 16+,
   Gemini only, Play builds only, preview access required) and Apple App
   Intents. Expose the same tool set through each, as separate, optional steps.

**Exit:** an agent adds an itemized expense from a receipt description, the
user confirms it, and it appears on the phone with the agent device named in
the history.

---

## 6. What "done" means for each phase

SPEC §8 still applies. Added to it:

- `./gradlew check` green, including `verifyTraceability`.
- The L4 suite green on API 26 and latest; from P8, the iOS simulator suite too.
- Works in airplane mode (from P9: with sync off).
- `PROGRESS.md` updated with evidence; `docs/requirements.yaml` has no new
  `open` item without a reason.
- No invariant in `CLAUDE.md` violated; any deviation is in `DECISIONS.md`.

---

## 7. Risks worth naming

| Risk | Why it matters | Mitigation |
|---|---|---|
| Cross-platform fold divergence | Two friends seeing different numbers ends trust in the app | P4 freeze, P6 golden corpus on three runtimes, strict signature rule |
| Encrypted database on iOS | No vendor-supported path exists | Spike before P6; the two platforms need not share a file format, since data crosses only as bundles and backups |
| Manual-only sync at launch | High friction for the non-technical target user | Invite-with-bundle in P2, tappable links in P5, relay in P9 |
| Android developer verification in 2027 | Unregistered apps become hard to install | Owner decision §2.2 item 3 before P4 |
| Play closed-test rule | 14 days of fixed lead time | Start it on day one of P4 |
| Kotlin MCP SDK instability | Pre-1.0, not yet on the latest protocol revision | Adapter layer; Java SDK fallback |
| Agent reads data another member wrote | Prompt injection through expense text | Read-only first, delimited output, draft-then-commit, no network in the server |
| Solo maintainer, ten phases | Scope | Every phase from P4 on ends in a shippable release; stop anywhere |

---

## 8. Things only the owner can do

| When | Action |
|---|---|
| Now | Answer §2.2 items 8 and 9 (contact addresses, repo home) |
| Before P3 ends | Photograph 30 real receipts for the M5 accuracy number |
| Start of P4 | Create the Play developer account; recruit 12 testers; generate and safely store the release keystore; decide on developer verification |
| Start of P4 | Borrow or buy a mid-range 2022 Android phone for the NFR run |
| Before P5 | Register a domain for invite links, if approved |
| Before P6 | Approve the CLAUDE.md amendments |
| Before P8 | Join the Apple Developer Program; have an iPhone for testing |

---

## 9. Research behind the choices

Read at the cited source on 2026-11-24 unless marked otherwise. Re-check before
relying on any of it.

**Adoption**
- Splitwise's free tier limits daily expenses (user reports range from 2 to 4
  per day) and puts conversion, receipt scanning, itemization, search and
  charts behind Pro. https://www.splitwise.com/pro ,
  https://www.trustpilot.com/review/splitwise.com
- Most-requested features on Splitwise's forum and in the Spliit and SplitPro
  trackers: paying for individual items (985 votes), a calculator in the amount
  field (955), adding people without an account (625), recurring expenses,
  per-expense currency, full Splitwise import, offline mode.
  https://feedback.splitwise.com/forums/162446-general?filter=top
- Closest existing project: Quits (Kotlin Multiplatform, F-Droid, encrypted
  relay, no iOS). https://github.com/SeineEloquenz/quits
- Play closed-testing rule. https://support.google.com/googleplay/android-developer/answer/14151465
- Android developer verification. https://developer.android.com/developer-verification/guides
- F-Droid inclusion and reproducible builds. https://www.f-droid.org/en/docs/Inclusion_Policy/ ,
  https://f-droid.org/en/docs/Reproducible_Builds/
- Not verified: Reddit sentiment (blocked), whether Tricount works without a login.

**Kotlin Multiplatform**
- Compose Multiplatform for iOS is stable; gaps remain in native text input and
  platform look. https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Room's multiplatform drivers do not encrypt; Zetetic has no driver.
  https://developer.android.com/kotlin/multiplatform/sqlite ,
  https://discuss.zetetic.net/t/room-2-7-beta-kmp-support/6864
- The multiplatform Android library plugin has no flavors or build types.
  https://developer.android.com/kotlin/multiplatform/plugin
- JetBrains' migration guide on Hilt. https://kotlinlang.org/docs/multiplatform/migrate-from-android.html
- libsodium binding with Argon2id, XChaCha20-Poly1305 and Ed25519.
  https://github.com/ionspin/kotlin-multiplatform-libsodium
- App Store encryption documentation. https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption
- Not verified: typed DataStore on iOS, Xcode 27 support, the binding's
  byte-level agreement with the current Android crypto (hence the spikes).

**MCP**
- Current protocol revision 2026-07-28; stdio and Streamable HTTP transports.
  https://modelcontextprotocol.io/specification/latest
- SDK tiers; the Kotlin SDK is tier 3 and does not yet implement 2026-07-28.
  https://modelcontextprotocol.io/docs/sdk ,
  https://github.com/modelcontextprotocol/kotlin-sdk
- Bundle format and its server types (no Java type; use `binary`).
  https://github.com/modelcontextprotocol/mcpb
- Web and mobile AI clients reach only public HTTPS servers through the
  vendor's cloud. https://support.claude.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp
- Security guidance for local servers. https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices.md
- Tool design and evaluation. https://www.anthropic.com/engineering/writing-tools-for-agents
- Android AppFunctions. https://developer.android.com/ai/appfunctions
- Not verified: the Kotlin SDK under GraalVM native image; OS-level MCP on iOS.

---

## 10. Prompts

Start each phase in a fresh session:

> Read `CLAUDE.md`, `ROADMAP.md`, `PROGRESS.md` and `DECISIONS.md`. Implement
> **P<n>** from `ROADMAP.md` and nothing beyond it. First write your plan for
> the phase to `PLAN.md`, then proceed without waiting. Follow ROADMAP §3
> exactly: tests first, evidence for every claim, three attempts then
> `BLOCKED.md`. Stop at the phase's exit gate, run `./gradlew check`, and
> update `PROGRESS.md` with what shipped, what was deferred, and the evidence.

For P0, add: "The working tree has uncommitted M6 work that fails
`./gradlew check`. It is the owner's work in progress: finish it, do not
discard it."
