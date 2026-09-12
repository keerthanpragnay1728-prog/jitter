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

```
cd tools/pure-verify
gradle test --rerun-tasks
```

Covers `core/model`, `core/time`, `engine`, and the pure sensing maths
(`CadenceAnalyzer`, `StepGate`, `FallbackImuGate`), plus the tests under
`app/src/test`. `PurityTest` fails the build if anything in that set grows an
`android.*` or `androidx.*` import.
