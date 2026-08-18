# Dibs — Open source setup

Everything needed to make this a project other people can actually contribute to,
in the order it matters.

---

## 1. License

**Apache-2.0.** Not MIT.

Both are permissive and both are fine, but Apache-2.0 includes an express patent
grant and a contributor patent-retaliation clause. For a project touching
cryptography and ML that you'd like companies to feel safe adopting, that grant
removes a category of legal hesitation MIT leaves open. It is also the default in
the Android and Kotlin ecosystem, so it will surprise nobody.

Avoid the Fair Source License and the various "source available" licenses. The
closest prior art in this space (PeerSplit) uses Fair Source, and being *actually*
open source is a real differentiator you should not give away.

Add `LICENSE` at the repo root and an SPDX header in each source file.

## 2. Repository name and identity

- Repo: `dibs` under your personal account, or a `dibs-app` org if you want room
  to add a website and docs repos later. An org costs nothing and looks more
  durable to contributors — I'd start there.
- Description: "Split expenses with friends. Everything stays on your phone."
- Topics: `android`, `kotlin`, `jetpack-compose`, `local-first`, `crdt`,
  `privacy`, `expense-tracker`, `splitwise-alternative`, `on-device-ml`,
  `offline-first`.
- Enable Issues, Discussions, and Projects. Disable the Wiki (docs belong in the
  repo, versioned with the code).

## 3. Files to create at initialization

| File | Purpose |
|---|---|
| `README.md` | See §4 |
| `LICENSE` | Apache-2.0 full text |
| `CONTRIBUTING.md` | Setup, build, test, PR expectations, commit convention |
| `CODE_OF_CONDUCT.md` | Contributor Covenant 2.1, with a real contact address |
| `SECURITY.md` | Threat model + private vulnerability reporting |
| `ARCHITECTURE.md` | Module map, data flow, why the op-log design |
| `PRIVACY.md` | What Dibs collects: nothing. State it plainly |
| `CHANGELOG.md` | Keep a Changelog format |
| `.github/ISSUE_TEMPLATE/` | bug, feature, question |
| `.github/PULL_REQUEST_TEMPLATE.md` | Checklist incl. tests and a11y |
| `.github/workflows/ci.yml` | Build, test, lint on every PR |
| `.github/workflows/release.yml` | Tagged builds, signed, artifacts attached |
| `.github/dependabot.yml` | Weekly Gradle and Actions updates |
| `.gitignore` | Android + IDEA + keystores + `model/data/` |
| `.editorconfig` | Matching ktlint |

## 4. README structure

The README is your recruiting document. Order matters:

1. Name, one-line description, badges (CI, license, latest release).
2. **Three screenshots, above everything else.** People decide in two seconds.
3. "Why Dibs" — three bullets: no account, no server, no ads.
4. Install: Play Store link, F-Droid link, direct APK.
5. Features list.
6. "How it works" — a short, plain-language explanation of local-first plus one
   architecture diagram. This is the part that makes engineers star the repo.
7. Build from source: exact commands, JDK version, no assumed setup.
8. Contributing pointer.
9. License.

Write it for two audiences at once: the user who wants an app, and the engineer
evaluating whether you can build things. Both are reading.

## 5. Making it contributable

The single biggest predictor of outside contributions is whether a stranger can
get from `git clone` to a running app in under ten minutes. Protect that
ruthlessly:

- `./gradlew assembleDebug` must work on a clean machine with only JDK 17 and
  Android SDK installed. No manual key generation, no `local.properties` editing,
  no "ask the maintainer for a file".
- Debug builds use a checked-in dummy signing config.
- The optional VLM must not be required to build or run.
- CI must run on PRs from forks.

Then:

- Label 10–15 issues `good first issue` **at launch**, not later. Real ones:
  new currency formatting cases, additional split modes, translations, empty-state
  copy, a specific edge case from SPEC §6 that lacks a test.
- Label another 10 `help wanted` for larger pieces.
- Set up a GitHub Project board with Backlog / In Progress / Review / Done so the
  project reads as alive.
- Respond to every issue within 48 hours, even if the response is "not now, but
  thank you." Unanswered issues kill contributor momentum faster than bugs do.

## 6. Translations

Use Weblate's free hosting for libre projects. Externalizing strings from day
one (a `CLAUDE.md` invariant) is what makes this possible later without a painful
refactor. Translations are the highest-volume, lowest-friction contribution type
and a great on-ramp for first-time contributors.

## 7. Distribution

- **F-Droid** — the natural home for a privacy-focused FOSS Android app, and its
  audience is exactly your early-adopter profile. Requires a reproducible build
  and no proprietary dependencies. Note: **ML Kit is proprietary**, so plan a
  build flavor that swaps in an FOSS OCR path (Tesseract or a bundled ONNX model)
  for the F-Droid variant. Decide this early — retrofitting it is painful.
- **Play Store** — $25 one-time, reaches everyone else. The Data Safety form is
  refreshingly easy to fill in when the honest answer to every question is "no
  data collected."
- **GitHub Releases** — signed APKs attached to every tag.

## 8. Launch sequence

Ship first, announce second. A "coming soon" post with no APK converts nobody.

Once M4 is done (import works, so people can actually migrate):

1. Post to r/androidapps, r/fossdroid, r/opensource, r/privacy.
2. Show HN: "Dibs — a Splitwise alternative that has no server." Lead with the
   architecture, not the features; HN cares about the how.
3. Product Hunt.
4. A short write-up on your own blog about the op-log CRDT design and why you
   skipped Automerge. This is the piece that generates the most durable interest
   and the one most likely to be read by someone technical evaluating you.

## 9. Sustainability

- No donation buttons at launch — they change the dynamic and there's nothing to
  sustain yet.
- Add `FUNDING.yml` only if hosting costs ever appear. By design, they shouldn't.
- Be explicit in the README about your maintenance capacity. "This is a side
  project; I review PRs on weekends" sets expectations honestly and prevents the
  resentment cycle that kills most solo-maintained repos.
