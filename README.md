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
| **96 JVM tests, 0 failures** | `core/model`, `core/time`, `engine`, and the pure sensing maths, compiled and executed for real |
| **Purity is enforced, not asserted** | `PurityTest` walks the source tree and fails if the pure set grows an `android.*`/`androidx.*` import |
| **Two real bugs found and fixed** | see "Bugs the harness caught" below |

Reproduce:

```
cd tools/pure-verify && gradle test --rerun-tasks
```

```
CadenceAnalyzerTest       20 tests, 0 failed
CalibrationProbe           1 tests, 0 failed
ClockTamperClampTest       8 tests, 0 failed
FallbackImuGateTest       11 tests, 0 failed
ForegroundReplayTest      12 tests, 0 failed
FrictionEngineTest        25 tests, 0 failed
MonotonicIntTest           4 tests, 0 failed
PurityTest                 1 tests, 0 failed
StepGateTest               9 tests, 0 failed
TierPolicyTest             5 tests, 0 failed
TOTAL                     96 tests, 0 failed
```

### What is NOT verified

Everything that touches the Android framework: `MolassesAccessibilityService`,
`ShutterOverlayManager`, `OverlayHost`, `GateOverlayManager`,
`MovementDetector`, `ForegroundReconciler`, the Room and Proto DataStore
layers, and all Compose UI. It is written to compile, it has been checked by
hand and by cross-reference sweeps, but **it has not been through a compiler.**
Expect a first-compile pass to surface import and signature fixes. Treat every
version number in `gradle/libs.versions.toml` as unresolved: none of them could
be fetched.

### The measured latency distribution — not available

§11 asked me to report the latency numbers. I cannot, and I will not invent
them. The `latency_probe` (§11.1) is implemented at
`app/src/androidTest/.../probe/LatencyProbeTest.kt` and the debug screen parses
requested-vs-actual armed durations back out of the Room ledger, but both need
a device. **The central feasibility question — whether the stall blackout
starts within a frame or two of the flick — is therefore still open.** Run:

```
adb shell appops set dev.molasses SYSTEM_ALERT_WINDOW allow
adb shell am instrument -w -e class dev.molasses.probe.LatencyProbeTest \
    dev.molasses.test/androidx.test.runner.AndroidJUnitRunner
```

One honesty note on that probe: it measures the *arming* path (flag mutation →
`updateViewLayout` → window-update round trip), which is the part under our
control. It cannot measure how long the platform took to deliver the scroll
event in the first place, because nothing in the app observes the finger. Read
its output as a floor on perceived latency, not the whole of it.

---

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

Two more were caught by review rather than by tests, and are called out because
both were code that pretended to work:

3. **The exit watchdog could not work as specified.** It was written to read
   the foreground package from `AccessibilityWindowInfo`, which carries no
   package name — the only route is `window.root.packageName`, and that is a
   content read, which `canRetrieveWindowContent="false"` gives up. It now uses
   `UsageStatsManager` (`monitor/ForegroundProbe.kt`), needing only the
   `PACKAGE_USAGE_STATS` grant the app already requires. It returns `null` for
   "no information", never "nothing is in front", so a missing grant cannot
   read as the user leaving every app.

4. **`OverlayHost` could only ever be shown once.** A `LifecycleRegistry`
   cannot return to `CREATED` after `DESTROYED`; it throws. The class is now
   explicitly single-use and `GateOverlayManager` builds a fresh host per gate.

---

## Two spec details that do not hold as written

### The IMU peak-count rule binds before the stated frequency floor

§8's table gives a pass band of **1.2–2.6 Hz** *and* requires **≥ 4 peaks in a
3 s window**. Those are not simultaneously satisfiable at the bottom of the
band: four peaks need three intervals, and at 1.2 Hz a trailing 3 s window
holds only three (1.2 × 3 = 3.6 expected, so it depends on phase). Measured:

```
walk 1.2Hz   ok=false  reason=NEED_MORE_STEPS   peaks=3  hz=1.20
walk 1.4Hz   ok=true   reason=PASSED            peaks=4  hz=1.40
```

The effective floor is ≈1.33 Hz, not 1.2 Hz, and a genuinely slow walker is
rejected. **Both thresholds are implemented exactly as written** and the
behaviour is pinned by
`CadenceAnalyzerTest.the peak-count rule binds before the stated frequency
floor`. I did not retune it unilaterally. The fix is a one-line decision:
`MIN_PEAKS = 3`, or widen the window to 3.5 s. `MIN_PEAKS = 3` still rejects
the cheat the rule exists for — a single jerk gives one peak.

### α = 0.8 attenuates the band of interest by about a third

§8 specifies a gravity low-pass of `α = 0.8`. At `SENSOR_DELAY_GAME` (~50 Hz)
that puts the filter's −3 dB point at ≈2 Hz — the middle of the 1.2–2.6 Hz band
we are trying to measure. The "gravity" estimate therefore absorbs a large
share of the gait signal, and `a = raw − g` keeps only ~65% of it (measured:
a 12.0 m/s² input peak reads as 7.82 m/s²).

The consequence is a real sensitivity limit: `RMS > 1.2 m/s²` needs a heel
strike of roughly 5.5 m/s² or more.

```
walk A=12  rms=2.75  PASSED
walk A=7   rms=1.60  PASSED
walk A=6   rms=1.37  PASSED
walk A=4   rms=0.92  NOT_ENOUGH_MOTION
```

A soft-footed walker, or a phone loose in a bag rather than held or pocketed,
lands under the floor. Both thresholds are as specified; if field testing shows
false rejections, α is the knob to turn first (0.9 at 50 Hz moves the corner to
≈0.9 Hz and roughly doubles retention), not the RMS floor.

---

## Anti-cheat: measured behaviour

Every rejection class is exercised against synthetic signals
(`app/src/test/.../sensing/SignalGen.kt`) and each is rejected by the specific
test intended to catch it. Full table from `CalibrationProbe`:

```
signal           verdict  reason              rms   peaks  hz    cv    vert  peak   tilt
walk 1.8Hz       PASS     PASSED              2.75    6   1.80  0.01  0.98   7.82  40.0
walk 1.3Hz       PASS     PASSED              2.25    4   1.30  0.01  0.98   7.84  40.0
walk 2.4Hz       PASS     PASSED              2.96    7   2.40  0.02  0.98   7.74  40.0
walk jitter 0.6  PASS     PASSED              2.52    5   2.04  0.18  0.98   7.79  40.0
pocket tilt 26°  PASS     PASSED              2.75    6   1.80  0.01  0.98   7.82  26.0
desk tap         reject   PHONE_STATIONARY    3.16    6   1.80  0.01  0.99   8.99   0.6
lateral wave     reject   MOTION_NOT_VERTICAL 3.14    6   1.80  0.01  0.16   8.95  40.5
violent shake    reject   TOO_VIOLENT        12.56   10   3.38  0.13  0.97  26.04  60.2
thumb tremor     reject   NOT_ENOUGH_MOTION   0.11    0   0.00    --  0.80   0.25   2.0
walk 2.8Hz       reject   CADENCE_TOO_FAST    3.27    9   2.80  0.02  0.98   7.66  40.0
tilt 10°         reject   PHONE_STATIONARY    2.75    6   1.80  0.01  0.98   7.82  10.1
```

The desk-tap row is the interesting one. A phone lying flat and being tapped
produces a signal that clears the motion floor (3.16 > 1.2) *and* sits squarely
in the cadence band (1.80 Hz, CV 0.01) *and* is 99% vertical. Only the
gravity-vector angle test separates it from walking. That is why the tilt
requirement cannot be dropped, and why the seven tests are an AND and not a
score.

---

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

**Design choices that could go either way**
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
