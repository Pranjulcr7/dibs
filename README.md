# Dibs

**Split expenses with friends. Everything stays on your phone.**

[![CI](https://github.com/dibs-app/dibs/actions/workflows/ci.yml/badge.svg)](https://github.com/dibs-app/dibs/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

> 🚧 **Under construction.** Dibs is being built milestone by milestone and is not
> yet installable. Screenshots will land here the moment there is a screen worth
> showing. See [PROGRESS.md](PROGRESS.md) for where things stand.

## Why Dibs

- **No account.** No email, no phone number, no sign-up. Open the app, add an expense.
- **No server.** Your data lives on your phone and syncs directly with your
  friends' phones when you choose to. There is nothing to shut down, breach, or subpoena.
- **No ads, no caps, no upsell.** Free and open source. Dibs collects nothing — see
  [PRIVACY.md](PRIVACY.md).

## Features (planned for v1)

- Groups and one-off splits, with balances you can always drill into
- Six split modes: equal, exact amounts, percentages, shares, +/- adjustment, and
  itemized with proportional tax and tip
- Multiple payers per expense, partial settlements, settle-up suggestions
- Receipt scanning — fully on-device, with a mandatory review screen
- Splitwise CSV import, CSV export, encrypted backups
- Serverless sync via QR invite and encrypted bundles over any channel you already use

## How it works

Dibs is local-first. Every change is a small immutable fact appended to a log on
your device. Syncing with friends is just exchanging encrypted copies of those
facts — merging is a set union, so devices that have seen the same facts always
agree, byte for byte, with no server to arbitrate. Money is integer minor units
end to end; splits are allocated with the largest-remainder method so every cent
is accounted for and every device computes the identical answer.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the module map and the op-log design.

## Build from source

Requirements: JDK 17 and the Android SDK. Nothing else — no key generation, no
`local.properties` editing.

```bash
git clone https://github.com/dibs-app/dibs.git
cd dibs
./gradlew :app:assembleDebug
```

Run the tests (the domain core needs no emulator):

```bash
./gradlew check
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Translations, currency edge cases, and
edge-case tests from [SPEC.md](SPEC.md) §6 are great first contributions.

This is a side project; issues and PRs are reviewed on a best-effort basis —
expect responses within a couple of days, not hours.

## License

[Apache-2.0](LICENSE)
