# CLAUDE.md

Conventions for this repository. These persist across sessions and override
any default behaviour to the contrary.

## Prose style

No em dashes (U+2014) or en dashes (U+2013) anywhere in repository text. This
covers README files, documentation, code comments, string literals, commit
messages, and pull request bodies.

Use a period, a comma, or parentheses instead:

```
bad:   The floor is 1.5 <U+2014> the sweep does not support 2.2.
good:  The floor is 1.5. The sweep does not support 2.2.
good:  The floor is 1.5 (the sweep does not support 2.2).

bad:   Cadence band 1.2<U+2013>2.6 Hz
good:  Cadence band 1.2-2.6 Hz
```

The `bad:` lines above spell the offending characters as `<U+2014>` and
`<U+2013>` on purpose, so that this file does not itself trip the check
below.

Hyphens in compound words and numeric ranges are fine. ASCII `--` is fine as a
table placeholder.

Check before committing:

```
grep -rnP '(*UTF)[\x{2013}\x{2014}]' --include='*.md' --include='*.kt' .
```

The `(*UTF)` prefix is required. Without it GNU grep rejects code points above
U+00FF with "character code point value in \x{} or \o{} is too large".

## Branch naming

Use `patch/<topic>` or `feat/<topic>`. Lowercase, hyphenated, under 30
characters total.

```
good:  patch/gait-recalibration
good:  feat/movement-gate
bad:   claude/eloquent-archimedes-cw2814
```

Do not use a `claude/` prefix or generated codenames.

## Commit messages

Conventional-commit subject in the imperative mood, under 72 characters.

```
good:  fix: derive IIR alpha from measured sample interval
good:  feat: add four-segment stall latency probe
bad:   Fixed the alpha calculation and also added some tests
```

Do not include:

- a "Generated with Claude Code" footer
- a `Co-Authored-By: Claude` trailer
- emoji

The body is plain prose under the same style rules as above. Explain why, not
just what.

## History

Do not rewrite history that has already been pushed. No amend, squash, or
rebase of published commits. Corrections go in a new commit.

Earlier commits on this repository predate these conventions and are left as
they are.

## Build and verification

The Android toolchain is unreachable from the usual session environment
(`dl.google.com` is denied by egress policy), so `./gradlew assembleDebug`
cannot be run there. The Android-free subset is still verifiable:

```
cd tools/pure-verify && ./gradlew test --rerun-tasks
```

That is a standalone Gradle build with its own wrapper, not an included
module. `./gradlew :tools:pure-verify` from the repo root will not resolve.

Never report the Android layer as building or passing tests unless a real
compile has actually run. Say plainly what was verified and what was not.
