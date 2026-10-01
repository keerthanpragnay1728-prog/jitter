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

### Stubs are where the longest lines collect

A refusal is shown on a motionless face for three seconds, so it has to be
readable in three seconds. An audit for lines over twelve words found the worst
offenders were all the same kind of string: the reason a stub gives for not
working.

That is not a coincidence, it is the shape of the problem. A command that
cannot run has to explain an absent subsystem, and an absent subsystem takes a
paragraph. "Allowances are not built yet. A lease has to park the penalty
ratchet, not just hide the gate" is eighteen words and every one of them is
load bearing, because the reader has no other source for any of it. A command
that *can* run only has to say what went wrong with this attempt, which is a
clause.

So purge a stub promptly rather than letting it sit behind a dimmed manual row.
Deleting `log`, `rem` and `allow` removed the three longest refusals in the app
without a single word being rewritten, and what was left needed one edit. The
copy problem and the stub problem were the same problem.

If a stub genuinely has to stay for a while, its reason is the one string worth
holding to twelve words even at the cost of precision, because it is the one
that will be read most and acted on least.

Check before committing:

```
tools/check-all.sh
```

That runs the dash check, the encoding check, the format-string check, the
colour check, the structure check and the pure suite. The individual scripts
still work on their own.

It ends by printing `check-all: PASS` or `check-all: FAIL`. Read that line
rather than the exit code, because the exit code is the first thing a pipe
takes away: `tools/check-all.sh | tail -4` reports whether `tail` succeeded.
A failing suite has already been committed and pushed for exactly that reason.

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

Sources are UTF-8 with no BOM, and LF. Every conversion is `%n$s`. A literal
percent is `%%`. `formatted="false"` and positional arguments are mutually
exclusive.

LF is pinned by `.gitattributes` (`* text=auto eol=lf`), not by habit. A
Windows checkout with `core.autocrlf=true` made every source CRLF, and two
wiring tests that match across a line break failed on correct code with
messages that read as regressions. `LineEndingsTest` now fails first, naming
the cause and the fix. It checks only what git tracks (`git ls-files`) and
skips only what git calls `-text` (`git check-attr`): an untracked
`local.properties` or diagnostic dump is not the repository's to govern.

The encoding check covers **every tracked file under `app/src`, `tools` and
`fastlane`**, not just resource XML, because the file that was actually
damaged was Kotlin. Binary files are allowed by path, never by extension:
`res/raw`, the store icon, and PNGs in the store screenshot directory.
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
dropping our own package from `packageNames` stops the session ever closing, and
the ladder just goes quietly wrong.

## A guard with no caller is worse than no guard

This has now come up four times, in four unrelated parts of the app, so it is
written down once rather than re-argued.

1. The window-id collision guard could never match a real window once
   `flagRetrieveInteractiveWindows` was dropped, because every event arrived
   with an id of `-1`. **Deleted, not narrowed.**
2. `IgnoreReason.OWN_WINDOW` outlived its producer and was kept for exactly one
   release, because a running build could still emit it and an in-memory tally
   would have been unreadable without it. **Kept with a stated expiry**, and
   deleted at 1.0.3, later than stated.
3. The telephony secondary could not register on any shipped build, because
   `READ_PHONE_STATE` is held out of the manifest by test. **Deleted, not
   guarded**, after one commit that guarded it and was the wrong answer.
4. `CommandSpec.isRelief` lost its only user when `$ allow` was deleted.
   **Kept, and its test rewritten to drive it with a synthetic spec** rather
   than through a verb.

The rule the four cases share:

**A guard nothing can reach is not neutral. The next reader takes its presence
as cover for a case that is actually being carried by something else, or by
nothing at all.** That is what made the window-id guard expensive: it looked
like the collision check, so nobody went looking for the package-name check
that was doing the whole job alone.

So when a caller disappears, decide which of three things the guard is:

- **Dead.** Nothing can reach it under any configuration this app ships.
  Delete it. Git has it. A narrower version of a guard that cannot fire is
  still a guard that cannot fire.
- **Waiting.** The mechanism is general and the next user is a named, intended
  feature rather than a hope. Keep it, and **keep it tested against the
  mechanism rather than against the departed caller** (case 4). A test that
  went away with its verb leaves a live branch uncovered, which is the same
  invisibility one layer down.
- **Expiring.** Something already in the field can still reach it. Keep it,
  say in the doc when it goes, and go then (case 2).

The distinction between dead and waiting is about the device, not about the
notes. Relief is designed in this file, at length, with its own clamp
direction, and that did not make `$ allow` anything other than a verb that
always refused. Design on paper is not a caller.

The pinned-stall and forced-probability debug controls were deleted in 380c3bc because nothing could write them; segment D calibration on a new device needs them rebuilt, with a control that writes them, in the debug source set.

## Apply it where it changes, persist behind it

**A value the service both changes and reads must be applied where it is
changed, with persistence behind it. A store round-trip is durability, not the
write.**

This is now the second instance, in two unrelated subsystems, so it is written
down rather than re-derived.

1. **`fontScale` captured as a value.** The overlay managers are constructed
   once when the service connects and shown for hours afterwards, so a
   captured `Float` froze whatever the setting was at boot. Fixed by holding
   it as `() -> Float` and invoking it at composition. `FontScaleWiringTest`
   reads all five call sites as text.
2. **A lease read before its write landed.** `grantLease` told the engine
   synchronously and the lease registry through a DataStore write, and
   `maybeLaunchGate` reads both in one breath. Between the two it saw a
   package with no lease and one more lease taken, so it gated again one
   escalation step higher, and every lease cost two rungs. The mirror case is
   cycle rollover, where the engine clears `leasesTaken` in its own call stack
   and the registry was cleared through the store: there the gap skips the
   first gate of the new cycle, which is the relief direction and the worse
   one. Assigning in memory and persisting behind it fixed neither on the
   device, for the reason below, and the registry is now owned by the service
   and seeded from the store once at connect. `LeaseGrantVisibilityTest`
   covers all three.

Both compiled. Both ran. Both had a comment next to them asserting they were
fine: "read at the moment this window is shown" on a parameter that was not
one, and "one stale read costs at most one extra gate, which is the direction
that fails safely" on a read whose extra gate ended in an extra lease taken,
which the countdown counts. **A comment claiming a hazard is handled is not
evidence that it is.** That is the same failure as a guard with no caller, one
section up: the next reader takes the note as cover.

### The half that was missing, and cost a second wrong fix

The first version of this section said the two copies could not disagree,
because `LeaseManager` is immutable and its `grant` refuses to lengthen a live
lease, so the observer's later assignment was a no-op. Both halves of that are
true and the conclusion was still wrong, because it only holds for the emission
carrying that write.

`FrictionEngine.publish()` writes a snapshot of its own, `onLeaseGranted` calls
it, and its write is issued from a channel receiver that is already parked while
the lease write still has a coroutine to schedule. So the engine's write landed
first, its emission carried the lease list as it stood on disk, and the observer
replaced the whole registry with a copy that predated the grant. **The grant
erased itself, through the engine, on its own call stack**, about sixty
milliseconds before the gate that read it.

Idempotency could not save it, because the observer was not applying a grant. It
was assigning a registry wholesale.

So the rule needs its second half:

**Persisting behind an in-memory write is only safe when the persisted copy is
not also read back as the source of truth. A store with more than one writer
emits snapshots that are stale with respect to each other, and a wholesale
assignment from any of them loses the newest write at random.**

The fix is ownership, not ordering. Whoever writes the value in memory owns it;
the store persists it and is read exactly once, at startup, to seed it. Any
ordering trick is a bet on which of two writes reaches the disk first, and that
bet is being placed inside a coroutine dispatcher.

Ordering is worth checking for a different reason, though: it tells you the
value has a second writer at all. The tell here was that `publish()` is called
by the very function whose effect was disappearing.

### Where the startup seed has to go, and why it is load bearing

Reseeding on every emission is also what healed anything that went wrong, and
that safety net goes with it. The seed becomes the only chance to load the
persisted value, so its position is part of the fix rather than a detail of it.

In the service that means after reconciliation, which may write, and before
`ready` is set, which opens the event gate. A lease that survived a process
death has to be in hand before the first event is accepted, or the first launch
after a restart gates an app the user already paid for, which is exactly what
`LeaseManager`'s doc promises cannot happen.

### Delete the read path once it is not the truth

`CycleStateStore.leases`, a `Flow<LeaseManager>` off the store, had no consumer
and was deleted with this change. Leaving it would have been the sixth
guard with no caller in this file, and the worst placed of them: it is the exact
API that invites the next person to read leases off the store again, which is
the thing this whole section exists to stop.

### Not a timer, and not a grace window

The tempting shape is to have the reader ignore the hazard for a few hundred
milliseconds after the write starts. Do not. It papers over an ordering rather
than fixing it, the interval is a guess about disk latency, and for anything
that gates it is a window in which the gate declines to fire, which is a
bypass. Always err toward more friction.

### Test it as text as well as in the pure layer

A pure test can say the pieces answer correctly. It cannot say the service
made the assignment, because the service is one of the 38 files nothing here
compiles. So these are asserted the way `FontScaleWiringTest` asserts its call
sites: read the source, slice out the function, and check both that the
assignment exists **and that it is before the `scope.launch`**. The second
half is the one that matters. An assignment moved inside the coroutine would
compile, would read like the same fix, and would restore the bug with a shorter
window, and no pure test can see it.

The same applies to the two halves of ownership. `LeaseGrantVisibilityTest`
asserts that the service seeds the registry between reconciliation and `ready`,
and that the settings observer does not assign it at all. A reader put back
into the observer would look like a harmless restoration of a safety net and
would reintroduce this exact bug.

## The stored target list is not the tracked set

**`settingsRepository.targets` is the raw stored list and is never the answer
to "what is tracked". Every reader goes through `TargetScope.resolve`. A reader
that does not is wrong on a fresh install and wrong after every edit.**

`resolve(stored, defaults)` falls back to the defaults when the stored list is
empty, and that fallback is not a corner case: it is the state of every device
between first launch and the first time the user edits the list. The service
resolves, in `observeSettings`, and so is tracking five apps while the raw flow
reports none.

This has now produced the same bug three times, in three different readers,
with three different symptoms, which is why it is a rule rather than a fix:

1. The drawer's `[TRACKED]` badge, which read a hardcoded `DEFAULT_TARGETS`
   instead of either list. Fixed.
2. `$ focus` and `$ bedtime`, which refused with "no targets" on a fresh
   install while five apps were being gated. Fixed, along with the token
   resolver's `preferred` set beside them.
3. CFG's own target list, which showed nothing tracked in the same state.
   Fixed at both ends at once, which is the part worth keeping: `toggleTarget`
   computes the next list from the same flow the rows render, so resolving the
   display alone would have inverted the control. A tap meant to turn one of
   the five defaults off would have removed it from an empty list, failed, and
   added it instead.

That third case is the reason the rule is stated as "every reader" rather than
"read through resolve". Resolving one end of a read-modify-write is worse than
resolving neither.

### Empty meant two things, and the store now says which

An empty stored list used to mean "use the defaults", full stop, so turning
the last target off wrote empty and the defaults came back. Tracking nothing
was not expressible.

`targets_chosen` in the proto is the tiebreak, and `TargetScope.Selection`
carries it with the list rather than beside it. False plus empty is a fresh
install and the defaults apply; true plus empty is "track nothing" and it is
honoured; a non-empty list is the list either way, because a list with
packages in it is data and the flag only ever settles empty.

The migration is a no-op by construction. Proto3 defaults the flag to false,
so every install that already exists reads as untouched, and both shapes it
can be in behave exactly as before: a populated list keeps itself through the
non-empty clause, and an empty one was a fresh install anyway.

**The list and the flag are one type, not two values.** Every bug in this area
came from reading one half without the other, and the repository stopped
exposing the raw list at all: `SettingsRepository.targetSelection` is the only
flow, and there is no `targets` beside it to read by mistake.

Two related traps, both already paid for elsewhere in this file. A reader that
caches the resolved set is wrong after every edit, which is what `by lazy` did
to the badge. And `usedFallback` and `trackingNothing` are separate questions
with a test asserting they are never both true, because they describe the two
ways a list can be empty and a screen told both at once has nothing to render.

### The fallback that had to come out of `packageNames`

`packageNames` substituted the defaults whenever it was handed an empty set. It
looked like belt and braces for the null trap and it was a second answer to a
question `resolve` had already answered.

Once empty can mean "the user chose nothing", that substitution stops being
redundant and starts being wrong: the service would go on receiving every
default's events for a user who asked for none. Not a guard that cannot fire,
but one that fires against the thing it is guarding.

It is deleted. The null trap stays closed by the own-package entry, which is
never absent, so with no targets the scope is our own package alone, which is
narrow and correct.

## Colours live in one file

`ui/theme/Color.kt` is the only place a colour is defined. Everything else
names a token, and `tools/check-colors.sh` fails on a hex literal or a named
`Color` constant anywhere else under `ui/`.

The rule exists because the palette has an actual constraint: `TerminalAlert`
(`#FF5555`) is reserved for the terminal tier and appears nowhere else. That
is unenforceable the moment a one-off `Color(0xFF...)` can appear in a modifier
chain, which is how the launcher arrived with six private colour constants and
two buried literals.

`Color.Red` is checked as well as hex. It is the same literal wearing a
different spelling, and it would defeat the terminal-tier rule while passing a
hex-only grep.

## Service model

No `FOREGROUND_SERVICE` of any type, and no self-started background services.
System-bound services (`AccessibilityService`, `NotificationListenerService`)
are permitted because the OS requires them to be discrete classes and controls
their binding lifecycle.

## What earns a place on the console

**A command earns its place when it takes a bounded input, returns one
answer, and leaves nothing behind. If it creates state the user will come
back to read, it belongs in an app, however convenient it would be here.**

Three utilities were admitted on it: `calc`, `conv` and `days`. Each takes a
line, answers once, and keeps nothing between calls, which every one of their
test files asserts directly rather than leaving as a claim.

### `$ rem` is a deliberate exception, not a violation to clean up

`$ rem` fails the second clause on purpose. A reminder is state the user will
come back to read, and by the rule above it belongs in an app. The owner
decided otherwise, and this is written down so a later reader does not delete
it as a rule violation, the way the `rem` stub was deleted in 132aa42.

The reasons, as decided:

- **The first clause holds.** Opening a clock or a notes app to set "in 45
  minutes, tea" costs more attention than the reminder is worth, and the
  console is already where the user is when the thought arrives. That is the
  exact question the first clause asks, and here it comes out the other way
  from the dictionary.
- **The state is bounded and self-clearing.** At most twenty pending, and a
  reminder leaves the store when it is dismissed. It is not a list to curate.
- **It announces itself where the user already looks.** No notification and
  no new grant: a short tone that follows the ringer, and the text waiting on
  the console until dismissed.

What keeps it an exception rather than a precedent: it is the only command
that stores anything, its queue is separate from Bit's speech budget and
from `console_queued`, and it has no view of its own beyond the one row. A
second stateful command needs its own decision recorded here, not this one
cited. Delivery is exact: the manifest declares `USE_EXACT_ALARM`, plus
`SCHEDULE_EXACT_ALARM` capped at `maxSdkVersion="32"` for Android 12 and
12L, and `setExactAndAllowWhileIdle` is used whenever
`canScheduleExactAlarms()` allows it. Otherwise it falls back to an inexact
alarm, and the acknowledgement states which was used (EXACT, INEXACT, or
saved but not armed). `AccessibilityConfigTest` pins the permission list.

### The first clause is not decoration, and here is where it bit

The second clause does most of the work and is easy to read as the whole
rule. It is not. The first clause asks a separate question: **does opening
the thing that already does this cost more attention than the task itself?**

A dictionary is the case that separates them, and it is recorded here because
it passes everything the second clause asks and still fails.

It is bounded, stateless and offline. Payload is not the objection either: a
stripped one-line lexicon is about fifty thousand headwords at roughly sixty
bytes, so about 3 MB raw and about 1.2 MB with shared-prefix or trie
encoding. That is meaningful against a sub-10 MB APK and not disqualifying.

It fails because **nothing gets opened for a definition today.** Long-press a
word and Android offers one inline, at the only place an unfamiliar word is
ever met, which is inside something being read. The launcher version costs
leaving the text, holding the word in your head, going home, typing `def` and
typing the word. That is strictly more attention than what it would replace,
in the case that happens almost every time. Same shape as a torch: the OS
already does it closer to where it is needed.

Second and independent: a lexicon invites browsing in a way a calculator does
not, because one word suggests another. Fifty thousand things on a launcher
surface passes the letter of the second clause and fails its spirit.

### The honest limit on that argument, and what would change it

The long-press route is not universal. It needs selectable text, so it fails
on a word inside an image or a video frame, and some apps replace the
selection menu with their own. In exactly those cases the launcher version
would work where the platform does not.

That is a real gap and it is not what the feature would be for. A feature
justified only by its fallback case is a feature whose common case is a
regression, and the browsing objection stands on its own regardless.

So it is a no, and the evidence that would reopen it is named rather than
left implicit: **a field tester reporting that they actually hit the
unselectable-text case often enough to go looking for a way to look a word
up.** Not a guess about how often it happens. The inset constants are held
the same way, for the same reason.

## The accessibility profile is user-visible

Banking and UPI apps read the declared `AccessibilityServiceInfo` of every
enabled service and refuse to run, or warn loudly, based on what they find.
Three attributes in `accessibility_service_config.xml` are therefore load
bearing and are not to be changed casually:

- `android:packageNames` stays scoped to the monitored targets plus the
  applicationId, `org.jitteros.app`. The code namespace is `dev.molasses` and
  is not a package on the device; `AccessibilityConfigTest` reads the
  applicationId from `app/build.gradle.kts` rather than a literal.
- `android:canRetrieveWindowContent` stays `false`.
- `android:accessibilityFlags` does not include `flagRetrieveInteractiveWindows`.

Do not add `typeWindowContentChanged` as a scroll proxy again without new evidence: 2952f14 reverted one that could not tell scrolling from video playback and ate taps on YouTube's player controls (README, Known limitations).

Removing the last one means `getWindows()` returns an empty list. If a feature
needs to know about another window, route it through `UsageStatsManager` or
drop the feature. Do not add the flag back.

### What dropping the flag costs, in full

Three things have broken because of this and each was found by debugging
rather than by reading. The list exists so the fourth is found by reading.

1. **`getWindows()` returns an empty list.** No enumeration of any window,
   including our own. The Phase 0.1 overlay collision guard used it to
   register our window ids up front and had to be rewritten to learn them from
   events instead.
2. **`getBoundsInScreen()` on another window is unavailable.** Bit's planned
   fullscreen auto-retract has no geometry to read and needs a proxy signal.
3. **`AccessibilityEvent.getWindowId()` is not dependable.** It returns `-1`
   when the platform declines to say, and under this profile it declines for
   every event: the device read `-1` and nothing else. The learned-id guard
   treated `-1` as a real id, learned it from one of our own events, and then
   matched it against every event from every package. The whole app routed
   nothing on a device where the service was bound, ready, correctly scoped
   and reporting healthy.

   That guard is now **deleted**, not repaired. With ids stripped it could
   never match a real window, so a narrower version of it would be a guard
   that cannot fire, which is worse than none: the next reader takes it as
   cover for a case the package check is carrying alone. The collision guard
   is the package-name check in `ForegroundEventRouter` and nothing else.

The pattern behind all three: **this profile withholds window identity and
window geometry, not just window contents.** `canRetrieveWindowContent` is the
attribute that sounds like it covers only the second, and it does not.

So, before writing anything that depends on which window an event came from,
where a window is, or what else is on screen, assume the answer is unavailable
and check. An API that compiles and returns a plausible value is not evidence:
`getWindowId()` returns an `Int` either way, and `-1` is a perfectly good `Int`
until it is used as a set key.

Where a value can be absent, reject the absent form at the boundary rather
than downstream. A sentinel that reaches a comparison is a sentinel that
matches something.

Jitter must never draw any overlay over a package in
`core/safety/SensitivePackages`. An overlay sets `FLAG_WINDOW_IS_OBSCURED` on
that app's touches and a hardened payment app is entitled to refuse the
transaction.

## Window insets are a constant, and that is a known fault

Both activities call `setDecorFitsSystemWindows(window, false)` and neither
reads a single inset. Nothing in `ui/` consumes `WindowInsets` except the two
`imePadding()` calls on the lists. Everything else clears the system bars with
a hardcoded `padding(horizontal = 18.dp, vertical = 44.dp)`, and the drawer and
the notification inbox each repeat their own copy of that guess.

It works. 44dp is larger than a 24dp status bar and larger than a gesture
navigation inset, so nothing looks wrong on the hardware this was built on.
That is the whole of why it has survived: the number is not related to any
measurement, it is just bigger than the ones that have been tried. It is a few
dp short of a 48dp three button bar, it is short on a device whose status bar
grows to cover a tall cutout, and 18dp clears nothing at all in landscape.

The fix is real inset consumption, `safeDrawing` or the individual types,
replacing the constants. It is deliberately not done yet: it is being held
until test users report from hardware nobody here owns, because guessing a
second time is not better than guessing once.

### Immersive has now landed, and this section still stands

`LauncherActivity.hideStatusBar` hides the status bar on the console, with
transient bars on swipe. It was landed for its own sake: a launcher that
reports four unread messages along the top gives you somewhere to go, which is
what the console is arranged around not doing.

It changes nothing about the fault above, and this paragraph exists so that is
not rediscovered as "already fixed". With the status bar hidden there is no
status bar for the 44dp to be wrong about **on that one screen**, and the
constant is exactly as wrong as it was everywhere else: the settings activity,
the gate, the lock overlay, and the console itself for as long as a transient
bar is showing.

So the fault did not shrink, its most-looked-at instance became invisible.
That is the worse direction for something waiting on field reports, and it is
why the section says to leave the note standing.

The navigation bar is deliberately not hidden. It carries nobody's
notifications, and hiding it would take the back gesture's affordance with it
on a three-button device.

### If you are building immersive mode, read this first

Immersive mode hides the system bars. A constant padding that exists to clear
the system bars is trivially correct once there are no bars to clear. So
immersive **conceals this fault rather than fixing it**, and it conceals it
completely: with the bars hidden there is no device shape and no navigation
mode on which the 44dp is wrong.

That matters in two directions. Shipping immersive does not close the inset
work, and the inset work must not be marked done because immersive made the
symptom go away. And the first user who turns immersive off, or the first
screen that does not use it, gets the original fault back with no warning and
nothing in the diff to point at.

So: land immersive if it is wanted for its own sake, and leave this section
standing until the constants are actually gone. That is what happened; see
above.

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

## Pushing

**Push only with `tools/push-if-green.sh`. Never run `git push` directly.**

A failing suite has been pushed twice, 677928f and 3e5214e, both times
because the push was chained after the checks instead of gated on them. A
line like `check-all.sh; git push` pushes whatever the checks said.

The script refuses a dirty working tree (untracked files included), runs
`check-structure.py`, then `check-all.sh`, and pushes only if the last line
check-all printed is exactly `check-all: PASS`. Anything else exits non-zero
and pushes nothing. The clean-tree rule is there because the checks read the
working tree and the push sends commits, and the two are the same only when
nothing is uncommitted. `PushGateTest` pins all three conditions.

**Never create or push a tag.** Tagging is the owner's manual step in
RELEASE.md, taken only after `./gradlew testDebugUnitTest` and the signed
release build have both passed on the owner's machine. A green check-all is
not that: it compiles neither the Android layer nor the app module's tests
under the Compose compiler. `push-if-green.sh` pushes the current branch and
nothing else, and the `git push origin vX.Y.Z` in RELEASE.md is the owner's
command, not an exception to the rule above.

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

### What "green" covers, and for which files it does not

`pure-verify` compiles `core`, `engine`, part of `sensing`, `legacy` and
`debug`. Everything else under `app/src/main/java` is compiled by **no tool
available in that environment**: at the last count, 38 files, including every
Compose screen, every overlay manager, the accessibility service, the DataStore
and Room layers and the DI module.

For those 38 files, a green `check-all.sh` means:

- no em or en dashes
- valid UTF-8, no BOM, no mojibake
- no colour literals outside `Color.kt`
- no brace that closes a function early

and nothing else. **It does not mean the file parses.** It cannot mean the
file type-checks, resolves its imports, or agrees with any signature it calls.

That distinction is not pedantic, it cost seven commits. `LauncherActivity.kt`
carried one misplaced brace that closed a composable three hundred lines early,
and everything after it became top level declarations. The file could not
compile. `check-all.sh` passed on every commit, and "check-all green" was
reported each time as though it meant the code was sound.

The structure check exists because of that, and it is a tripwire rather than a
compiler. It catches one class of fault. The honest phrasing for the rest is
that the Android layer is unverified until a real build runs, which is what the
paragraph above already says and what the reports should say too.

Kotlin files added under `app/src/main/java` outside the pure set inherit this
silence automatically. Moving a file into `pureMain` is the only thing that
buys it a compiler, and `check-structure.py` derives its own scope from that
list so the two cannot drift.

### What check-all does not run

**check-all does not run the app module's tests.** `./gradlew
testDebugUnitTest` runs only on the owner's machine, and RELEASE.md makes it a
required step before tagging.

The gap is not a list of skipped files. Every test class under `app/src/test`
sits in pure-verify's test source set (`core`, `engine`, `sensing`, `legacy`,
`debug` cover all of them), so every one of them runs in check-all. What
differs is how they are built and what they read:

- **No Compose compiler.** pure-verify compiles the pure classes with plain
  Kotlin. The app module applies the Compose compiler, which adds a `$stable`
  field to every class, including pure ones like `Lease` and `Lock`. A test
  that reflects over fields sees it in `testDebugUnitTest` and not here, which
  is how `LeasePersistenceTest` and `LockPersistenceTest` failed on first run.
- **This checkout's line endings.** A text-reading test sees whatever the
  working tree has. See `.gitattributes` and `LineEndingsTest`.
- **`app/src/androidTest` never runs anywhere but a device.** Today that is
  `LatencyProbeTest`, which nothing here compiles or runs.

So a test can be green in check-all and red on the owner's machine, and the
first run of `testDebugUnitTest` found four that were. Treat a green check-all
as "the pure layer and the text contracts hold under plain Kotlin on LF", and
nothing more.
