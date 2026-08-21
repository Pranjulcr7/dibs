# BLOCKED

Work that cannot proceed without an external input, per the working style
(record it here rather than working around it). Remove entries as they clear.

## Blocked on the physical device reconnecting (RZCX11TAQ4N)

The phone was connected on 2026-08-21 long enough to install, run T7 (which
caught and verified the O(n²) import fix), and measure NFR-1/6/7 — then
dropped off adb mid-scan-flow. Remaining, in order:

1. **NFR-4 (ML Kit OCR pass < 1.5 s).** A synthetic receipt is already at
   `/sdcard/Pictures/receipt.jpg` and the app sits one tap from
   "Pick from gallery" in the scan flow. Time gallery-pick → review screen.
2. **NFR-3 (balance recomputation, 5k group, < 100 ms).** Needs a temporary
   (uncommitted) timing log around the fold in `LedgerRepository.loadLocked`,
   reinstall without `pm clear`, force-stop, reopen the "Big Import" group,
   read logcat. The tab switches render within a ~1.5 s screenshot poll, but
   that is an upper bound, not a measurement of the fold itself.
3. **NFR-2 (add expense ≤ 3 taps, < 5 s).** Walk FAB → amount → save on the
   equal-split default and time it.
4. **Scan review → editor hand-off on hardware** (the JVM tests cover the
   logic; the camera capture path itself has only been seen to preview).
5. **R8 release build smoke test.** `app-arm64-v8a-release-unsigned.apk`
   builds but has never executed; the proguard keeps in
   `app/proguard-rules.pro` (kotlinx.serialization, SQLCipher, BouncyCastle/
   eddsa) are unverified at runtime.
6. **NFR-1 on a mid-range 2022 device.** The S24 Ultra numbers (519–635 ms)
   have margin against the 800 ms budget, but the NFR names mid-range
   hardware; measure there before 1.0.

## Blocked on project decisions (not device)

- **M6 (sync) and M7 (polish/release)** — explicitly out of scope until the
  kickoff instruction changes; M6 also awaits the relay/transport decision.
- **F-Droid FOSS flavor** (no ML Kit, `NoOpReceiptParser`) — decided as the
  likely answer to NFR-6 (22 MB with the bundled OCR model vs the 20 MB
  target); needs a go-ahead before adding the flavor split.
