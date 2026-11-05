import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The pen-abstraction boundary (anti-corruption layer). The app and every SDK driver depend on
// THIS module, never on each other. NeoLAB's SDK is quarantined behind the `NeoPenSdk` interface
// defined here, so we can swap or progressively replace its internals (see android/STRANGLER.md)
// without the app noticing.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nibhaus.pencore"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // Saved-pen list persistence (PenPrefs.savedPens) — same JSON-in-SharedPreferences approach the
    // app module already uses (ActionZoneStore etc.), same pinned version.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
