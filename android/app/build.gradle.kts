import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nibhaus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nibhaus"
        // The Neo SDK supports KitKat, but modern BLE scanning + coroutines/WorkManager
        // are far more reliable from API 24 up. Raise the floor deliberately.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Bring-up only: inject the (locked) pen's password at build time, e.g.
        //   ./gradlew :app:installDebug -PpenPassword=XXXX
        // Never committed, empty in CI. Upgrade path: in-app secure password entry (Phase 1 finish).
        buildConfigField("String", "PEN_PASSWORD", "\"${project.findProperty("penPassword") ?: ""}\"")
        // Bring-up only: the pen's BLE MAC to auto-connect to on launch (until a scan/pair screen
        // exists — Phase 1). e.g. -PpenMac=AA:BB:CC:DD:EE:FF . Empty → no auto-connect.
        buildConfigField("String", "PEN_MAC", "\"${project.findProperty("penMac") ?: ""}\"")
        // Which real pen driver to load reflectively: "penble" (the clean-room GPL-free driver,
        // default, always packaged, see :penble in settings.gradle.kts) or "neosdk" (the GPL
        // adapter, opt-in A/B path, only built when its AAR is present). e.g. -PpenDriver=neosdk
        buildConfigField("String", "PEN_DRIVER", "\"${project.findProperty("penDriver") ?: "penble"}\"")
        // Whether the private :premium bundle is linked into this build (open-core seam). Mirrors the
        // dependencies-block condition below; lets tests assert the reflective seam matches the variant
        // (bundle present in the full build, absent in the public clone / -Pnibhaus.premium=false).
        buildConfigField("Boolean", "PREMIUM_LINKED", (rootProject.findProject(":premium") != null).toString())
    }

    // Release signing: reads android/keystore.properties (gitignored) or, failing that, the
    // developer's ~/keystores/inkvault/keystore.properties. Absent both (CI, fresh clones), the
    // release build stays unsigned rather than failing — signing is a local, key-holder concern.
    val keystoreProps = sequenceOf(
        rootProject.file("keystore.properties"),
        File(System.getProperty("user.home"), "keystores/inkvault/keystore.properties"),
    ).firstOrNull { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // CVE-2021-0341 (CVSS 7.5): okhttp before 4.9.2 has a certificate-hostname-verification bypass.
    // It reaches the freemium build transitively through Firebase ML Kit's on-device model download
    // (a live TLS path in the free tier). Force the patched line until ML Kit ships a fixed okhttp.
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0") {
            because("CVE-2021-0341: ML Kit pulls a vulnerable okhttp 3.12.1 transitively")
        }
    }

    // The pen-abstraction boundary. The app talks to NeoPenSdk; the NeoLAB SDK never appears here.
    implementation(project(":pencore"))
    implementation(project(":premiumapi"))

    // The private premium bundle — linked ONLY when the module is included (open-core: the public
    // clone and -Pnibhaus.premium=false builds compile without it). :app still never imports its
    // package; ServiceLocator resolves PremiumServicesImpl reflectively. AUDIT FIX: this linkage was
    // missing entirely, leaving every premium feature structurally unreachable in all builds.
    if (rootProject.findProject(":premium") != null) {
        implementation(project(":premium"))
    }

    // The clean-room, GPL-FREE pen driver (selected via -PpenDriver=penble). Always safe to link —
    // it imports no kr.neolab.sdk — so it's unconditional, unlike :neosdk below.
    implementation(project(":penble"))

    // Real pen driver — linked ONLY when the patched SDK AAR has been built into neosdk/libs
    // (android/neosdk/patch-and-build-sdk.fish). CI/dev build without it, against the fake. The
    // app still never imports kr.neolab.sdk; ServiceLocator looks the adapter up reflectively.
    if (rootProject.file("neosdk/libs").listFiles()?.any { it.extension == "aar" || it.extension == "jar" } == true) {
        implementation(project(":neosdk"))
    }

    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    // Branded launch: Android-12 SplashScreen backported to minSdk 24; held until the palette loads.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // Material 3 Expressive (spring MotionScheme + new components) is on the 1.5.0-alpha track until
    // it graduates to stable later in 2026; pin it over the BOM. The toolchain (AGP 9.2 / Gradle 9.4
    // / compileSdk 37) was upgraded specifically to support it. Note: drop the explicit version
    // and go back to BOM-managed `material3` once 1.5.0 ships stable.
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Persistence — the source of truth. Room 2.8.x is KSP2-ready.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Durable background sync.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Phase 3 export: persisted settings + SAF folder writes.
    // 1.1.7 is the last 1.1.x with a confirmed 16 KB-aligned libdatastore_shared_counter.so.
    // Note: do NOT jump to 1.2.0 — it regressed the .so back to 4 KB alignment
    // (issuetracker 357653528 / flutter#182898); re-verify with check_elf_alignment.sh before any 1.2.x.
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Print a page via the system print dialog (PrintHelper renders a bitmap).
    implementation("androidx.print:print:1.0.0")

    // On-device handwriting OCR straight from captured strokes (offline after a one-time model
    // download; no GPU/NAS). ML Kit Digital Ink consumes our X/Y/time pen data — see OnDeviceInk.
    // 19.0.0 is the 16 KB-page-aligned digital-ink artifact (libdigitalink.so LOAD align 0x4000 vs
    // 18.1.0's 0x1000 — verified with readelf), resolving the mlkit#938 liability on Android 15+
    // 16 KB-booted devices. NOTE: 19.0.0 did NOT remove the API — it REPACKAGED it from
    // `com.google.mlkit.vision.digitalink.*` to `…vision.digitalink.recognition.*`. The earlier
    // "package is gone, stay on 18.1.0" read was a misdiagnosis of the moved imports; OnDeviceInk
    // imports the `.recognition` package and the fail-soft stays as defense-in-depth. Build + JVM
    // unit tests pass on 19.0.0.
    // Free tier ships instant OCR (gate unification 2026-07-05): OnDeviceInk lives in :app, so the
    // engine is linked in EVERY build, freemium included. APK size cost accepted; ML Kit is not
    // GPL, so verifyGplFree is unaffected. verifyNoTelemetry already allows ML Kit plumbing.
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    // language-id + translate ML Kit deps moved to :premium (Translator) — open-core split.
    // Languages picker data stays in :app, decoupled (static code list); see translate/Languages.kt.

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Opt-in biometric/device-credential app-lock for the vault (Section C1). The AndroidX wrapper
    // normalizes BiometricPrompt across API levels + provides device-credential fallback; it also
    // pulls androidx.fragment (so MainActivity can be a FragmentActivity, which BiometricPrompt needs).
    implementation("androidx.biometric:biometric:1.1.0")

    // Home-screen widget (#13): Jetpack Compose for App Widgets. 1.1.1 is the latest stable release
    // (1.2.0/1.3.0 are still alpha/beta as of this writing) — no version catalog exists in this
    // project, so pinned directly here like every other dependency above.
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // The real Neo SDK jar would be dropped here, e.g.:
    // implementation(files("libs/neosmartpen-sdk-2.1.10.jar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("androidx.room:room-testing:2.8.4")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("com.google.truth:truth:1.4.4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// --- Privacy guardrail: no telemetry, enforced at build time (DESIGN.md hard rule) ---
// ML Kit's plumbing (firebase-components/encoders/annotations, datatransport) is allowed: it's DI +
// the transport layer, and ML Kit's own usage logging is already disabled in the manifest
// (firebase_data_collection_default_enabled=false). What must NEVER enter the runtime classpath is an
// actual analytics / crash-reporting / ads / measurement SDK. CI runs `:app:verifyNoTelemetry`.
val telemetryBlocklist = listOf(
    "firebase-analytics", "firebase-crashlytics", "firebase-perf", "firebase-messaging",
    "firebase-inappmessaging", "play-services-measurement", "play-services-ads",
    "play-services-analytics", ":crashlytics", "appcenter", "io.sentry", "mixpanel",
    "amplitude", "com.segment",
)
tasks.register("verifyNoTelemetry") {
    group = "verification"
    description = "Fail the build if a telemetry/analytics/ads dependency is on the app runtime classpath."
    // Inspects the resolved runtime classpath at execution time, so it can't be configuration-cached;
    // declare that explicitly (Gradle runs it uncached instead of failing the build).
    notCompatibleWithConfigurationCache("resolves the runtime classpath at execution time")
    doLast {
        val offenders = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").flatMap { cfg ->
            configurations.getByName(cfg)
                .incoming.resolutionResult.allComponents
                .map { it.id.displayName }
                .filter { id -> telemetryBlocklist.any { id.contains(it, ignoreCase = true) } }
        }.distinct().sorted()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "no-telemetry rule violated — these dependencies report to a third party:\n  " +
                    offenders.joinToString("\n  "),
            )
        }
        logger.lifecycle("verifyNoTelemetry: clean — no analytics/crash/ads SDK on debug or release runtime classpath.")
    }
}

// Legal-safety gate: the released app must be GPL-free. NeoLAB's SDK is GPLv3; the :neosdk adapter that
// links it must never ship (it would force GPLv3 on the app and collide with the proprietary :premium
// module). This fails any release that links :neosdk / NeoLAB code, or that isn't built with the
// clean-room :penble driver — making a GPL-contaminated release structurally impossible. See LICENSING.md
// and android/penble/CLEAN_ROOM.md.
tasks.register("verifyGplFree") {
    group = "verification"
    description = "Fail a release build that links the GPL :neosdk adapter instead of the clean-room :penble driver."
    notCompatibleWithConfigurationCache("resolves the release classpath at execution time")
    doLast {
        val gpl = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .map { it.id.displayName }
            .filter { it.contains(":neosdk") || it.contains("neolab", ignoreCase = true) }
            .distinct().sorted()
        if (gpl.isNotEmpty()) {
            throw GradleException(
                "GPL-free rule violated — the release links GPL pen-driver code (ship :penble, not :neosdk):\n  " +
                    gpl.joinToString("\n  "),
            )
        }
        val driver = (project.findProperty("penDriver") ?: "penble").toString()
        if (driver != "penble") {
            throw GradleException(
                "GPL-free rule: release builds must use the clean-room driver — rebuild with -PpenDriver=penble (was '$driver').",
            )
        }
        logger.lifecycle("verifyGplFree: clean — release is GPL-free (:penble, no :neosdk / NeoLAB SDK).")
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn("verifyGplFree")
}
