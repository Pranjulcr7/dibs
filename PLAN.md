# PLAN — finishing P2 and starting P3

The working plan for the current stretch of [ROADMAP.md](ROADMAP.md). Prior
plans are in git history. Status of each requirement lives in
[docs/requirements.yaml](docs/requirements.yaml), not here.

## Where things stand

| Phase | State |
|---|---|
| P0 Get back to green | Done |
| P1 Verification harness | Done in a different shape than planned (D50, D51). Not done: screenshot tests, CI for the on-device flows |
| P2 Finish sync tier 1 | Feature-complete on Android and verified at three levels (below). Remaining items listed under "Left in P2" |
| P3 onwards | Not started, except three P3 items closed early (several payers, duplicate-settlement hint, edge 28 warning) |

## How P2 was verified

1. **Domain** (`:core:domain`): invite file codec and the join pre-flight, with
   property tests that arbitrary and mutated bytes never throw.
2. **Two simulated phones** (`TwoDeviceJoinTest`): separate ledgers, key
   stores and identities, the real `SyncService`, and only bytes crossing
   between them. Invite-file and bare-link joins, revoked, expired and used-up
   invites, a race for one invite, approval, removal, damaged files, concurrent
   edits, delete against edit, double settlement.
3. **Two emulators** (`scripts/e2e`): the installed debug APK driven over adb,
   with files pulled off one phone and opened on the other. All seven
   two-phone flows pass (PROGRESS.md 2026-11-25).

## Left in P2

- **Two physical phones.** The ROADMAP exit gate asks for one pass on real
  hardware through a real chat app. Emulators cannot show how WhatsApp or
  Signal label a `.dibsinvite` file or whether "Open with Dibs" is offered.
- **QR scan on a camera.** The decoder is unit-tested and the screen is wired
  in; nothing has pointed a real camera at a real code.
- **Clock-skew warning (edge 22).** The check exists in the domain; surfacing
  it needs the newest op's wall clock without loading the op log (D40).
- **Member merge (edge 26).** Still detection only. Needs a design that
  reconciles claimed identities (AUDIT T8) before any op is added.
- **"Import updates" from inside a group** uses the system file picker; it is
  covered by ViewModel tests but not by the on-device flows, which use
  "open with Dibs" instead.

## Next: P3, in this order

Work the `open` and `partial` entries in `docs/requirements.yaml`. Already
closed early: several payers, member detail, share who owes whom, the
duplicate-repayment hint, Undo and haptics, the version in About. What is left,
with the ones users will notice first:

1. Delete group with typed confirmation and a pre-delete backup (FR-G4,
   edge 18). Needs a decision first: deletion is local to one phone, and an
   update file from another member would bring the group back.
2. Storage usage and "clear receipt images" (FR-T5); a tappable source link
   and credits in About (FR-T6).
3. Language, default split mode and default group settings (FR-T1, FR-T2).
4. Date-range and amount filters in search (FR-E9); group colour (FR-G1).
5. Storage-full and migration-failure paths with injected faults (edges 63, 64).
6. 100-member group: virtualized pickers and the warning above 50 (edge 17).
7. On-device flows for import, CSV, backup and restore, the recurring
   reminder, and app lock, which need the system file picker and a lock screen.
8. Quality passes with evidence: TalkBack, RTL, tablet, API 26; the foss
   flavor on device.
9. Screenshot tests for every screen; the on-device flows in CI.

Each item: failing test first, `docs/requirements.yaml` updated in the same
commit, `./gradlew check` green, and the matching on-device flow added to
`scripts/e2e` where the flow crosses a screen boundary.
