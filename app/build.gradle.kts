import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.protobuf)
}

/**
 * versionCode from versionName, as MAJOR * 10000 + MINOR * 100 + PATCH.
 *
 * Derived so the two have one source (appVersion in libs.versions.toml) and
 * raising the name raises the code. Android refuses an update whose code is
 * not higher than the installed one, and a code typed by hand is the one that
 * gets forgotten. The first build under this scheme is 0.2.0, code 200; the
 * code shipped before it was 1.
 */
fun versionCodeOf(version: String): Int {
    val parts = version.split('.').map { it.toInt() }
    require(parts.size == 3) { "appVersion must be MAJOR.MINOR.PATCH, was $version" }
    val (major, minor, patch) = parts
    require(minor in 0..99 && patch in 0..99) { "MINOR and PATCH must be 0-99, was $version" }
    return major * 10_000 + minor * 100 + patch
}

val appVersion: String = libs.versions.appVersion.get()

/**
 * Release signing, read from ~/.gradle/gradle.properties or the environment,
 * never from the repository. No key is created or committed here.
 *
 * Each value is a Gradle property of this name, or an environment variable
 * of the same name. A release build with any of them missing fails at
 * packaging and names what is missing. It never falls back to an unsigned
 * APK or to the debug key: a release signed with anything but the one key
 * cannot update an installed release, and testers would lose their data.
 */
val releaseSigningProperties = listOf(
    "JITTER_STORE_FILE",
    "JITTER_STORE_PASSWORD",
    "JITTER_KEY_ALIAS",
    "JITTER_KEY_PASSWORD",
)

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull
        ?.takeIf { it.isNotBlank() }

val releaseSigning: Map<String, String?> = releaseSigningProperties.associateWith(::releaseSigningValue)
val missingReleaseSigning: List<String> = releaseSigning.filterValues { it == null }.keys.toList()

android {
    namespace = "dev.molasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.molasses"
        minSdk = 30
        targetSdk = 35
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only when all four are present. With any missing there is no
        // release signing config at all, and packageRelease is stopped below
        // before it can write an unsigned APK.
        if (missingReleaseSigning.isEmpty()) {
            create("release") {
                storeFile = file(releaseSigning.getValue("JITTER_STORE_FILE")!!)
                storePassword = releaseSigning.getValue("JITTER_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("JITTER_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("JITTER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Never the debug key, and never null by design: see
            // releaseSigningProperties and the packaging check below.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// A release APK or bundle is never packaged without the release key. The
// check sits on packaging rather than configuration so a debug build, and
// compiling or testing the release variant, still work without the key.
tasks.matching { it.name.matches(Regex("(package|sign)Release(Bundle)?")) }.configureEach {
    doFirst {
        if (missingReleaseSigning.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing: ${missingReleaseSigning.joinToString(", ")}. " +
                    "Set each in ~/.gradle/gradle.properties or as an environment variable of the same name. " +
                    "The release build will not fall back to an unsigned APK or to the debug key.",
            )
        }
    }
}

// Room writes each schema version to app/schemas as JSON at compile time.
// MolassesDatabase already set exportSchema = true, but with no location Room
// only warned and wrote nothing, so there was no record of version 1 to write
// a migration against. The files are meant to be committed: they are the
// history a MigrationTestHelper test replays, and a version bump without its
// JSON has nothing to compare to.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Kotlin 2.0 moved the Compose compiler into its own plugin
// (org.jetbrains.kotlin.plugin.compose, applied above). Deliberately no
// composeOptions { kotlinCompilerExtensionVersion = ... } block -- setting it
// is an error on Kotlin 2.x.

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            // Java lite only. Adding the "kotlin" builtin would also
            // require com.google.protobuf:protobuf-kotlin-lite; the generated
            // Java builders are all this uses.
            task.builtins {
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.uiautomator)
}
