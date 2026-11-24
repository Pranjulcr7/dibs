# Privacy

**Dibs collects nothing.**

- No account, no sign-up, no email, no phone number.
- No analytics, no telemetry, no crash reporting that phones home, no ads, no
  tracking of any kind. There is no server to send anything to.
- Your expense data lives in an encrypted database on your phone.
- Receipt photos are stored in app-private storage and never leave your device.
- Receipt scanning runs entirely on-device.
- Backups are files you create, encrypt with your passphrase, and store wherever
  you choose. Dibs never sees them.
- Sync (optional, off by default) exchanges end-to-end encrypted data directly
  between group members' devices over channels you choose.

The app works fully in airplane mode. That is not a fallback — it is the design.
No build declares the Android `INTERNET` permission, so the operating system
itself blocks network access; the build fails if that ever changes.

The `full` build uses Google's ML Kit to read receipts on the device. ML Kit
ships with a Google telemetry component; because the app has no network
permission, that component cannot send anything. The `foss` build contains no
Google code at all. See SECURITY.md for a correction about earlier source
builds.

If you find any network call in the source that contradicts this document,
please report it as a critical bug.
