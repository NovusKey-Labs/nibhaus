// OWASP Dependency-Check CI gate (Security teammate, STANDARDS.md §6/§9).
//
// Applied as a Gradle *init script* on purpose: it adds zero lines to the checked-in app/library
// build.gradle.kts files. CI (and anyone auditing locally) opts in explicitly with --init-script.
//
// Scans the :app module's resolved dependency graph — run with -Pnibhaus.premium=false to scan
// exactly the FREEMIUM ship artifact (the :premium module and its vendored llama.cpp tree are not
// even present in that build's project graph; see android/settings.gradle.kts's conditional
// include). Drop the flag to scan the full (premium-linked) graph once that variant ships.
//
// Usage (see .github/workflows/dependency-check.yml for the CI wiring):
//   ./gradlew --init-script gradle/dependency-check.init.gradle.kts \
//       :app:dependencyCheckAnalyze -Pnibhaus.premium=false
//
// Gate: fails the build on any resolved CVE with CVSS >= 7.0 (High/Critical). Findings below that
// bar are reported (HTML + JSON under app/build/reports/dependency-check/) but do not block.
//
// NVD_API_KEY: without an API key the NVD API is rate-limited hard enough that the first
// analysis (which must sync the local CVE cache) can take well over an hour and intermittently
// fail with 403s. Get a free key at https://nvd.nist.gov/developers/request-an-api-key and set it
// as the NVD_API_KEY repo secret. The CI workflow also caches the local NVD data directory
// (~/.dependency-check-data, set explicitly below so the cache key is deterministic) so only the
// first run pays the full sync cost.
//
// Known caveat (do not re-litigate on every run): this plugin's CPE-based matching produces
// occasional false positives against AndroidX/Kotlin artifacts that don't map cleanly to a CPE.
// Triage real hits into config/dependency-check-suppressions.xml with a comment citing the CVE and
// why it's a false positive — don't suppress by guesswork.

import org.owasp.dependencycheck.gradle.DependencyCheckPlugin
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension

initscript {
    repositories {
        // org.owasp:dependency-check-gradle is published to the Gradle Plugin Portal, not (as of
        // 12.x) Maven Central — the portal's Maven-style backing repo resolves it for an initscript
        // classpath declaration (the `plugins {}` DSL isn't available inside `initscript {}`).
        maven { url = uri("https://plugins.gradle.org/m2/") }
        mavenCentral()
    }
    dependencies {
        classpath("org.owasp:dependency-check-gradle:12.2.2")
    }
}

allprojects {
    if (name == "app") {
        pluginManager.apply(DependencyCheckPlugin::class.java)
        extensions.configure(DependencyCheckExtension::class.java) {
            failBuildOnCVSS = 7.0f
            format = "ALL"
            data.directory = "${System.getProperty("user.home")}/.dependency-check-data"
            val suppressions = file("$rootDir/config/dependency-check-suppressions.xml")
            if (suppressions.exists()) {
                suppressionFile = suppressions.absolutePath
            }
            nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
        }
    }
}
