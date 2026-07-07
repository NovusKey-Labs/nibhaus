import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Public, impl-free contract layer for the open-core split. :app and the private :premium
// module both depend on THIS; :app never depends on :premium (resolved reflectively at runtime).
// Interfaces use only native/stdlib/Android types so no :app entity crosses the boundary.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nibhaus.premiumapi"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
