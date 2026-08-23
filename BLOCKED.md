# BLOCKED

Work that cannot proceed without an external input, per the working style
(record it here rather than working around it). Remove entries as they clear.

## Blocked on the device being idle (it is the user's daily phone)

Most of the 2026-08-21 list cleared in the 2026-08-23 session (NFR-2/3/4
measured, scan→editor verified, R8 release smoke-tested — see PROGRESS.md).
Remaining, both interrupted by incoming calls:

1. **ML Kit parse under R8.** The release build ran everything except an
   actual "Read receipt" OCR pass. Steps: open Big Import → menu → Scan
   receipt → Pick from gallery → `receipt.jpg` (already on the device in
   Downloads and Pictures) → Read receipt; a correct review screen clears
   this item.
2. **Restore the committed debug build.** The phone currently runs the
   release APK signed with the checked-in debug keystore plus (harmless,
   reverted-in-repo) timing logs are NOT in it — it is the clean release.
   Still, day-to-day dogfooding should be on the debug build until release
   signing is real: `adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
   (already built at HEAD).

## Blocked on hardware we do not have

- **NFR-1 and NFR-3 on a mid-range 2022 device.** The S24 Ultra numbers have
  margin (519–635 ms cold start), but the SPEC names mid-range hardware;
  measure before 1.0.

## Blocked on project decisions (not device)

- **M6 (sync) and M7 (polish/release)** — explicitly out of scope until the
  kickoff instruction changes; M6 also awaits the relay/transport decision.
- **F-Droid FOSS flavor** (no ML Kit, `NoOpReceiptParser`) — decided as the
  likely answer to NFR-6 (22 MB with the bundled OCR model vs the 20 MB
  target); needs a go-ahead before adding the flavor split (ISSUES.md #23).
