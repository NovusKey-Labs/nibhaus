import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Clean-room, GPL-FREE pen driver. A from-scratch implementation of the :pencore `NeoPenSdk`
// contract over plain Android BLE + our own NeoLAB wire-protocol codec — built to replace the
// quarantined GPL :neosdk (see android/STRANGLER.md). This module imports NOTHING from
// kr.neolab.sdk; everything here is derived from the observed protocol + public specs.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nibhaus.penble"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // android.util.Log is a no-op in JVM unit tests instead of throwing "not mocked".
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":pencore"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
