# Molasses

A progressive-friction focus tool for Android. Codename **Molasses**.

Instagram, X and YouTube are never blocked. Instead, the longer you use them
inside a six-hour cycle, the more the phone *feels* like it is failing.

| Cumulative foreground time | Behaviour |
|---|---|
| 0–5 min | Normal |
| **at 5 min** | Full-screen movement gate (physical movement required) |
| 5–10 min | Phantom Stall: 1000 ms touch blackout on every scroll |
| **at 10 min** | Movement gate |
| 10–15 min | Phantom Stall: 3000 ms |
| **at 15 min** | Movement gate |
| 15–20 min | Phantom Stall: 5000 ms |
| **at 20 min** | Terminal tier — 5000 ms stall, gate re-arms every 5 min indefinitely |

**The invariant:** clearing a movement gate unlocks the *next* tier of usage.
It never resets accumulated time, never lowers the stall duration, and never
rewinds `tierIndex`. Gates are a toll, not a refund.

---

## Build status — read this first

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
| **137 JVM tests, 0 failures** | `core/**`, `engine`, and the pure sensing maths, compiled and executed for real |
| **Purity is enforced, not asserted** | `PurityTest` walks the source tree and fails if the pure set grows an `android.*`/`androidx.*` import — and cross-checks its own list against the harness's, so the two cannot drift |
| **Six real bugs found and fixed** | see "Bugs the harness caught" |

Reproduce:

```
cd tools/pure-verify && ./gradlew test --rerun-tasks
```

`tools/pure-verify` is a **standalone Gradle build**, not an included module —
the root `settings.gradle.kts` includes only `:app`, as the brief requires. So
`./gradlew :tools:pure-verify` will not resolve; it has its own wrapper.

```
CadenceAnalyzerTest          9 tests, 0 failed
CadenceBandEdgeTest          3 tests, 0 failed
CalibrationSweep             1 tests, 0 failed
ChannelSpecTest              7 tests, 0 failed
ClockTamperClampTest         8 tests, 0 failed
CvEstimatorTest              4 tests, 0 failed
FallbackImuGateTest         15 tests, 0 failed
ForegroundReplayTest        12 tests, 0 failed
FrictionEngineTest          25 tests, 0 failed
IirFilterTest                6 tests, 0 failed
LatencySegmentsTest         14 tests, 0 failed
MonotonicIntTest             4 tests, 0 failed
MovementRejectionTest       13 tests, 0 failed
PurityTest                   2 tests, 0 failed
StepGateTest                 9 tests, 0 failed
TierPolicyTest               5 tests, 0 failed
TOTAL                      137 tests, 0 failed
```

### What is NOT verified

Everything that touches the Android framework: `MolassesAccessibilityService`,
`ShutterOverlayManager`, `OverlayHost`, `GateOverlayManager`,
`MovementDetector`, `ForegroundReconciler`, the Room and Proto DataStore
layers, and all Compose UI. It is written to compile and has been checked by
hand and by cross-reference sweeps, but **it has not been through a compiler.**
Expect a first-compile pass to surface import and signature fixes. Treat every
version number in `gradle/libs.versions.toml` as unresolved.

### The measured latency distribution — still not available

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
WMS relayouts and InputDispatcher refreshes its window handles — one or more
frames later. C exists to measure exactly that gap.

Ring buffer of 100 per package per segment, p50/p95 rather than a mean
(latency distributions are right-skewed and a mean hides the tail that breaks
the illusion). Negative D samples are discarded and counted — the sink can
absorb a touch that was already in flight when the stall armed, whose
`eventTime` precedes the scroll.

```
STALL_LATENCY: [TARGET: com.instagram.android] A=34 B=2 C=18 D=71 | p50(D)=68 p95(D)=112 n=50

adb shell dumpsys activity service \
    dev.molasses/.monitor.MolassesAccessibilityService
```

**The central feasibility question remains open until someone runs that on
hardware.**

## §0.1 — the two ambiguities, resolved

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
Both are implemented, selectable in settings, and the default is
**`ABSTINENCE_6H`**: six continuous hours with zero foreground time on *any*
target app.

`FIXED_WINDOW_6H` is offered because the brief's wording permits it, but it is
not the default for a concrete reason: under a fixed window a user can wait out
the clock while still scrolling, which makes the entire friction ladder
decorative.

### 3. A third ambiguity I hit — `tierIndex` monotonicity vs. cycle reset

Not in the brief, but unavoidable: "`tierIndex` … monotonic, never decremented"
and "the cycle resets" are in direct tension, because a reset must return the
user to normal behaviour.

**Resolution:** the invariant is scoped to a cycle. A rollover *replaces* each
`AppState` with a fresh one rather than assigning a lower value to the existing
counter — which would throw, by design. This keeps "never decremented" true for
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
   instant, so the reconciler replays from exactly the point the snapshot
   accounts up to — credited once, never twice.

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
   package name — the only route is `window.root.packageName`, and that is a
   content read, which `canRetrieveWindowContent="false"` gives up. It now uses
   `UsageStatsManager` (`monitor/ForegroundProbe.kt`), needing only the
   `PACKAGE_USAGE_STATS` grant the app already requires. It returns `null` for
   "no information", never "nothing is in front", so a missing grant cannot
   read as the user leaving every app.

6. **`OverlayHost` could only ever be shown once.** A `LifecycleRegistry`
   cannot return to `CREATED` after `DESTROYED`; it throws. The class is now
   explicitly single-use and `GateOverlayManager` builds a fresh host per gate.

---

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
| dynamic RMS floor | 1.2 m/s² | 2.52 | 3.29 | **1.5** | Preserves physical sensitivity exactly. See below — **not** the suggested 2.2. |
| peak count | ≥ 4 in 3.0 s | 5 | 6 | **≥ 4 in 3.5 s** | Window widened per §2; count unchanged. |
| cadence band | 1.2–2.6 Hz | 1.72 | 1.74 | **1.15–2.6 Hz** | Low edge needs a guard band; see §2 finding below. |
| CV | < 0.35 | 0.024 | 0.029 | **0.02–0.35** | Two-sided per §3, floor measured over a 12 s baseline. |
| vertical energy share | ≥ 0.50 | 0.98 | 0.97 | **≥ 0.50 (unchanged)** | Ratio barely moved: the filter attenuated the projection and the magnitude near-identically. |
| peak \|a\| ceiling | 25 m/s² | 7.82 | 10.55 | **25 (unchanged)** | Violent shake still reads 28.2 vs 26.0 — clears the ceiling on both. |
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
| thumb tremor | old | 0.11 | 0 | — | — | 0.80 | 0.25 | 2.0 | NOT_ENOUGH_MOTION |
| | **new** | **0.13** | **0** | **—** | **—** | **0.80** | **0.31** | **2.0** | **NOT_ENOUGH_MOTION** |
| static | old | 0.00 | 0 | — | — | 0.00 | 0.00 | 0.0 | NOT_ENOUGH_MOTION |
| | **new** | **0.00** | **0** | **—** | **—** | **0.00** | **0.00** | **0.0** | **NOT_ENOUGH_MOTION** |

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

### The RMS floor is 1.5, not 2.2 — the sweep does not support 2.2

| input peakA | new rms | old floor 1.2 | **chosen 1.5** | suggested 2.2 | 2.6 |
|---|---|---|---|---|---|
| 4.0 | 1.10 | no | no | no | no |
| 5.0 | 1.37 | yes | no | no | no |
| 6.0 | 1.64 | yes | **yes** | no | no |
| 8.0 | 2.19 | yes | **yes** | no | no |
| 10.0 | 2.74 | yes | **yes** | yes | yes |
| 12.0 | 3.29 | yes | **yes** | yes | yes |

Two facts decide it.

**In physical terms, 2.2 is a large tightening nobody asked for.** Old pipeline:
RMS = 0.21 × peak, floor 1.2, so a walker needed a 5.7 m/s² heel strike. New
pipeline: RMS = 0.274 × peak. A floor of 1.5 needs 5.5 m/s² — the same walker.
A floor of 2.2 needs 8.0 m/s², **41% more physical motion**, silently making
the gate harder to clear.

**And 2.2 buys nothing.** The RMS floor exists to reject one thing: thumb
tremor. That measures 0.13, and the brief's own stated tremor ceiling is
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

### §2 — the band floor fails on the cadence *estimate*, not the peak count

The patch predicted the peak count would bind at 1.2 Hz. With the window at
3.5 s it does not: the count lands on 4 or 5 across phase, and 4 satisfies the
gate. The actual binding constraint is the cadence estimate's sampling
variance.

Measured cadence over a 3.5 s window is a 3–5 interval sample statistic. A
walker whose true cadence is exactly 1.20 Hz measures **1.190 Hz** as often as
1.21. A `>` threshold placed at 1.2 therefore rejects about half their windows —
and since the gate needs 8 *continuous* seconds, a 1.2 Hz walker would never
clear it at all.

So `minHz` carries a guard band at **1.15**. The *stated* band is still
1.2–2.6 Hz; the threshold sits one measurement-spread below it. `maxHz` gets no
guard band, deliberately: the two edges have opposite failure costs — the low
edge exists to admit real users and should err toward admitting, the high edge
exists to exclude shaking and should err toward excluding.

With that, the §2 sweep passes 8/8 at every phase from 1.20 Hz up:

| Hz | φ=0.00 | φ=0.13 | φ=0.25 | φ=0.38 | φ=0.50 | φ=0.63 | φ=0.75 | φ=0.88 |
|---|---|---|---|---|---|---|---|---|
| 1.20 | ok(4) | ok(5) | ok(5) | ok(4) | ok(4) | ok(4) | ok(4) | ok(4) |
| 1.25 | ok(4) | ok(4) | ok(4) | ok(4) | ok(4) | ok(5) | ok(5) | ok(5) |
| 1.30 | ok(4) | ok(5) | ok(5) | ok(5) | ok(5) | ok(5) | ok(4) | ok(4) |
| 1.35 | ok(5) | ok(5) | ok(5) | ok(4) | ok(4) | ok(4) | ok(5) | ok(5) |

### §3 — a per-window CV floor is incompatible with a continuous sustain

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
1.2 Hz that is 4 × 833 = 3333 ms inside a 3500 ms window — 167 ms of slack.
Measured: 17 of 32 band-floor combinations fail. The §2 and §3 remedies are in
direct conflict at this window width. (`MIN_PEAKS = 5` with a 4.5 s window does
pass 0/32 failures, if you would rather go that way.)

**Third, and decisive:** even 4.6% per window is fatal, because the sustain
requires *every* window to pass for 8 continuous seconds. At 100 Hz that is
~800 overlapping windows. Measured end-to-end, before this was fixed: a
jittered 1.8 Hz walk at 100 Hz had its streak broken at t=3.15 s and again at
t=11.6 s and never passed — while **the identical signal at 50 Hz passed**, the
only difference being that coarser timestamp quantisation added enough noise to
keep the estimate off the floor. A gate that works at 50 Hz and not at 100 Hz
is not a threshold problem, it is a structural one.

The fix keeps the floor at 0.02 and changes only *what it is measured over*:
the CV **floor** uses a 12 s baseline (≥8 intervals required, else it
abstains), while the CV **ceiling** keeps the 3.5 s analysis window. That
asymmetry is principled — the ceiling asks "is this erratic right now" and must
be responsive; the floor asks "has this been machine-regular throughout" and
wants a long, stable baseline. At 1.8 Hz the floor engages ~4.7 s into a gate,
well before an 8 s sustain could complete, so a metronome is still caught.

After: all four delivery rates pass at ~10 s.

| rate | transitions |
|---|---|
| 25 Hz | WAITING → PHONE_STATIONARY(1560) → NEED_MORE_STEPS(1840) → SUSTAINING(2040) → **PASSED(10040)** |
| 50 Hz | … SUSTAINING(2000) → **PASSED(10000)** |
| 100 Hz | … SUSTAINING(1990) → **PASSED(9990)** |
| 200 Hz | … SUSTAINING(1990) → **PASSED(9990)** |

**A correction to the patch's premise, while here.** Bessel's correction makes
the *variance* unbiased, not the standard deviation: `E[s] = σ·c4(n)`, and
`c4(3) = 0.886`. So a CV from 3 intervals still reads ~11% low *with* the
correction — measured 0.0531 against a true 0.06, matching σ·c4 to three
decimal places. Bessel removes the 18% population bias and leaves an 11% one.
`CvEstimatorTest` asserts against `c4(n)` exactly rather than a loose
tolerance.

### A real bypass on the fused path, found by the sweep

The 250 ms refractory is a hard decimator. Anything faster than 1/refractory
(4 Hz) has peaks dropped, and what survives can land anywhere — including the
middle of the pass band. Measured on the fused pipeline:

| shake Hz | jitter | fused measured hz | fused cv | verdict *before* the guard |
|---|---|---|---|---|
| 4.5 | 0.15 | 2.17 | 0.035 | **PASSED** |
| 5.0 | 0.15 | 2.50 | 0.053 | **PASSED** |

That is a working bypass: shake at 4.5 Hz and the gate opens. The IIR path
resisted it only because its filter perturbs peak timing enough to break the
alias — luck, not design, and §1.1 asks us to *prefer* the fused path.

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

Threshold 0.30 sits in a wide gap. All shake rates 3–6 Hz, jittered or not, now
read `CADENCE_TOO_FAST` on both pipelines.

## Sensor pipeline hierarchy (§1)

```
1. TYPE_STEP_DETECTOR                        (needs ACTIVITY_RECOGNITION)
2. TYPE_LINEAR_ACCELERATION + TYPE_GRAVITY   (platform fusion)
3. TYPE_ACCELEROMETER + GravitySplitter      (our IIR)
```

Level 2 requires **both** fused sensors. `TYPE_LINEAR_ACCELERATION` alone is not
enough — the vertical-energy-share and gravity-angle tests both need a gravity
vector, and re-deriving one would reintroduce the very filter this level exists
to avoid. A device with linear acceleration but no gravity sensor drops to
level 3 rather than running a half-fused pipeline.

Levels 2 and 3 are separate **calibration domains** with separate threshold
sets behind one interface. `Thresholds.FUSED` is seeded from `Thresholds.IIR`
and marked `UNCALIBRATED`, surfaced as such in the debug screen, because the
fused sensor's internal high-pass has a corner we neither control nor can
query. The active path is published on `GateProgress` and stamped on **every**
ledger row — when a gate pass looks wrong in hindsight, the first question is
which domain produced it, and that has to be answerable from the ledger alone.

### α is derived from measured dt, never hardcoded

`SENSOR_DELAY_GAME` is a hint, not a contract. A fixed α means a time constant
that drifts with the device:

| rate | τ with α=0.97 | corner |
|---|---|---|
| 25 Hz | 1.293 s | 0.12 Hz |
| 50 Hz | 0.647 s | 0.25 Hz |
| 100 Hz | 0.323 s | 0.49 Hz |
| 200 Hz | 0.162 s | **0.98 Hz** — back inside the gait band |

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
formula. The first sample seeds `gravity = raw` rather than starting from zero —
otherwise the filter spends ~2 s converging and the gate's first two seconds
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
├── overlay/ ShutterOverlayManager, OverlayHost, GateOverlayManager
├── sensing/ MovementDetector, CadenceAnalyzer, StepGate, FallbackImuGate
└── ui/     settings/ gate/ theme/
```

Two additions to the brief's tree, both noted in place: `di/` (Hilt needs a
module, and putting it in `data/` would make a DI concern look like a storage
one) and `monitor/ForegroundProbe.kt` (see bug 3).

### Timebase

`FrictionEngine`'s `nowMs` parameter is **monotonic**
(`SystemClock.elapsedRealtime()`). The brief does not say which clock; this is
the only safe reading, because durations measured on the wall clock are
user-settable and the whole point of the ladder is that accumulated time cannot
be argued with. Wall-clock concerns — the cycle anchor and the abstinence
window — go through `WallClock` and are clamped by `ClockTamperClamp` before
they reach the engine.

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

A rollover is suppressed entirely while tampering is detected — not permanently,
since the next connect with agreeing clocks re-evaluates against the real
`last_target_use_wall_ms`.

### Why there is no foreground service

An `AccessibilityService` is already system-bound and persistent. Adding an FGS
on API 34+ forces a `specialUse` type and a Play justification for no extra
capability. All long-lived work lives in the service's `SupervisorJob` scope.

### Why `TYPE_ACCESSIBILITY_OVERLAY`

A `TYPE_APPLICATION_OVERLAY` window is untrusted, so Android 12's
untrusted-touch-blocking rules apply — and a window that is near-invisible and
touch-consuming is precisely what that feature exists to stop. The platform
would pass touches through regardless of our flags.
`TYPE_ACCESSIBILITY_OVERLAY` borrows the service's own window token, is
trusted, and needs no `SYSTEM_ALERT_WINDOW` grant.

`SYSTEM_ALERT_WINDOW` stays in the manifest for exactly one thing: the "Test
Phantom Stall" button in settings, which has no service token to borrow. That
preview is therefore *not* a guarantee that touches were eaten — only the real
path is.

### The one hard performance rule

`onAccessibilityEvent` runs on the service's main thread and events are
delivered serially, so every millisecond there delays the next event — and with
`notificationTimeout="0"` the event rate during a scroll burst is high. That
callback does arithmetic on in-memory state and nothing else. Persistence goes
through a conflating channel to a coroutine; the Room ledger batches on its own
buffered channel and drops the oldest row rather than ever blocking the caller.
Losing a ledger row degrades the debug view; blocking that thread degrades the
product.

---

## Known limitations

**Verification**
- The Android layer has never been compiled. See "Build status".
- No measured stall latency. The feasibility question is open.
- Dependency versions in the catalog are unresolved and unpinnable here.
- AGP is 8.11.1, not the 8.7.x the brief suggested: `compileSdk = 36` is not
  recognised before AGP 8.9, so 8.7.3 fails at configuration. 8.11.1 satisfies
  "8.7+" and actually accepts 36.

**Platform ceilings**
- **HOME and RECENTS cannot be blocked from an overlay.** This is intentional,
  not a gap: leaving the app *pauses* the gate rather than clearing it, and the
  gate returns on the next scroll. Pretending otherwise would mean a gate that
  vanishes on a home press and grants free usage on return.
- The gate has no way to stop a user from disabling the accessibility service
  in Settings. Nothing on Android can prevent that, and a friction tool that
  tried would be malware.
- Events are dropped during the reconciliation window on connect (a few hundred
  ms), because the engine must not see a live event before reconciliation
  finishes.
- `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` replay depends on `PACKAGE_USAGE_STATS`.
  Without it, a mid-session process death loses that session's tail — bounded
  to 15 s by the checkpoint cadence.

**Permissions beyond the brief's list**
- `READ_PHONE_STATE`, required by `TelephonyCallback.CallStateListener` on API
  31+. Without it that panic path is unavailable; the 8 s hard ceiling and the
  four-tap escape still bound the worst case. Also: `TelephonyCallback` is API
  31+ while `minSdk` is 30, so API 30 uses the deprecated `PhoneStateListener`.
- `QUERY_ALL_PACKAGES`, without which the target picker shows only this app on
  API 30+. It is a Play review question; the picker is the "user selects an app"
  case the policy allows.

**Thresholds that differ from the brief, and why** — full reasoning above
- `minRms` is **1.5**, not the suggested 2.2. The sweep does not support 2.2.
- `minHz` is **1.15**, guarding a stated 1.2 Hz band floor.
- `MIN_PEAKS` stays at **4**; the CV floor moved to a 12 s baseline instead.
- Two thresholds exist that the brief did not specify: the aliasing guard
  (`MAX_SUPPRESSED_RATIO = 0.30`, `SUPPRESSED_SIGNIFICANCE = 0.5`) and
  `regularityWindowMs` / `minCvIntervals`. Both close defects found by the
  sweep; neither can be removed without reopening one.

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
- The abstinence check runs on foreground entry and on service connect. A cycle
  coming due while the process is dead is the common case, so the connect path
  has to check too.
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
screen — `canRetrieveWindowContent` is `false` in the service config, so the
text, images, messages and accounts in those apps are not visible to it. It has
no network permission and sends nothing anywhere.

**It will make your phone ignore your finger.** After five minutes in a target
app, every scroll causes a blackout — one second at first, up to five later —
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

**It will ask you to get up and walk.** At each five-minute mark a full-screen
gate appears and will not go away until you have walked for a bit — about
twelve steps, or eight seconds of walking-shaped motion if your phone has no
step sensor. You can always leave with HOME or RECENTS; that pauses the gate
instead of clearing it, and it comes back when you next scroll. **If walking is
not something you can or should do, turn on Settings → Movement gate →
Alternative challenge**, which replaces it with a 25-second untimed typing
task. No sensors run in that mode.

**Clearing a gate buys you five more minutes and nothing else.** It does not
reset your time and it does not make the blackouts shorter. Within a cycle the
friction only ever increases.

**The only way out is to stop.** By default the counter resets after six
continuous hours with no time in any of your target apps. You can switch it to
a plain six-hour clock in settings, but then waiting it out while still
scrolling works, which rather defeats the object.

**Battery.** Motion sensors run only while a gate is open on screen, and are
unregistered the moment it passes, is abandoned, or times out after 90 seconds.
Nothing samples in the background. No wake locks are ever held.

**Uninstalling works normally.** So does turning the accessibility service off
in Android's own Settings. Nothing here tries to stop you, and anything that
did would be malware.
