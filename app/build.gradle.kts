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

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
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
    implementation(libs.androidx.work.runtime.ktx)

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
