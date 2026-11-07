// Top-level build file. Plugin versions are declared here with `apply false`
// and applied in the module build scripts. Versions verified mutually-compatible
// against source (2026-11): Kotlin 2.4.21 (>= 2.4.20 closes CVE-2026-53914 in kotlin-gradle-plugin;
// KGP 2.4.2x supports Gradle 7.6.3–9.7.0 / AGP 8.5.2–9.3.1). KSP 2.3.x is no longer paired to a
// Kotlin version (2.3.10+ handles 2.4); 2.3.12 over 2.3.13 because of google/ksp#3286 (P1, open).
// AGP 9.2.0 (needs Gradle 9.4.1, JDK 17, compileSdk 37) — upgraded to allow Material 3 Expressive.
plugins {
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    // Static analysis — 1.23.8 is the newest stable release and
    // the first line to declare Gradle configuration-cache support; detekt 2.x is still alpha.
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

// Every Kotlin/Android module gets `detekt` wired to the default ruleset + a per-module baseline
// (config/detekt/baseline-<module>.xml). The baseline freezes existing debt while new findings fail
// both the local task and CI. Skips :premium: it vendors llama.cpp
// (native C/C++, see android/premium/src/main/cpp/) and mirrors the .cbmignore precedent of
// keeping vendored/native code out of source-analysis tooling.
subprojects {
    if (name == "premium") return@subprojects
    plugins.withId("org.jetbrains.kotlin.android") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            baseline = file("$rootDir/config/detekt/baseline-$name.xml")
        }
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            reports {
                xml.required.set(true)
                html.required.set(true)
                sarif.required.set(true)
                txt.required.set(false)
                md.required.set(false)
            }
        }
    }
}
