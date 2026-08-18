## What

<!-- What does this PR change, and why? Link the issue if one exists. -->

## Checklist

- [ ] `./gradlew check` passes locally
- [ ] Tests added or updated for the change (domain logic requires tests *first*)
- [ ] No `Double`/`Float` anywhere in a money path (CLAUDE.md invariant 1)
- [ ] No Android imports in `:core:domain` (invariant 4)
- [ ] User-facing strings live in `strings.xml`, not hardcoded
- [ ] New screens/components have light, dark, and large-font previews
- [ ] Accessibility: content descriptions present, touch targets ≥ 48 dp
- [ ] Commit messages follow Conventional Commits
