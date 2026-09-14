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
tools/check-all.sh
```

That runs the dash check, the encoding check, the format-string check and the
pure suite. The individual scripts still work on their own.

The script runs `grep -rn` for the two characters literally, with no `-P`, no
code points and no `(*UTF)`, so it works on GNU and BSD grep. It covers `*.md`,
`*.kt` and `*.xml`, and exits non-zero on a hit.

The command lives in a script rather than inline here because writing the
characters into this file would make it trip its own check.

`*.xml` is in scope because display copy lives in `res/values/strings.xml`. It
was out of scope once, and an em dash sat in shipped copy unnoticed.

## Source encoding and format strings

`tools/check-encoding.sh` and `tools/check-format-strings.py` exist because
each of the faults they catch has already reached this repository:

- A UTF-8 BOM was added to `accessibility_service_config.xml` by a commit whose
  message described fixing an XML parse error. A BOM before an XML declaration
  is itself an aapt2 failure mode on some versions.
- `LauncherActivity.kt` carried two raw `0x95` bytes, a CP1252 bullet, so the
  file was not valid UTF-8 at all. `kotlinc` reads sources as UTF-8, and a
  stray high byte is a build failure or a silently mangled string literal.
- Four strings in `strings.xml` used `%1` and `%2`, which are not format
  specifiers. `String.format` throws `UnknownFormatConversionException` on
  them. They were latent only because the renderer hardcoded its literals
  instead of reading the resources.

Sources are UTF-8 with no BOM. Every conversion is `%n$s`. A literal percent is
`%%`. `formatted="false"` and positional arguments are mutually exclusive.

The encoding check covers **every tracked file under `app/src` and `tools`**,
not just resource XML, because the file that was actually damaged was Kotlin.
It runs over `git ls-files` rather than `find`, so it skips Gradle's build
output and checks exactly the set that reaches another machine.

It fails on four things: a file that is not valid UTF-8, a BOM, a U+FFFD, and
a CP1252 round-trip signature. The last one matters separately: mojibake is
still *valid* UTF-8, so a decoder-based check passes it and only a pattern
catches it.

The script is kept pure ASCII and builds its search patterns with `printf`
octal escapes. An earlier version wrote U+FFFD literally and flagged itself.

## The profile is asserted, not just documented

`AccessibilityConfigTest` reads `accessibility_service_config.xml` and
`AndroidManifest.xml` as text and asserts the load-bearing attributes, because
two of them were silently reverted once inside a commit about something else.
Neither revert broke a visible feature, which is why neither was noticed:
dropping `dev.molasses` from `packageNames` stops the session ever closing, and
the ladder just goes quietly wrong.

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

**Always err toward more friction.** Every deadline in this app is either a
restriction or a relief, and the two want opposite clamps. Getting the
direction wrong hands the user a bypass, so the direction is named at every
call site rather than inferred.

### Restrictions: cycle accumulation, `LockRegistry`

Credit `min(wallDelta, elapsedDelta)`, floored at zero.
`ClockTamperClamp.Direction.RESTRICTION`.

The attack is a **forward** clock jump. Set the date thirty days ahead and a
bare wall-clock deadline expires a thirty day lock instantly. `max()` would
credit the jump. `min()` takes the monotonic delta, which did not move, so the
lock holds.

A restriction has to span reboots to be worth anything, so it carries a
wall-clock stamp and accepts one residual hole: wind the clock forward, then
reboot. That costs a reboot rather than a tap, and it is visible in the ledger
as a `CLOCK_WARP` row.

### Relief: `PauseWindow`, `$ allow`, any friction suspension

Credit `max(wallDelta, elapsedDelta)`, floored at zero.
`ClockTamperClamp.Direction.RELIEF`.

The attack is a **backward** wind. Winding the clock back an hour makes the
wall delta small or negative, and `min()` credits nothing, so a fifteen minute
pause stays open forever. `max()` still takes the monotonic delta and expires
it on schedule.

Relief must not trust a wall clock across a reboot: there is no reading that
survives the boot to bound it. `Verdict.bootChanged` exists so a relief caller
can treat a boot as expiry. `PauseWindow` goes further and drops the wall clock
entirely, measuring on `elapsedRealtime` alone and expiring across a reboot.
That is simpler and airtight, and it is the right default for any relief short
enough that a reboot outlasts it.

### Both of these are bugs that shipped here

A pause built on the restriction clamp was held open indefinitely by winding
the clock back an hour. `PauseWindowTest` caught it. The mirror image, a lock
built on the relief clamp, is pinned by
`LockRegistryTest.a forward clock jump does not shorten a lock`.

`ClampDirectionTest` asserts the general invariant: for any gap, relief credits
at least as much as restriction. So when in doubt, restriction is the safe
default, and it is the default parameter value for that reason.

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
