# Jitter

A progressive-friction focus tool for Android.

Instagram, X and YouTube are never blocked. Instead, the longer you use them
inside a six-hour cycle, the more the phone *feels* like it is failing.

The package id is `dev.molasses` and the class names still say Molasses. That
was the working name and renaming the package would orphan every existing
install for no user-visible gain, so it stays.

| Cumulative foreground time | Behaviour |
|---|---|
| 0-5 min | Normal |
| **at 5 min** | Full-screen movement gate (physical movement required) |
| 5-10 min | Phantom Stall: 1000 ms touch blackout on every scroll |
| **at 10 min** | Movement gate |
| 10-15 min | Phantom Stall: 3000 ms |
| **at 15 min** | Movement gate |
| 15-20 min | Phantom Stall: 5000 ms |
| **at 20 min** | Terminal tier. 5000 ms stall, gate re-arms every 5 min indefinitely |

**The invariant:** clearing a movement gate unlocks the *next* tier of usage.
It never resets accumulated time, never lowers the stall duration, and never
rewinds `tierIndex`. Gates are a toll, not a refund.

---

## Banking and UPI apps

Launching PhonePe, Google Pay, Paytm, BHIM or a banking app on a phone with
Jitter enabled can raise "Suspicious App Detected: The below app has enhanced
access that can be used by fraudsters to steal your money."

That warning is not about anything Jitter does at runtime. Those apps cannot
observe our behaviour. They read two things:

1. The declared `AccessibilityServiceInfo` of every enabled accessibility
   service, which is static and public.
2. `MotionEvent.FLAG_WINDOW_IS_OBSCURED` on their own touches, which is set
   whenever anything is drawn over them.

A runtime "drop our hooks when a bank is open" trick is invisible to the first
check and buys nothing on its own. So the response is in the declared profile
and in what we draw.

**Declared profile.** `packageNames` is scoped to the monitored targets plus
`dev.molasses`, `canRetrieveWindowContent` is `false`, and
`flagRetrieveInteractiveWindows` has been removed. That last one is the flag
that reads as "this service can enumerate and inspect every window on screen",
and it is the one worth giving up.

**Never draw over a payment app.** `core/safety/SensitivePackages` holds a set
of package prefixes (PhonePe, Paytm, Google Pay, BHIM, SBI and ICICI by
default). While one of them is foreground, no overlay is drawn at all: not the
stall sink, not a gate. This is the half that actually matters, because an
overlay over a payment screen can block a transaction outright rather than
merely producing a warning.

The default list names six families and there are dozens of Indian bank apps,
so it is user-extensible in settings under SAFETY. Add yours if you see a
warning.

Because those packages are deliberately absent from `packageNames`, no
accessibility event ever names them, and `UsageStatsManager` is the only
witness that one is in front. While any overlay of ours is on the glass the
foreground poll tightens from 2 s to 400 ms, which bounds how long a stall
sink can sit over an app the user has just switched to.

### Tested on a device, September 2026

| app | with Jitter enabled |
| --- | --- |
| Google Pay | payments complete normally |
| PhonePe | payments complete normally |
| **Paytm** (`net.one97.paytm`) | **blocks the transaction** |

Paytm shows "Important Security Alert / Suspicious App Detected", names
Jitter, and says "Remove the app or disable its Accessibility permission to
continue". The payment does not go through.

**This one cannot be fixed from inside Jitter, and it is worth being precise
about why.** Paytm calls `getEnabledAccessibilityServiceList()` and blocks on
the *presence* of any enabled service that is not on its allowlist. It never
asks what that service observes. So every mitigation above is invisible to it:
the scoped `packageNames`, `canRetrieveWindowContent` being false, the removed
`flagRetrieveInteractiveWindows`, and the refusal to draw anything at all over
a payment app. None of them change the one thing Paytm checks.

The only mechanism that does is `AccessibilityService.disableSelf()`.

**Disable for payments.** Settings, under SAFETY. It turns the accessibility
service off. Friction stops completely: no stalls, no checkpoints, no locks
enforced. There is no timer, and nothing inside Jitter can switch it back on,
because `disableSelf()` cannot be reversed programmatically by platform
guarantee. To undo it you go into Android Settings, Accessibility, and enable
Jitter again by hand. The permission checklist at the top of settings will
read "Not running" until you do.

That guarantee is the point rather than an inconvenience. A control that could
quietly switch friction back on is a control you cannot trust at a till, and
one that switched it back on *for* you would be the bypass every other defence
here exists to prevent, running in reverse.

**The overlay suppression set stays exactly as it is.** It prevents a
different failure, the tapjacking check that sets
`FLAG_WINDOW_IS_OBSCURED`, and GPay and PhonePe would both hit that without
it. Disabling for payments is the answer to Paytm specifically, not a
replacement for never drawing over a payment screen.

**Honestly:** one blocker out of three is a documented limitation rather than
an architecture problem, and the workaround is two taps plus a trip back
through Settings. If another app turns up, that changes.

**What the removal cost.** `getWindows()` now returns an empty list. Bit's
planned fullscreen auto-retract loses `getBoundsInScreen()` entirely and will
need a proxy signal.

The Phase 0.1 overlay collision guard also used it, to enumerate our own
windows up front. It now learns those window ids from events instead, which
means the set is **empty for the first event from any new overlay window**. In
that window the guard is carried entirely by the package-name check in
`ForegroundEventRouter`: an event wearing `dev.molasses` that is not the
launcher activity is dropped.

**This is weaker than the token set it replaced and needs device
verification.** The reasoning is that an accessibility overlay added by this
service reports our package on its events, so the package check is sufficient;
`ForegroundEventRouterTest` pins that with an empty id set for every event
kind, including a null class name. But the pure tests assert what the router
does with an event, not what the platform actually puts on one. If a real
overlay turns out to report a different package, or none, a gate could read as
a foreground exit and close the session it is gating. Watch for a `PAUSED`
ledger row timed with a gate appearing.

---

## Distribution

Jitter is distributed by sideload and, once it is buildable, F-Droid. That is
permanent, not a staging step on the way to Google Play.

An accessibility service that exists to make other apps harder to use is not
something Play will keep listed. The Play Console policy on accessibility APIs
requires that the service exist to help users with disabilities, and
`isAccessibilityTool` is set to `false` in the service config precisely because
claiming otherwise would be a false declaration. Every part of the design
follows from accepting that:

- No `SYSTEM_ALERT_WINDOW`, no `READ_PHONE_STATE`, no `QUERY_ALL_PACKAGES`, no
  foreground service, no network permission. The permission set is small enough
  to read in full on the install screen.
- The accessibility service description is written as a prominent disclosure in
  plain language, because a sideloading user has no store listing to read.
- No update mechanism of its own, no telemetry, no account.

To install, enable install from unknown sources, install the APK, then grant
the accessibility service and usage access from the setup checklist in
settings. Uninstalling removes everything; the app stores nothing outside its
own data directory.

---

## Build status (read this first)

**`./gradlew assembleDebug` has never been run. It cannot be run in the
environment this was written in.** The brief asked me to stop and name a
blocking constraint rather than substitute a weaker approach, so:

The egress policy here denies `dl.google.com`. That single host serves both
the Android SDK and all of Google Maven, and `maven.google.com` is a 301
redirect into it. So AGP, every `androidx` artifact, Compose, Room, Hilt's
Android artifacts, and the SDK platform jars are all unreachable:

```
$ ./gradlew assembleDebug
Plugin [id: 'com.android.application', version: '8.11.1'] was not found in any
of the following sources:
  ...
  - Plugin Repositories (could not resolve plugin artifact
    'com.android.application:com.android.application.gradle.plugin:8.11.1')
    Searched in the following repositories:
      Google
      MavenRepo

$ curl -I https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
curl: (56) CONNECT tunnel failed, response 403
```

Maven Central *is* reachable, and the Gradle wrapper downloads fine. So rather
than claim an unverified build, I verified everything that can be verified
without the Android toolchain and marked the rest honestly.

### What is actually verified

| | |
|---|---|
| **173 JVM tests, 0 failures** | `core/**`, `engine`, and the pure sensing maths, compiled and executed for real |
| **Purity is enforced, not asserted** | `PurityTest` walks the source tree and fails if the pure set grows an `android.*`/`androidx.*` import, and cross-checks its own list against the harness's, so the two cannot drift |
| **Six real bugs found and fixed** | see "Bugs the harness caught" |

Reproduce:

```
cd tools/pure-verify && ./gradlew test --rerun-tasks
```

`tools/pure-verify` is a **standalone Gradle build**, not an included module.
The root `settings.gradle.kts` includes only `:app`, as the brief requires. So
`./gradlew :tools:pure-verify` will not resolve; it has its own wrapper.

```
BucketProbe                  1 tests, 0 failed
CadenceAnalyzerTest          9 tests, 0 failed
CadenceBandEdgeTest          4 tests, 0 failed
CalibrationSweep             1 tests, 0 failed
ChannelSpecTest              7 tests, 0 failed
ClockTamperClampTest         8 tests, 0 failed
CvEstimatorTest              4 tests, 0 failed
FallbackImuGateTest         19 tests, 0 failed
ForegroundReplayTest        12 tests, 0 failed
FrictionEngineTest          25 tests, 0 failed
HysteresisGateTest           8 tests, 0 failed
IirFilterTest                6 tests, 0 failed
LatencySegmentsTest         14 tests, 0 failed
MonotonicIntTest             4 tests, 0 failed
MovementRejectionTest       13 tests, 0 failed
PurityTest                   2 tests, 0 failed
StepGateTest                 9 tests, 0 failed
SustainAccumulatorTest      11 tests, 0 failed
TerminalEventDeliveryTest    5 tests, 0 failed
TickEvaluationTest           6 tests, 0 failed
TierPolicyTest               5 tests, 0 failed
TOTAL                      173 tests, 0 failed
```

### What is NOT verified

Everything that touches the Android framework: `MolassesAccessibilityService`,
`ShutterOverlayManager`, `OverlayHost`, `GateOverlayManager`,
`MovementDetector`, `ForegroundReconciler`, the Room and Proto DataStore
layers, and all Compose UI. It is written to compile and has been checked by
hand and by cross-reference sweeps, but **it has not been through a compiler.**
Expect a first-compile pass to surface import and signature fixes. Treat every
version number in `gradle/libs.versions.toml` as unresolved.

### The measured latency distribution (still not available)

I cannot report latency numbers and will not invent them. What changed in this
patch is that the probe now measures the right thing (§6), decomposed into four
segments, all on `SystemClock.uptimeMillis()` so `AccessibilityEvent.getEventTime()`
and `MotionEvent.getEventTime()` are directly subtractable:

| Segment | From → To | What it tells you |
|---|---|---|
| **A** | scroll `eventTime` → `onAccessibilityEvent` entry | Platform delivery. **Not optimisable.** If A alone exceeds ~80 ms the concept is dead regardless of implementation. |
| **B** | callback entry → `updateViewLayout` returns | The only segment we control. |
| **C** | `updateViewLayout` returns → next `Choreographer` frame | WMS relayout + InputDispatcher window-handle refresh. |
| **D** | scroll `eventTime` → `eventTime` of the first absorbed `MotionEvent` | The real answer. Everything else is diagnostic. |

The previous probe stopped at B and would have reported an optimistic number:
`updateViewLayout` returning means only that the change is *queued* to
WindowManagerService. The armed flag is not live in the input dispatcher until
WMS relayouts and InputDispatcher refreshes its window handles, one or more
frames later. C exists to measure exactly that gap.

Ring buffer of 100 per package per segment, with p50 and p95 rather than a
mean. Latency distributions are right-skewed and a mean hides the tail that
breaks the illusion. Negative D samples are discarded and counted, because the
sink can absorb a touch that was already in flight when the stall armed, with
an `eventTime` earlier than the scroll.

```
STALL_LATENCY: [TARGET: com.instagram.android] A=34 B=2 C=18 D=71 | p50(D)=68 p95(D)=112 n=50

adb shell dumpsys activity service \
    dev.molasses/.monitor.MolassesAccessibilityService
```

**The central feasibility question remains open until someone runs that on
hardware.**

## The two ambiguities, resolved (§0.1)

### 1. What happens after 20 minutes?

`TIER_TERMINAL`: the stall stays at 5000 ms and the movement gate re-arms every
five minutes, indefinitely. Nothing is silently capped.

Implemented by letting the tier index keep climbing rather than saturating:

```kotlin
fun indexFor(accumulatedMs: Long) = (accumulatedMs / TIER_WIDTH_MS).toInt()  // unbounded
fun stallMsFor(index: Int) = if (index >= TERMINAL_INDEX) 5_000L else STALL_MS[index]
```

So 25 min is tier 5, 60 min is tier 12, each with a gate on entry and a 5000 ms
stall. The index stays a faithful description of usage instead of a saturated
counter, which matters because it is what the ledger and the debug screen
report.

### 2. What does "resets only after 6 continuous hours" mean?

The phrase is ambiguous between an abstinence window and a wall-clock window.
The app runs **`FIXED_WINDOW_6H`**: the cycle is anchored on the first target
app you open from a clean state and ends six hours later whether you keep
scrolling or not.

This used to be a setting, and the setting did nothing. The engine never
applied a change, and its own checkpoint wrote its startup policy back over
the user's choice within fifteen seconds. It was removed rather than wired,
for the reason below. The enum and the stored proto field remain so every
existing file parses; the value stored there is no longer read.

`ABSTINENCE_6H` was the default originally, on the argument that a fixed window
lets a user wait out the clock while still scrolling. That is true, and it is
also the weaker half of the argument: waiting out six hours of a fixed window
means six hours of the ladder doing its job. An abstinence window has the worse
property that a user who opens Instagram once an hour never sees a reset at
all, so the countdown is unreadable and the ladder only ever climbs.

The deadline is evaluated on the 15 s checkpoint tick, not only when a target
app comes to the foreground. Checking on entry alone means a session that runs
past six hours never rolls: the countdown goes negative and the stall stays
pinned at its ceiling for the rest of the session.

The anchor is stored as a `StampedInstant`: wall clock, `elapsedRealtime`, and
`BOOT_COUNT` together. A bare wall-clock deadline is defeated by opening
settings and moving the system clock forward six hours, which is the cheapest
bypass in the app and needs nothing but the date and time screen. See the
clamp section below.

### 3. A third ambiguity I hit (`tierIndex` monotonicity vs. cycle reset)

Not in the brief, but unavoidable: "`tierIndex` … monotonic, never decremented"
and "the cycle resets" are in direct tension, because a reset must return the
user to normal behaviour.

**Resolution:** the invariant is scoped to a cycle. A rollover *replaces* each
`AppState` with a fresh one rather than assigning a lower value to the existing
counter, which would throw, by design. This keeps "never decremented" true for
the lifetime of a counter, so the guard stays meaningful where it matters.
`FrictionEngineTest.a rollover does not throw despite the monotonic tier
counter` is the regression guard.

---

## Bugs the harness caught

Worth recording, because they are the argument for having verified anything at
all rather than shipping the lot unexecuted.

1. **The engine crashed on construction.** The checkpoint channel was built as
   `Channel(capacity = CONFLATED, onBufferOverflow = DROP_OLDEST)`. `CONFLATED`
   already implies `DROP_OLDEST`, and passing both throws
   `IllegalArgumentException` from the `Channel()` factory. Every single
   `FrictionEngineTest` case failed with it. On a device this would have been a
   service that died the instant it connected.

2. **A persisted snapshot silently dropped the live session.** `snapshot()`
   serialised only *closed* foreground time, so a crash mid-session lost the
   whole session, and the test asserting "clearing a gate leaves accumulated
   time untouched" was passing vacuously by comparing 0 to 0. `snapshot()` now
   folds the live session and the writer stamps `last_seen_*` at the same
   instant. The reconciler replays from exactly the point the snapshot accounts
   up to, so the interval is credited once.

Two more from this patch, both caught by the calibration sweep rather than by
a test written to look for them:

3. **A working bypass on the pipeline §1.1 asks us to prefer.** Shaking at
   4.5 Hz aliases through the 250 ms refractory to 2.17 Hz with a CV of 0.035
   and passed every test on the fused path. See "A real bypass on the fused
   path" below.

4. **The CV floor made the gate unclearable at 100 Hz and above.** A per-window
   lower bound cannot coexist with an 8-second continuous sustain: ~800
   overlapping windows compound a 2% per-window false rate into near-certain
   failure. The identical walk passed at 50 Hz and failed at 100 Hz purely
   because coarser timestamps added noise. See "§3" below.

And two more from the first pass, caught by review rather than by tests,
because both were code that pretended to work:

5. **The exit watchdog could not work as specified.** It was written to read
   the foreground package from `AccessibilityWindowInfo`, which carries no
   package name. The only route is `window.root.packageName`, and that is a
   content read, which `canRetrieveWindowContent="false"` gives up. It now uses
   `UsageStatsManager` (`monitor/ForegroundProbe.kt`), needing only the
   `PACKAGE_USAGE_STATS` grant the app already requires. It returns `null` for
   "no information", never "nothing is in front", so a missing grant cannot
   read as the user leaving every app.

6. **`OverlayHost` could only ever be shown once.** A `LifecycleRegistry`
   cannot return to `CREATED` after `DESTROYED`; it throws. The class is now
   explicitly single-use and `GateOverlayManager` builds a fresh host per gate.

---

## Device session 1: the gate was unclearable

First device build. The progress ring reached 100%, the gate never cleared, and
the streak reset to 0 with "a little faster". Two separate defects.

### A terminal event was riding on a conflating channel

Pass was a boolean on `GateProgress`, and `GateProgress` travels on a
`StateFlow`. A StateFlow conflates. A `passed = true` that holds for one sensor
sample can be overwritten by the next sample before the collector is scheduled,
and then it is gone. The ring reaching 100% proved the sustain had completed,
so the failure was delivery rather than sensing.

`GateOutcome` now travels on its own `MutableSharedFlow(replay = 1,
extraBufferCapacity = 1)`. `tryEmit` always succeeds, so the sensor callback
never suspends. The replay cache is cleared on every `start()`, without which
the previous session's pass would be delivered to the next gate and clear it
for free. `GateProgress` has no `passed` field at all now, so no collector can
re-create the inference.

`TerminalEventDeliveryTest` reproduces the loss on a StateFlow and the survival
on a SharedFlow through the same producer burst.

Two smaller faults on the same path are fixed with it. `pass()` ran inside the
very coroutine it then cancelled, and it was not idempotent, so a duplicate
outcome or an outcome racing the 90 s timeout would have cleared one gate
twice.

### The sustain streak was too brittle

Requiring all seven tests to hold on every window for 8 continuous seconds is
the defect already found for the CV floor, and it applied to the other six just
as hard. On the device a marginal cadence window reset a completed streak.

**Fixed 4 Hz tick.** The battery is evaluated every 250 ms of sample time, not
per sample. The grid is anchored, so a tick is always worth exactly 250 ms. An
earlier version reset the grid to the sample time, which made the realised
period 280 ms at 25 Hz against 250 ms at 200 Hz. A delivery gap longer than two
intervals resyncs instead of firing a backlog, because catching up would
evaluate one stale window many times and could credit seconds of movement that
never happened.

**Leaky bucket instead of a streak.** A passing tick adds its duration, a
failing tick removes half of it, floor 0, pass at 8 s of net credit. Credit is
capped at the requirement so a long walk cannot bank progress toward the next
gate. Break-even is a 33% pass rate, which is what stops alternating pass and
fail from being a bypass.

**Schmitt triggers.** Cadence enters at 1.20 Hz and exits only below 1.05 Hz.
RMS enters at 1.5 and exits below 1.3, the same 12.5% drop. This supersedes the
1.15 Hz guard band, which was solving the same problem less well.

### Measured: ticks to clear

At 250 ms per tick, 32 ticks is the floor.

| condition | ticks to clear | wall time |
|---|---|---|
| clean gait | 32 | 8.0 s |
| 10% of ticks failing | 43 | 10.8 s |
| 50% of ticks failing | 182 | 45.5 s |
| 75% of ticks failing | never | never |

Failure rates are applied at the tick level, in `SustainAccumulatorTest`.
Splicing stillness into a synthetic gait signal does not produce "10% of ticks
failing": 250 ms of stillness sits inside a 3500 ms analysis window, so a 10%
duty cycle pollutes closer to 90% of windows, and that signal never clears at
all.

A 1 s stumble in real gait costs 125 ms of credit, one failing tick. The
analysis window is why it costs so little, since most of the window is still
walking.

Through the full pipeline, clean gait clears in:

| rate | ticks to clear | ticks over a fixed 12 s | tick duration |
|---|---|---|---|
| 25 Hz | 35 | 48 | 250 ms |
| 50 Hz | 38 | 48 | 250 ms |
| 100 Hz | 39 | 48 | 250 ms |
| 200 Hz | 39 | 48 | 250 ms |

Tick count and tick duration are identical across delivery rates. The residual
4-tick spread in time to clear is analyzer warm-up: `minSamples` is a fixed
count of 40, which is 1.6 s at 25 Hz and 0.2 s at 200 Hz.

### Band floor, through the full gate with hysteresis

| Hz | p=0.00 | p=0.13 | p=0.25 | p=0.38 | p=0.50 | p=0.63 | p=0.75 | p=0.88 |
|---|---|---|---|---|---|---|---|---|
| 1.20 | clears | clears | clears | clears | clears | clears | clears | clears |
| 1.25 | clears | clears | clears | clears | clears | clears | clears | clears |
| 1.30 | clears | clears | clears | clears | clears | clears | clears | clears |
| 1.35 | clears | clears | clears | clears | clears | clears | clears | clears |

## GATE_EVAL: setting thresholds from real gait

Every evaluation tick is logged, one line, every measured value and every
per-test verdict.

```
adb logcat -s GATE_EVAL
```

```
t=7 ms=1750 dt=250 path=IMU_IIR thr=IIR rms=3.29 peaks=6 hz=1.74 cv=0.029
rcv=0.053 rn=9 vert=0.97 pk=10.55 tilt=40.0 tiltms=1400 sup=0 [RPhCVMT]
latch=HR pass=0 credit=1750/8000 reason=CADENCE_TOO_SLOW
```

The bracketed flags are the seven tests in fixed order: Rms, Peaks, Hz, Cv,
Vertical, Magnitude ceiling, Tilt. Uppercase passed. `latch` shows which Schmitt
triggers are held. `rcv=nan` means the regularity baseline does not yet have
enough intervals, which is distinct from a measured 0.000.

Field order never changes, so a capture can be split on columns.
`TickEvaluationTest` pins the format.

## Debug controls, debug variant only

`app/src/debug` and `app/src/release` each supply a `DebugSurface`. The release
one is a no-op. Splitting by source set rather than guarding with
`if (BuildConfig.DEBUG)` means the bypass is not compiled into a release
variant, rather than being a dead branch R8 is trusted to remove.

- **Bypass**: hold the progress ring for 2 s. Writes `GATE_BYPASSED_DEBUG` to
  the ledger, never `GATE_PASSED`, so a bypassed session cannot be read back as
  a cleared one.
- **State editor**: sets accumulated time and tier index per package on the
  debug screen, so a stall tier can be reached without clearing a gate first.
  It bumps a nonce in the proto that the service watches, and the service
  rebuilds the engine from the edited snapshot. Without that the engine would
  overwrite the edit from memory at its next 15 s checkpoint and the control
  would appear to do nothing. Rebuilding drops any open session.

`DebugSurfaceTest` checks the split is intact, that the two variants declare
the same surface, that release contains no pointer handling, and that nothing
in `main` hand-rolls a bypass. It is a source-level check. Asserting against an
assembled release APK needs `assembleRelease`, which cannot run here.

## Threshold recalibration (§4)

Every threshold in the seven-test battery was measured through a gravity filter
that was silently removing ~35% of the passband. Moving the time constant from
80 ms to 650 ms invalidates all of them at once, so the whole table was
re-derived rather than one constant adjusted.

"old" is `LegacyCadenceAnalyzer`, a frozen verbatim copy of the shipped
analyzer at commit `482c728`, kept in test sources so the comparison is against
code that actually ran rather than a reconstruction. "new" is the current
pipeline. Reproduce with `CalibrationSweep`.

### The decision table

| test | old threshold | old measured (walk A=12) | new measured | proposed | rationale |
|---|---|---|---|---|---|
| dynamic RMS floor | 1.2 m/s² | 2.52 | 3.29 | **1.5** | Preserves physical sensitivity exactly. See below. **Not** the suggested 2.2. |
| peak count | ≥ 4 in 3.0 s | 5 | 6 | **≥ 4 in 3.5 s** | Window widened per §2; count unchanged. |
| cadence band | 1.2-2.6 Hz | 1.72 | 1.74 | **1.15-2.6 Hz** | Low edge needs a guard band; see §2 finding below. |
| CV | < 0.35 | 0.024 | 0.029 | **0.02-0.35** | Two-sided per §3, floor measured over a 12 s baseline. |
| vertical energy share | ≥ 0.50 | 0.98 | 0.97 | **≥ 0.50 (unchanged)** | Ratio barely moved: the filter attenuated the projection and the magnitude near-identically. |
| peak \|a\| ceiling | 25 m/s² | 7.82 | 10.55 | **25 (unchanged)** | Violent shake still reads 28.2 vs 26.0, clears the ceiling on both. |
| gravity-angle | > 25° for 1 s | 40.0 | 40.0 | **> 25° (unchanged)** | Steady-state angle is unaffected; only the *time to reach it* moved. See below. |

### Per-signal, old vs new

| signal | pipe | rms | peaks | hz | cv | vert | peak\|a\| | tilt | verdict |
|---|---|---|---|---|---|---|---|---|---|
| walk A=12 | old | 2.52 | 5 | 1.72 | 0.024 | 0.98 | 7.82 | 40.0 | PASSED |
| | **new** | **3.29** | **6** | **1.74** | **0.029** | **0.97** | **10.55** | **40.0** | **PASSED** |
| walk A=6 | old | 1.26 | 5 | 1.72 | 0.024 | 0.97 | 3.91 | 40.0 | PASSED |
| | **new** | **1.64** | **6** | **1.74** | **0.029** | **0.97** | **5.27** | **40.0** | **PASSED** |
| walk A=4 | old | 0.84 | 5 | 1.72 | 0.024 | 0.97 | 2.61 | 40.0 | NOT_ENOUGH_MOTION |
| | **new** | **1.10** | **6** | **1.74** | **0.029** | **0.97** | **3.52** | **40.0** | **NOT_ENOUGH_MOTION** |
| walk jitter .25 | old | 2.46 | 5 | 1.57 | 0.041 | 0.98 | 7.83 | 40.2 | PASSED |
| | **new** | **3.27** | **6** | **1.60** | **0.053** | **0.97** | **10.63** | **40.0** | **PASSED** |
| metronome (j=0) | old | 2.73 | 6 | 1.81 | 0.018 | 0.98 | 7.80 | 40.1 | PASSED |
| | **new** | **3.50** | **7** | **1.80** | **0.015** | **0.97** | **10.48** | **40.0** | **TOO_REGULAR** |
| desk tap | old | 3.14 | 6 | 1.81 | 0.018 | 0.99 | 8.97 | 1.7 | PHONE_STATIONARY |
| | **new** | **4.03** | **7** | **1.80** | **0.015** | **0.99** | **12.04** | **1.1** | **PHONE_STATIONARY** |
| lateral wave | old | 3.12 | 6 | 1.81 | 0.018 | 0.16 | 8.92 | 44.9 | MOTION_NOT_VERTICAL |
| | **new** | **4.01** | **7** | **1.80** | **0.015** | **0.04** | **11.98** | **41.7** | **MOTION_NOT_VERTICAL** |
| fast shake | old | 2.87 | 9 | 2.92 | 0.225 | 0.95 | 6.78 | 45.0 | CADENCE_TOO_FAST |
| | **new** | **3.38** | **11** | **3.03** | **0.236** | **0.94** | **8.21** | **45.1** | **CADENCE_TOO_FAST** |
| violent shake | old | 12.56 | 10 | 3.38 | 0.135 | 0.97 | 26.04 | 60.2 | TOO_VIOLENT |
| | **new** | **14.37** | **11** | **3.33** | **0.141** | **0.97** | **28.23** | **60.5** | **TOO_VIOLENT** |
| thumb tremor | old | 0.11 | 0 | -- | -- | 0.80 | 0.25 | 2.0 | NOT_ENOUGH_MOTION |
| | **new** | **0.13** | **0** | **--** | **--** | **0.80** | **0.31** | **2.0** | **NOT_ENOUGH_MOTION** |
| static | old | 0.00 | 0 | -- | -- | 0.00 | 0.00 | 0.0 | NOT_ENOUGH_MOTION |
| | **new** | **0.00** | **0** | **--** | **--** | **0.00** | **0.00** | **0.0** | **NOT_ENOUGH_MOTION** |

Note the walk signals now carry realistic jitter. Before the CV floor existed, a
zero-jitter synthetic was a fine model of a walker; it is now a cheat class in
its own right, and the old model would have "proved" the floor was broken.

### Passband retention

| input peak | old measured | old retention | new measured | new retention |
|---|---|---|---|---|
| 4.0 | 2.61 | 65% | 3.52 | 88% |
| 6.0 | 3.91 | 65% | 5.27 | 88% |
| 12.0 | 7.82 | 65% | 10.55 | 88% |
| 20.0 | 13.04 | 65% | 17.58 | 88% |

88%, not ~99%, because a burst train has a DC component the low-pass legitimately
absorbs. 99% is the figure for a pure sinusoid at 1.8 Hz.

### The RMS floor is 1.5, not 2.2 (the sweep does not support 2.2)

| input peakA | new rms | old floor 1.2 | **chosen 1.5** | suggested 2.2 | 2.6 |
|---|---|---|---|---|---|
| 4.0 | 1.10 | no | no | no | no |
| 5.0 | 1.37 | yes | no | no | no |
| 6.0 | 1.64 | yes | **yes** | no | no |
| 8.0 | 2.19 | yes | **yes** | no | no |
| 10.0 | 2.74 | yes | **yes** | yes | yes |
| 12.0 | 3.29 | yes | **yes** | yes | yes |

Two facts decide it.

**In physical terms, 2.2 is a large tightening nobody asked for.** On the old
pipeline RMS = 0.21 × peak, so a floor of 1.2 needed a 5.7 m/s² heel strike.
On the new pipeline RMS = 0.274 × peak, so a floor of 1.5 needs 5.5 m/s². That
is the same walker. A floor of 2.2 needs 8.0 m/s², **41% more physical
motion**, and makes the gate harder to clear without saying so.

**And 2.2 buys nothing.** The RMS floor exists to reject thumb tremor and
nothing else. Tremor measures 0.13, and the brief's own stated ceiling for it is
0.4 m/s². Every other cheat class sits *above* 2.2 (desk tap 4.03, lateral wave
4.01, fast shake 3.38) and is rejected by a different test entirely. Raising
the floor from 1.5 to 2.2 rejects no additional cheat and costs real walkers.
1.5 still clears tremor by 11×.

### The gravity-angle cost of the longer time constant

The steady-state angle is unchanged, but the estimate now takes ~3τ to get
there, so the *time to satisfy* the tilt test moved:

| true tilt | old (τ=80 ms) | new (τ=650 ms) |
|---|---|---|
| 26° | 580 ms | 2260 ms |
| 30° | 440 ms | 1340 ms |
| 40° | 340 ms | 800 ms |
| 60° | 240 ms | 560 ms |

At a shallow 26° this costs ~1.7 s before the 1 s sustain can even start. It
still fits inside the 8 s sustain and the 90 s gate budget with room to spare,
and `CadenceAnalyzerTest` pins both the regression and the fact that a 26° walk
still passes. Worth knowing if the gate ever feels sluggish to start.

## Findings that changed the design

### §2. The band floor fails on the cadence *estimate*, not the peak count

The patch predicted the peak count would bind at 1.2 Hz. With the window at
3.5 s it does not: the count lands on 4 or 5 across phase, and 4 satisfies the
gate. The actual binding constraint is the cadence estimate's sampling
variance.

Measured cadence over a 3.5 s window is a 3-5 interval sample statistic. A
walker whose true cadence is exactly 1.20 Hz measures **1.190 Hz** as often as
1.21. A `>` threshold placed at 1.2 therefore rejects about half their windows,
and since the gate needs 8 *continuous* seconds, a 1.2 Hz walker would never
clear it at all.

So `minHz` carries a guard band at **1.15**. The *stated* band is still
1.2-2.6 Hz; the threshold sits one measurement-spread below it. `maxHz` gets no
guard band, deliberately: the two edges have opposite failure costs. The low
edge exists to admit real users and should err toward admitting, the high edge
exists to exclude shaking and should err toward excluding.

With that, the §2 sweep passes 8/8 at every phase from 1.20 Hz up:

| Hz | φ=0.00 | φ=0.13 | φ=0.25 | φ=0.38 | φ=0.50 | φ=0.63 | φ=0.75 | φ=0.88 |
|---|---|---|---|---|---|---|---|---|
| 1.20 | ok(4) | ok(5) | ok(5) | ok(4) | ok(4) | ok(4) | ok(4) | ok(4) |
| 1.25 | ok(4) | ok(4) | ok(4) | ok(4) | ok(4) | ok(5) | ok(5) | ok(5) |
| 1.30 | ok(4) | ok(5) | ok(5) | ok(5) | ok(5) | ok(5) | ok(4) | ok(4) |
| 1.35 | ok(5) | ok(5) | ok(5) | ok(4) | ok(4) | ok(4) | ok(5) | ok(5) |

### §3. A per-window CV floor is incompatible with a continuous sustain

`MIN_PEAKS` was **not** raised to 5, and this is the one instruction I did not
follow literally. Three measurements drove that.

**First**, the n=3 estimate does straddle the floor, as suspected. 100k trials,
walker with a true CV of 0.06:

| n intervals | peaks needed | mean | p05 | p95 | **P(estimate < 0.02)** |
|---|---|---|---|---|---|
| 3 | 4 | 0.0531 | 0.0134 | 0.1041 | **10.6%** |
| 4 | 5 | 0.0553 | 0.0206 | 0.0969 | **4.6%** |
| 5 | 6 | 0.0565 | 0.0254 | 0.0926 | **2.0%** |
| 8 | 9 | 0.0580 | 0.0335 | 0.0853 | **0.2%** |

**Second**, `MIN_PEAKS = 5` breaks §2. Five peaks need four intervals, and at
1.2 Hz that is 4 × 833 = 3333 ms inside a 3500 ms window, leaving 167 ms of
slack.
Measured: 17 of 32 band-floor combinations fail. The §2 and §3 remedies are in
direct conflict at this window width. (`MIN_PEAKS = 5` with a 4.5 s window does
pass 0/32 failures, if you would rather go that way.)

**Third, and decisive:** even 4.6% per window is fatal, because the sustain
requires *every* window to pass for 8 continuous seconds. At 100 Hz that is
~800 overlapping windows. Measured end-to-end, before this was fixed: a
jittered 1.8 Hz walk at 100 Hz had its streak broken at t=3.15 s and again at
t=11.6 s and never passed, while **the identical signal at 50 Hz passed**, the
only difference being that coarser timestamp quantisation added enough noise to
keep the estimate off the floor. A gate that works at 50 Hz and not at 100 Hz
is not a threshold problem, it is a structural one.

The fix keeps the floor at 0.02 and changes what it is measured over. The CV
**floor** uses a 12 s baseline and abstains below 8 intervals. The CV
**ceiling** keeps the 3.5 s analysis window. The two bounds ask different
questions, which is why they can take different windows. The ceiling asks
whether the motion is erratic right now and has to be responsive. The floor
asks whether it has been machine-regular throughout and wants a long, stable
baseline. At 1.8 Hz the floor engages ~4.7 s into a gate, well before an 8 s
sustain could complete, so a metronome is still caught.

After: all four delivery rates pass at ~10 s.

| rate | transitions |
|---|---|
| 25 Hz | WAITING → PHONE_STATIONARY(1560) → NEED_MORE_STEPS(1840) → SUSTAINING(2040) → **PASSED(10040)** |
| 50 Hz | … SUSTAINING(2000) → **PASSED(10000)** |
| 100 Hz | … SUSTAINING(1990) → **PASSED(9990)** |
| 200 Hz | … SUSTAINING(1990) → **PASSED(9990)** |

**A correction to the patch's premise, while here.** Bessel's correction makes
the *variance* unbiased. The standard deviation stays biased, since
`E[s] = σ·c4(n)` and `c4(3) = 0.886`. A CV from 3 intervals therefore reads
about 11% low even with the correction applied. The measurement is 0.0531
against a true 0.06, which matches σ·c4 to three decimal places. Bessel removes
the 18% population bias and leaves an 11% one. `CvEstimatorTest` asserts
against `c4(n)` exactly.

### A real bypass on the fused path, found by the sweep

The 250 ms refractory is a hard decimator. Anything faster than 1/refractory
(4 Hz) has peaks dropped, and what survives can land anywhere, including the
middle of the pass band. Measured on the fused pipeline:

| shake Hz | jitter | fused measured hz | fused cv | verdict *before* the guard |
|---|---|---|---|---|
| 4.5 | 0.15 | 2.17 | 0.035 | **PASSED** |
| 5.0 | 0.15 | 2.50 | 0.053 | **PASSED** |

That is a working bypass. Shake at 4.5 Hz and the gate opens. The IIR path
resisted it only because its filter perturbs peak timing enough to break the
alias, which is luck rather than design, and §1.1 asks us to *prefer* the fused
path.

The guard counts refractory-discarded peaks that are **full amplitude**
relative to the accepted peak before them. The amplitude condition is what
makes it measurable: on the IIR path each heel strike leaves filter ringing
80 ms later at 19% of the main peak, whereas an aliased shake produces
candidates at 220 ms and ~95%. Counting every discarded candidate flagged real
walking at a ratio of 0.86; counting only the significant ones separates
cleanly:

| signal | suppressed/accepted (IIR) | ratio | (fused) | ratio |
|---|---|---|---|---|
| walk A=12 | 0/7 | 0.00 | 0/7 | 0.00 |
| walk jitter .25 | 0/6 | 0.00 | 0/6 | 0.00 |
| desk tap | 0/7 | 0.00 | 0/7 | 0.00 |
| fast shake | 13/11 | 1.18 | 7/7 | 1.00 |
| violent shake | 18/11 | 1.64 | 8/9 | 0.89 |

Threshold 0.30 sits in a wide gap. All shake rates 3-6 Hz, jittered or not, now
read `CADENCE_TOO_FAST` on both pipelines.

## Sensor pipeline hierarchy (§1)

```
1. TYPE_STEP_DETECTOR                        (needs ACTIVITY_RECOGNITION)
2. TYPE_LINEAR_ACCELERATION + TYPE_GRAVITY   (platform fusion)
3. TYPE_ACCELEROMETER + GravitySplitter      (our IIR)
```

Level 2 requires **both** fused sensors. `TYPE_LINEAR_ACCELERATION` alone is not
enough. The vertical-energy-share and gravity-angle tests both need a gravity
vector, and re-deriving one would reintroduce the very filter this level exists
to avoid. A device with linear acceleration but no gravity sensor drops to
level 3 rather than running a half-fused pipeline.

Levels 2 and 3 are separate **calibration domains** with separate threshold
sets behind one interface. `Thresholds.FUSED` is seeded from `Thresholds.IIR`
and marked `UNCALIBRATED`, surfaced as such in the debug screen, because the
fused sensor's internal high-pass has a corner we neither control nor can
query. The active path is published on `GateProgress` and stamped on **every**
ledger row. When a gate pass looks wrong in hindsight, the first question is
which domain produced it, and that has to be answerable from the ledger alone.

### α is derived from measured dt, never hardcoded

`SENSOR_DELAY_GAME` is a hint, not a contract. A fixed α means a time constant
that drifts with the device:

| rate | τ with α=0.97 | corner |
|---|---|---|
| 25 Hz | 1.293 s | 0.12 Hz |
| 50 Hz | 0.647 s | 0.25 Hz |
| 100 Hz | 0.323 s | 0.49 Hz |
| 200 Hz | 0.162 s | **0.98 Hz** (back inside the gait band) |

So τ is fixed at 650 ms and α is computed per sample from the sensor's own
clock, `α = τ / (τ + dt)`, with dt clamped to [2 ms, 100 ms] to reject batched
replay and doze gaps. Measured:

| rate | dt | derived α | realised τ | error | step-response τ |
|---|---|---|---|---|---|
| 25 Hz | 40.0 ms | 0.94203 | 0.6500 s | 0.00% | 640 ms |
| 50 Hz | 20.0 ms | 0.97015 | 0.6500 s | 0.00% | 640 ms |
| 100 Hz | 10.0 ms | 0.98485 | 0.6500 s | 0.00% | 650 ms |
| 200 Hz | 5.0 ms | 0.99237 | 0.6500 s | 0.00% | 650 ms |

The step-response column is measured end-to-end (time to reach 63.2% of a step),
so an error in the update rule would be caught, not just an error in the α
formula. The first sample seeds `gravity = raw` rather than starting from zero.
Otherwise the filter spends ~2 s converging and the gate's first two seconds
are measured against a gravity vector that is mostly wrong.


## Architecture

```
dev.molasses
├── MolassesApp.kt                (@HiltAndroidApp)
├── core/   model/ time/          pure: no Android imports, enforced by PurityTest
├── data/   db/ datastore/ repo/  Room ledger + Proto DataStore hot state
├── di/                           Hilt module  (added; not in the brief's tree)
├── engine/ FrictionEngine, TierPolicy, MonotonicInt
├── monitor/ MolassesAccessibilityService, ForegroundReconciler, ForegroundProbe
├── overlay/ ShutterOverlayManager, OverlayHost, GateOverlayManager,
│            LeaseGateOverlayManager, LockOverlayManager, CallDetector
├── sensing/ MovementDetector, CadenceAnalyzer, StepGate, FallbackImuGate
└── ui/     settings/ gate/ theme/
```

Two additions to the brief's tree, both noted in place: `di/` (Hilt needs a
module, and putting it in `data/` would make a DI concern look like a storage
one) and `monitor/ForegroundProbe.kt` (see bug 3).

### Two systems, and they do not talk

**System A is the lease**: permission to be in an app, measured in real elapsed
time. **System B is friction**: accumulated foreground time, which decides what
a scroll costs. `LeaseManager` and `FrictionEngine` share no state and neither
reads the other.

One number crosses, in one direction: the accumulated total at the moment of
the grant, carried on the lease so the engine can find where "past the lease
you took" begins in its own timebase. The engine reads it; the lease never
reads the engine.

The consequence is the load-bearing part: **a lease buys no friction relief.**
Fifteen minutes buys fifteen minutes without the launch gate, and the scrolling
inside them costs exactly what it would have cost anyway. A lease that also
bought friction would be the one purchase that makes the ladder negotiable, and
there would then be no reason for anyone not to buy it every time.

The gate used to fire on crossing a tier boundary, part way through a session.
It moved to the launch for two reasons. A gate eleven minutes in arrives after
the decision it was meant to inform, and the honest answer to "do you want to
keep going" at that point is always yes. And a checkpoint gate could be waited
out: leaving the app left the gate owed and changed nothing else, so the user
came back to the same gate, but they also came back to the same app, which is
what they wanted.

A lease measures on `elapsedRealtime` alone, with no wall-clock reading at all.
It is **relief**, so its failure mode has to be expiring early rather than
late; a bare wall clock held open by a backward wind would be a lease that
never ends. A reboot therefore ends every lease, which is correct rather than a
limitation: the longest one on offer is fifteen minutes and a reboot outlasts
it. This is the opposite answer from a lock, which has to span reboots to mean
anything. See "Which clock a deadline is measured on" in CLAUDE.md.

### Timebase

`FrictionEngine`'s `nowMs` parameter is **monotonic**
(`SystemClock.elapsedRealtime()`). The brief does not say which clock; this is
the only safe reading, because durations measured on the wall clock are
user-settable and the whole point of the ladder is that accumulated time cannot
be argued with.

The cycle anchor and the last-use stamp do have to survive a reboot, so they
carry a wall-clock stamp as well. Both are held as `StampedInstant` and every
deadline question about them goes through `CycleWindow`, which applies
`ClockTamperClamp`.

### The clock-tamper clamp

The attack is trivial: accumulate 19 minutes, background the app, set the
system clock forward seven hours, come back to a fresh cycle. Wall clock alone
cannot tell that from seven hours of genuine abstinence.

```
same boot, clocks disagree by > 60 s  ->  credit min(wallΔ, elapsedΔ),
                                          cap anchor advance at elapsedΔ,
                                          mark the row CLOCK_WARP
boot count changed                    ->  elapsedRealtime reset; trust wall
                                          only, floored at zero
```

`CycleWindow` is the single caller that turns that verdict into a deadline
answer, and the engine tick, the foreground-entry path and the reconciler all
go through it. They each used to re-derive the comparison locally, which is how
they came to disagree.

There is no separate "suppress the rollover while tampering is detected" rule
any more. A wall clock moved forward credits nothing towards the age of the
anchor, so the cycle simply is not due, and the rule and the clamp cannot drift
apart.

The one gap left is a reboot. `elapsedRealtime` restarts at zero and there is
no monotonic reading that spans the boot, so across one the wall delta is the
only witness available and is trusted, floored at zero. Setting the clock
forward and then rebooting therefore still works. It costs a reboot, which is
a different order of effort from opening the settings app, and closing it would
mean persisting a trusted time source the platform does not offer without a
network permission this app does not have.

### Why there is no foreground service

An `AccessibilityService` is already system-bound and persistent. Adding an FGS
on API 34+ forces a `specialUse` type and a Play justification for no extra
capability. All long-lived work lives in the service's `SupervisorJob` scope.

### Why `TYPE_ACCESSIBILITY_OVERLAY`

A `TYPE_APPLICATION_OVERLAY` window is untrusted, so Android 12's
untrusted-touch-blocking rules apply, and a window that is near-invisible and
touch-consuming is precisely what that feature exists to stop. The platform
would pass touches through regardless of our flags.
`TYPE_ACCESSIBILITY_OVERLAY` borrows the service's own window token, is
trusted, and needs no `SYSTEM_ALERT_WINDOW` grant.

`SYSTEM_ALERT_WINDOW` stays in the manifest for one thing. The "Test Phantom
Stall" button in settings has no service token to borrow, so its preview shows
the timing and the visible tell faithfully without guaranteeing that touches
were eaten. Only the real path guarantees that.

### The one hard performance rule

`onAccessibilityEvent` runs on the service's main thread and events are
delivered serially, so every millisecond there delays the next event. With
`notificationTimeout="0"` the event rate during a scroll burst is high. That
callback does arithmetic on in-memory state and nothing else. Persistence goes
through a conflating channel to a coroutine, and the Room ledger batches on its
own buffered channel, dropping the oldest row rather than blocking the caller.
Losing a ledger row degrades the debug view; blocking that thread degrades the
product.

---

## Proposed and rejected

Both of these were specified, examined and dropped. They are recorded here as
answered rather than deferred, so neither returns as an open question.

**A monochrome mode.** Rejected. The version that would do anything is
unreachable and the version that is reachable does nothing. Draining colour
from the *device* means writing a secure display setting, which needs
`WRITE_SECURE_SETTINGS`: signature or privileged only, granted by adb and not
by a user tap. Draining it from Jitter's own surfaces is all an ordinary app
can do, and those surfaces are already a single-hue phosphor palette on black.
The feature would therefore ship as a switch that greys out the one screen
that is already grey, while Instagram stays in full colour, which is the
screen the whole idea was aimed at.

**A short-form shield**, meaning friction aimed specifically at Reels, Shorts
and TikTok's feed rather than at the app containing them. Rejected, because
knowing which surface is in front means reading the other app's window
content, and this app does not have that and must not acquire it.
`canRetrieveWindowContent` is `false` and `accessibilityFlags` omits
`flagRetrieveInteractiveWindows`, both deliberately: a banking or UPI app
reads the declared profile of every enabled service and decides whether to run
on what it finds. Buying short-form detection with either attribute would
trade a payment app that works for a heuristic. The alternative, inferring the
surface from scroll rhythm, is a guess, and a guess that fires wrongly inside
a messaging thread is worse than no feature. The friction curve already treats
twenty-five minutes as twenty-five minutes whichever surface inside the app
produced it, which is the behaviour the shield was asked for anyway.

## Known limitations

**Known incompatibility**
- **Paytm (`net.one97.paytm`) blocks payments while Jitter's accessibility
  service is enabled.** It checks for the presence of any enabled service, not
  for what that service does, so no amount of scoping or suppression reaches
  it. Workaround: Settings, SAFETY, "Disable for payments", then re-enable
  Jitter from Android Settings afterwards. Google Pay and PhonePe are
  unaffected and complete payments normally. See "Banking and UPI apps".

**Verification**
- The Android layer has never been compiled. See "Build status".
- No measured stall latency. The feasibility question is open.
- Dependency versions in the catalog are unresolved and unpinnable here.
- AGP is 8.11.1, not the 8.7.x the brief suggested: `compileSdk = 36` is not
  recognised before AGP 8.9, so 8.7.3 fails at configuration. 8.11.1 satisfies
  "8.7+" and actually accepts 36.

**Platform ceilings**
- **HOME and RECENTS cannot be blocked from an overlay.** This is intentional,
  not a gap: leaving takes the gate down without buying anything, and the gate
  returns on the next entry or the next scroll. Pretending otherwise would mean
  a gate that vanishes on a home press and grants free usage on return, which
  is why the launch check runs on scroll as well as on entry.
- The gate has no way to stop a user from disabling the accessibility service
  in Settings. Nothing on Android can prevent that, and a friction tool that
  tried would be malware.
- Events are dropped during the reconciliation window on connect (a few hundred
  ms), because the engine must not see a live event before reconciliation
  finishes.
- `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` replay depends on `PACKAGE_USAGE_STATS`.
  Without it, a mid-session process death loses that session's tail. The
  checkpoint cadence bounds that loss to 15 s.

**Permissions beyond the brief's list**
- `READ_PHONE_STATE`, required by `TelephonyCallback.CallStateListener` on API
  31+. Without it that panic path is unavailable; the 8 s hard ceiling and the
  four-tap escape still bound the worst case. Also: `TelephonyCallback` is API
  31+ while `minSdk` is 30, so API 30 uses the deprecated `PhoneStateListener`.
- `QUERY_ALL_PACKAGES`, without which the target picker shows only this app on
  API 30+. It is a Play review question; the picker is the "user selects an app"
  case the policy allows.

**Thresholds that differ from the brief, and why**
- `minHz` entry is **1.20** with a **1.05** exit, replacing the 1.15 guard band.
- `minRms` keeps its **1.5** entry and gains a **1.3** exit.
- Sustain is a leaky bucket at **8 s net credit**, not 8 s continuous. (full reasoning above)
- `minRms` is **1.5**, not the suggested 2.2. The sweep does not support 2.2.
- `minHz` is **1.15**, guarding a stated 1.2 Hz band floor.
- `MIN_PEAKS` stays at **4**; the CV floor moved to a 12 s baseline instead.
- Two thresholds exist that the brief did not specify: the aliasing guard
  (`MAX_SUPPRESSED_RATIO = 0.30`, `SUPPRESSED_SIGNIFICANCE = 0.5`) and
  `regularityWindowMs` / `minCvIntervals`. Both close defects found by the
  sweep; neither can be removed without reopening one.

**Reminders (`$ rem`)**
- **Exact where Android allows it, and the answer says which.** Inexact
  alarms slipped on hardware (a one minute reminder arrived 40 s late), so a
  reminder is scheduled with `setExactAndAllowWhileIdle` whenever
  `canScheduleExactAlarms()` is true: always below Android 12, through
  `USE_EXACT_ALARM` on 13 and later (granted at install), and through
  `SCHEDULE_EXACT_ALARM` on 12 and 12L (declared with
  `maxSdkVersion="32"`, so it is not requested on 13 and later) unless
  revoked under Settings, Apps, Special app access, Alarms and reminders. Otherwise it falls back to
  `setAndAllowWhileIdle`, which with the device idle can be late, by up to an
  hour on Android 12 and later, and the acknowledgement reads INEXACT. If
  neither call succeeds, the reminder is still saved, the acknowledgement
  reads SAVED FOR ... NOT ARMED, and it is armed when the service next
  connects.
  `USE_EXACT_ALARM` is restricted on Google Play to alarm and calendar apps;
  this app is distributed by sideload and F-Droid.
- The acknowledgement stays up for a reading window, 2000 ms plus 250 ms a
  word, between 3 and 10 seconds, then goes by itself. A keystroke, another
  command or leaving the launcher clears it sooner.
- **A missed tone means the text is seen only on the next visit to the
  launcher.** There is no notification, so no notification grant. The tone
  is Jitter's own soft 280 ms chime (`res/raw/jitter_chime.wav`, made by
  `tools/gen-chime.py`), with the device's default notification sound as the
  fallback, played like a notification (notification volume, silenced by Do
  Not Disturb, cut off at 3 seconds),
  and follows the ringer: silent plays nothing, vibrate vibrates once. Several reminders missed while the phone
  was off sound it once, not once each. The text waits on the console, in
  order, until each is dismissed.
- Up to twenty pending. The twenty first is refused, nothing is dropped.
- A date can go before the time: `rem 2026-10-03 9am dentist`,
  `rem 3 oct 18:30 call mum`, `rem oct 3 ...`, `rem tomorrow 9am bins`. The
  dates are `$ days`'s: ISO or a day and a month name, slash dates refused
  as ambiguous, a month name with a year told to use ISO. A date needs a
  time, and a date and time that has already passed is refused rather than
  moved to next year.
- A bare `rem` lists what is pending, soonest first, at most five, each
  with its due time and text, held until the next keystroke. Tapping a row
  shows `[kill]` beside it, and `[kill]` removes that reminder and cancels
  its alarm. Only a pending reminder can be killed. With none it
  says NO PENDING REMINDERS. Fired reminders are not listed: they already
  wait on the console until dismissed, and a list of past ones would be
  something to come back and read.
- Alarms do not survive a reboot or a force stop. They are re-armed on
  `BOOT_COMPLETED` and whenever the accessibility service connects, and one
  that fell due while they were gone fires then, marked late.

**Live gaps**

These are neither platform ceilings nor decisions. Something in the app works
and nothing on screen reaches it, or a number is a guess nobody has checked
against hardware. They are listed apart from "design choices that could go
either way" because that heading is where a reader stops looking for a fix,
and neither of these is settled.

- **Command history is recorded and has no way to reach it.**
  `CommandHistory` deduplicates and caps at twenty, `CycleStateStore` records
  every dispatch, and nothing renders any of it. The recents view on an empty
  prompt was removed deliberately: a blank prompt is a blank prompt, and a
  list that appears when you have typed nothing is a list you did not ask
  for. What was not intended is the leftover, which is that a command is
  reachable only by retyping it in full.

  It is deliberately not fixed yet, because the shape of the fix is the open
  question and not whether one is wanted. A list on an empty prompt is one
  answer and a single recall gesture is another, and picking without evidence
  is how the first one got built. **The report that settles it: a tester
  saying they retyped the same conversion three times.** That says both that
  the gap is real and roughly what the recall wants to be.

  This surfaced from the other end. Clearing a utility's answer when the
  launcher is left was argued for on the grounds that re-running it costs two
  keystrokes, which is not true on any build that has shipped. The decision
  stands on its own reasoning; the supporting claim did not, and this entry
  exists so it is not repeated.

- **Window insets are a hardcoded 44dp and nothing reads a real inset.**
  It works on the hardware this was built on and the number is not related to
  any measurement. It is a few dp short of a 48dp three button bar, short on
  a device whose status bar covers a tall cutout, and 18dp clears nothing in
  landscape. Held for field reports rather than guessed at a second time, and
  immersive mode on the console hides the symptom on the one screen anyone
  looks at rather than fixing it anywhere. Full reasoning in CLAUDE.md,
  "Window insets are a constant, and that is a known fault".

**Design choices that could go either way**
- The fused pipeline's thresholds are **unmeasured**. They are seeded from the
  IIR set and flagged `UNCALIBRATED` in the debug screen. Re-run
  `CalibrationSweep` against real devices before trusting them.
- `minSamples` is a fixed count (40), so the analyzer's warm-up is 1.6 s at
  25 Hz and 0.2 s at 200 Hz. Harmless today because the peak-count test binds
  first at every rate, but it is a rate dependence of the kind §1.2 is about.
- `SCROLL` ledger rows are written from tier 1 on only. Tier-0 scrolling would
  dominate the table while reconstructing nothing the accumulated total does
  not already say.
- A single session is capped at 12 h and a negative monotonic delta credits
  zero, so a clock bug cannot fling a user to the terminal tier.
- The cycle deadline is checked on foreground entry, on the 15 s checkpoint
  tick, and on service connect. Entry alone misses a session that runs past the
  deadline; the tick alone misses a cycle that comes due while the process is
  dead, which is the common case.
- A rollover with a target app open re-anchors at that instant, because the
  user is in a target app and that is what the anchor means. A rollover with
  nothing open leaves the cycle unanchored, so the next foreground entry starts
  it rather than burning window on someone who is not using anything.
- A `ForegroundReplay` seed from `open_session_pkg` is *not* shortened by a
  later `ACTIVITY_RESUMED` in the same window. Apps fire `ACTIVITY_RESUMED` per
  activity, so trusting the later one would hand out a bypass: kill the
  process, relaunch, and the reconciler forgets everything before the new
  resume. Under-crediting is the exploitable direction here.

---

## What this app does to your phone

In plain language, because an app that deliberately makes a phone feel broken
owes you this.

**It watches two things, only in the apps you pick:** which app is in the
foreground, and when you scroll. That is all. It does not and cannot read your
screen. `canRetrieveWindowContent` is `false` in the service config, so the
text, images, messages and accounts in those apps are not visible to it. It has
no network permission and sends nothing anywhere.

**It will make your phone ignore your finger.** After five minutes in a target
app, every scroll causes a blackout (one second at first, up to five later)
during which touches in that app do nothing. It looks and feels like the phone
has frozen. That is the intended effect.

**So you can always tell it is us.** While a blackout is active, a thin grey bar
is drawn along the top edge of the screen. It is themeable and there is no
setting that removes it, because without it you could not distinguish a
deliberate stall from a failing touchscreen.

**Four escape hatches, always available.** Tap the top-left corner of the screen
four times within 1.5 seconds and the blackout releases immediately. It also
releases when the screen turns off, when you leave the app, and when a phone
call arrives. And it is never armed for more than eight continuous seconds, at
any tier, no matter how much you scroll.

**It will stop you at the door.** Opening one of your target apps puts a
full-screen black page in front of it: a face, the app's name, how long you
have spent in it today, how long this cycle, how many times you have opened it,
and a countdown. Eight seconds the first time. There is nothing to do but wait,
and there is no sentence on the screen telling you what to think about the
numbers.

**Then you choose how long you are staying.** At zero the countdown is replaced
by four answers: 5m, 10m, 15m, or TAKE ME OUT, which sends you home. There is
no unlimited option, and pressing back does the same thing as TAKE ME OUT.

**When a lease runs out, the app goes home under the gate.** The gate
checks your lease at its deadline, without waiting for you to touch anything,
and the LEASE EXPIRED gate sends the app to the background as it appears, so
a video stops because its app stopped, not because anything argued with its
sound. The gate stays up over the home screen. [ ARCHITECT'S SPACE ] is there
from the start and leaves (back does the same), [ BLOCK THIS APP ] is there
from the start, and the lease options appear at zero; taking one reopens the
app where you left it. Leaving grants nothing and resets nothing. The walking
gate and a lock that arrives mid-session send the app home the same way.

**Each lease makes the next gate longer.** Eight seconds, then twelve, sixteen,
twenty, up to thirty, for as long as the cycle lasts. Backing out costs nothing
extra: only leases you actually take lengthen it. The count resets when the
cycle does.

**A lease buys time and nothing else.** It does not reset your accumulated
time, does not lower a tier, and does not make the blackouts shorter. Staying
in the app past the lease you took makes them arrive sooner. Within a cycle the
friction only ever increases.

**It can ask you to get up and walk instead,** but only if you turn that on,
and only past twenty five minutes in one app in one cycle. Settings → The gate
→ What the gate asks for. That replaces the countdown with the movement gate:
about twelve steps, or eight seconds of walking-shaped motion if your phone has
no step sensor. **If walking is not something you can or should do,** the same
setting offers a 25 second untimed typing task in its place. No sensors run in
that mode. You can always leave with HOME or RECENTS; that takes the gate down
without buying anything, and it comes back when you next scroll.

**The counter resets six hours after the cycle started.** The cycle starts
the first time you open one of your target apps from a clean state, and ends
six hours later whether you kept using the apps or not. There is no setting
for it.

**Battery.** Motion sensors run only while a gate is open on screen, and are
unregistered the moment it passes, is abandoned, or times out after 90 seconds.
Nothing samples in the background. No wake locks are ever held.

**Uninstalling works normally.** So does turning the accessibility service off
in Android's own Settings. Nothing here tries to stop you, and anything that
did would be malware.
