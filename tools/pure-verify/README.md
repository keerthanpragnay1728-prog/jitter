# pure-verify

A standalone pure-JVM Gradle build that compiles and runs the Android-free
subset of `:app` directly out of `app/src`, against Maven Central only.

It exists because this project was authored in an environment where the Android
toolchain is unreachable (see the root README, "Build status"), so
`./gradlew assembleDebug` could not be run. The pure core *can* be verified
there, and this is how.

It is **not** part of the app build: `settings.gradle.kts` at the root includes
only `:app`, as the brief requires. Nothing in `:app` depends on this
directory.

It is a **standalone build with its own wrapper**, so:

```
cd tools/pure-verify && ./gradlew test --rerun-tasks
```

`./gradlew :tools:pure-verify` from the repo root will not resolve. The root
`settings.gradle.kts` includes only `:app`.

Covers all of `dev.molasses.core` and `dev.molasses.engine`, plus the pure
sensing maths (`CadenceAnalyzer`, `StepGate`, `FallbackImuGate`,
`GravitySplitter`, `Thresholds`) and the tests under `app/src/test`.

`PurityTest` fails the build if anything in that set grows an `android.*` or
`androidx.*` import, and cross-checks its own list against the `pureMain` list
in this directory's `build.gradle.kts` so the two cannot drift apart.

`CalibrationSweep` asserts nothing. It prints the measurement tables the
thresholds are derived from. Its output is reproduced in the root README under
"Threshold recalibration".
