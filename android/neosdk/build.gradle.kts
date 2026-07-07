import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

// The ONLY module that imports kr.neolab.sdk. It adapts the (patched) NeoLAB SDK to our clean
// `NeoPenSdk` boundary in :pencore. Built only when the SDK is present (settings.gradle.kts gates
// it on neosdk/libs containing a jar/aar), so the GPL SDK never has to live in this repo for CI.
// See README.md for how to drop in your patched SDK, and android/STRANGLER.md for the plan to
// progressively replace the SDK's internals behind this same boundary.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nibhaus.neosdk"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Supply-chain guard (Section C3): verify any local SDK artifact in libs/ matches the pinned
// SHA-256 allowlist (aar-checksums.txt), so a tampered/swapped AAR can't slip into the build.
// No-op in CI — the GPL SDK isn't committed, so libs/ is empty there.
val verifyAarChecksum by tasks.registering {
    val libs = fileTree("libs") { include("*.aar", "*.jar") }
    val allowFile = file("aar-checksums.txt")
    inputs.files(libs)
    inputs.file(allowFile)
    doLast {
        val allow = allowFile.readLines()
            .mapNotNull { it.substringBefore('#').trim().split(Regex("\\s+")).firstOrNull()?.lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        libs.files.forEach { f ->
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) }
            if (sha !in allow) {
                throw GradleException(
                    "neosdk supply-chain check: ${f.name} sha256=$sha is not in neosdk/aar-checksums.txt. " +
                        "If you intentionally rebuilt the SDK, add this hash there.",
                )
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyAarChecksum) }

dependencies {
    implementation(project(":pencore"))

    // The patched NeoLAB SDK. Pick ONE:
    // (a) drop a built jar/aar into neosdk/libs/ (auto-detected):
    implementation(fileTree("libs") { include("*.jar", "*.aar") })
    // (b) OR wire the SDK source module — add to settings.gradle.kts:
    //       include(":NASDK"); project(":NASDK").projectDir = file("/path/Android-SDK2.0/NASDK2.0_Studio/app")
    //     then:  implementation(project(":NASDK"))
}
