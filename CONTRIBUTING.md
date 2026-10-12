# Contributing

## Ground rules

1. **`main` is always green and always demo-able.** Nobody pushes to it directly.
2. **One owner per area, reviews across areas.** See `docs/architecture.md`.
3. **`core/model` is a contract.** Changing a record there needs approval from *both* other teammates, because it breaks their code.
4. **Work against fixtures, not each other.** If you need another area's output before it exists, fake it with a hand-built `FloorGraph` or a recorded trace in `data/fixtures/`.

## Workflow

```bash
git switch main && git pull
git switch -c feat/parser-skeleton      # branch per issue
# ...commit...
git push -u origin feat/parser-skeleton # then open a PR
```

**Branch names:** `feat/…`, `fix/…`, `eval/…`, `docs/…`, `chore/…`

**Commit messages** ([Conventional Commits](https://www.conventionalcommits.org)), present tense, ≤ 72 chars:

```
feat(tracking): resample particles with systematic resampling
fix(mapparse): close door gaps before flood-fill
test(routing): cover unreachable destination
```

**Pull requests**

- Keep them small (aim for under ~400 changed lines). Split big features.
- Link the issue with `Closes #12`.
- At least one approving review, and CI must pass.
- **Squash merge**, then delete the branch.
- Reviewer turnaround target: within 24 hours.

## Code style

- Kotlin, Android Studio style, enforced by ktlint: `./gradlew ktlintCheck` (`ktlintFormat` fixes most issues).
- Keep algorithm code free of Android imports where possible (`core/model`, `routing`, the filter maths) so it unit-tests on a laptop.
- No magic numbers in tracking: thresholds belong in `TrackingConfig`.
- Sensor and image work never runs on the UI thread.
- `NavigationSession` is the only writer of position and route.

## Data

- Small fixtures (one map photo, one 2-minute trace) → `data/fixtures/`, tracked with Git LFS.
- Full datasets → shared drive (see `data/README.md`). Never commit `data/raw/`.

## Decisions

Anything that changes an interface, a dependency or an algorithm choice gets a short note in `docs/decisions/NNNN-title.md` (context, decision, consequences). This doubles as material for the final report.

## Never commit

`local.properties`, keystores, API keys, large videos, build output.
