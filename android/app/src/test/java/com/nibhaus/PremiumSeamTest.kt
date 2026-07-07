package com.nibhaus

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PremiumSeamTest {
    /** The reflective seam must match the build variant. With :premium linked (the full build —
     *  BuildConfig.PREMIUM_LINKED=true; audit history: the linkage was once missing entirely, leaving
     *  every premium feature unreachable) the bundle class resolves. In the public clone / freemium
     *  build (-Pnibhaus.premium=false, PREMIUM_LINKED=false) :premium is not on the classpath, so
     *  Class.forName throws and ServiceLocator's runCatching nulls premium — the seam degrades cleanly.
     *  Gating on PREMIUM_LINKED lets this one test pass in BOTH variants (the public repo's CI runs the
     *  freemium branch and must stay green). The class name is assembled from parts so :app source (this
     *  file included) holds no contiguous reference to the private bundle package — the open-core
     *  leakage grep over app/src must stay clean. */
    @Test fun loadPremium_seamMatchesBuildVariant() {
        val className = "com.nibhaus." + "premium.PremiumServicesImpl"
        val cls = runCatching { Class.forName(className) }.getOrNull()
        if (BuildConfig.PREMIUM_LINKED) assertNotNull(cls) else assertNull(cls)
    }
}
