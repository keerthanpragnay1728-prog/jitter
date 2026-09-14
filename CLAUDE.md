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

## Display copy lives in strings.xml

No user-facing string is hardcoded in Kotlin. All copy goes in
`app/src/main/res/values/strings.xml` and is read with `stringResource()`.
Where the string depends on state, map the state to a `@StringRes Int` in a
plain function and resolve it at the call site, so the mapping stays pure.

This is the Android convention and the only thing that makes the app
translatable. It also keeps the prose rules checkable. With copy inline, a grep
over `*.kt` cannot tell a user-facing sentence from a code comment, and the two
do not have the same rules. With copy in resources, a `*.kt` hit is always a
comment.

Commands, field labels and format templates that are shown on screen but must
not be reworded carry `translatable="false"`.

Format arguments are all `%n$s`. A `%d` given a Double throws
`IllegalFormatConversionException` at runtime, and several of these strings
take Double thresholds. Surplus arguments are ignored by `String.format`, so
passing one unconditionally to a family of strings where only some use it is
safe.

Fixed-width alignment belongs in Kotlin, not in the resource. aapt collapses
runs of whitespace inside a string value unless the whole value is quoted.

Check before committing:

```
tools/check-dashes.sh
```

The script runs `grep -rn` for the two characters literally, with no `-P`, no
code points and no `(*UTF)`, so it works on GNU and BSD grep. It covers `*.md`,
`*.kt` and `*.xml`, and exits non-zero on a hit.

The command lives in a script rather than inline here because writing the
characters into this file would make it trip its own check.

`*.xml` is in scope because display copy lives in `res/values/strings.xml`. It
was out of scope once, and an em dash sat in shipped copy unnoticed.

## Service model

No `FOREGROUND_SERVICE` of any type, and no self-started background services.
System-bound services (`AccessibilityService`, `NotificationListenerService`)
are permitted because the OS requires them to be discrete classes and controls
their binding lifecycle.

## The accessibility profile is user-visible

Banking and UPI apps read the declared `AccessibilityServiceInfo` of every
enabled service and refuse to run, or warn loudly, based on what they find.
Three attributes in `accessibility_service_config.xml` are therefore load
bearing and are not to be changed casually:

- `android:packageNames` stays scoped to the monitored targets plus
  `dev.molasses`.
- `android:canRetrieveWindowContent` stays `false`.
- `android:accessibilityFlags` does not include `flagRetrieveInteractiveWindows`.

Removing the last one means `getWindows()` returns an empty list. If a feature
needs to know about another window, route it through `UsageStatsManager` or
drop the feature. Do not add the flag back.

Jitter must never draw any overlay over a package in
`core/safety/SensitivePackages`. An overlay sets `FLAG_WINDOW_IS_OBSCURED` on
that app's touches and a hardened payment app is entitled to refuse the
transaction.

## Which clock a deadline is measured on

Measure with the clock whose failure mode costs the user friction, never the
one whose failure mode grants it.

The cycle window uses `ClockTamperClamp`, which credits
`min(wall delta, elapsed delta)`. Under-crediting there means the cycle takes
longer to reset, so the user gets more friction.

A pause or a lockout is the opposite: under-crediting keeps it open longer,
which means less friction. Those are measured on `elapsedRealtime` alone and
expire outright across a reboot. See `core/safety/PauseWindow`.

Reusing the cycle clamp for a pause is a real bug that a test caught here
once, not a hypothetical.

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
