# Contributing to Dibs

Thanks for considering it. This page is everything you need to go from clone to
merged PR.

## Setup

1. Install JDK 17 and the Android SDK (Android Studio does both).
2. `git clone` and open the project — no other setup. Debug builds use the
   checked-in dummy keystore.

## Build and test

```bash
./gradlew :app:assembleDebug   # build the app
./gradlew check                # all tests + ktlint + detekt + lint + purity checks
./gradlew :core:domain:test    # domain tests only — pure JVM, no emulator
```

## Ground rules

The project constitution is [CLAUDE.md](CLAUDE.md); the invariants there are
non-negotiable. The ones contributors trip over most:

- **Money is `Long` minor units.** Any `Double`/`Float` in a money path will be
  rejected, however innocent it looks.
- **`:core:domain` is pure Kotlin/JVM.** The build fails if it gains an Android
  dependency — that's enforced by the `verifyPureJvm` task, not by review.
- **Determinism.** No iteration-order or wall-clock dependence in domain logic;
  sort by stable IDs.
- **Tests first** for anything in `:core:domain`, named `` `given X when Y then Z` ``.
- Strings go in `strings.xml` from the first draft. No hardcoded UI text.

## Pull requests

- One logical change per PR; keep them small.
- Conventional Commits (`feat:`, `fix:`, `test:`, `build:`, `docs:`, `refactor:`).
- `./gradlew check` must pass; CI runs it on every PR, including from forks.
- Fill in the PR template checklist honestly — it mirrors review.

## Out of scope

Servers, accounts, payment processing, ads, analytics, and cryptocurrency are
permanently out of scope. PRs adding them will be closed with a link to
[CLAUDE.md](CLAUDE.md), with appreciation and without exception.

## Reporting security issues

Privately, please — see [SECURITY.md](SECURITY.md).
