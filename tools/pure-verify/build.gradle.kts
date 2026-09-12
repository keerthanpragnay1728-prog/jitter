// Pure-JVM verification harness for Molasses.
//
// The Android toolchain (AGP, androidx, the SDK itself) is unreachable from
// this environment: every Google Maven and SDK URL redirects to
// dl.google.com, which the egress policy denies. This harness therefore
// compiles and runs the subset of the app that the brief specifies as pure --
// no Android imports -- straight from app/src, against Maven Central.
//
// It is NOT a substitute for `./gradlew assembleDebug`. It proves the engine
// invariants and the sensing maths, nothing about the Android layer.
plugins { kotlin("jvm") version "2.1.21" }
repositories { mavenCentral() }

val appMain = "${rootDir}/../../app/src/main/java"
val appTest = "${rootDir}/../../app/src/test/java"

// The pure set, exactly as claimed in the README.
val pureMain = listOf(
    "dev/molasses/core",
    "dev/molasses/engine",
    "dev/molasses/sensing/CadenceAnalyzer.kt",
    "dev/molasses/sensing/StepGate.kt",
    "dev/molasses/sensing/FallbackImuGate.kt",
    "dev/molasses/sensing/GravitySplitter.kt",
    "dev/molasses/sensing/Thresholds.kt",
)
val pureTest = listOf(
    "dev/molasses/core",
    "dev/molasses/engine",
    "dev/molasses/sensing",
    "dev/molasses/legacy",
)

sourceSets {
    main {
        kotlin.setSrcDirs(listOf(appMain))
        kotlin.include(pureMain.map { if (it.endsWith(".kt")) it else "$it/**" })
    }
    test {
        kotlin.setSrcDirs(listOf(appTest))
        kotlin.include(pureTest.map { "$it/**" })
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
