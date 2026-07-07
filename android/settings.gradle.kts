pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Nibhaus"
include(":app")
include(":premiumapi")   // public open-core contract layer (interfaces only)

// :premium = the private monetized-features module. Present ⇒ full build; absent ⇒ freemium-only.
// Mirrors the :neosdk gating: the open clone / CI builds :app without it. Additionally gated by
// -Pnibhaus.premium=false so the FREEMIUM variant is a first-class, CI-testable build (not a
// delete-the-module ritual): ./gradlew :app:assembleDebug -Pnibhaus.premium=false
val premiumEnabled = (providers.gradleProperty("nibhaus.premium").orNull ?: "true").toBoolean()
if (premiumEnabled && file("premium/build.gradle.kts").exists()) {
    include(":premium")
}
include(":pencore")   // pen-abstraction boundary; the app depends on this, never on the SDK directly
include(":penble")    // clean-room, GPL-free pen driver (replaces :neosdk) — imports no kr.neolab.sdk

// :neosdk = the quarantined NeoLAB SDK driver (the only code that imports kr.neolab.sdk). It is
// built ONLY when you provide the (patched) SDK — drop the SDK jar/aar into neosdk/libs/, or wire
// the SDK source module per neosdk/README.md. Until then CI builds :app + :pencore with the fake.
val neosdkLibs = file("neosdk/libs")
if (neosdkLibs.isDirectory &&
    neosdkLibs.listFiles()?.any { it.extension == "jar" || it.extension == "aar" } == true
) {
    include(":neosdk")
}
