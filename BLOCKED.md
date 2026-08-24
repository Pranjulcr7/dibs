# BLOCKED

Work that cannot proceed without an external input, per the working style
(record it here rather than working around it). Remove entries as they clear.

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
