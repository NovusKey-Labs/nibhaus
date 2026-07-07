package com.nibhaus.ui

/**
 * Official LAMY "Digital Writing" links surfaced from the No-pen empty state ("Don't have a pen?").
 *
 * Owner's routing decision (2026-06-28):
 *  - Point at the LAMY **Neo** digital pen specifically — NOT the standard LAMY writers, so buyers
 *    don't confuse a normal fountain/ballpoint with the digital pen this app needs.
 *  - The platform "how to use" link follows the running OS: Android now; Apple once the iOS port ships.
 *
 * Wired into the no-pen empty state (Feature 24, PensHome's PenStatusCard).
 *
 * Both URLs point at LAMY's general digital-writing landing page (verified 200): the earlier
 * per-platform `/android/` and `/apple/` paths 404 on lamy.com.
 */
object PenLinks {
    /** "Digital Writing" — setup/use guide for the current Android build. */
    const val LAMY_ANDROID = "https://www.lamy.com/en/digital-writing/"

    /** "Digital Writing" — for the future iOS port; route here when running on iOS. */
    const val LAMY_APPLE = "https://www.lamy.com/en/digital-writing/"
}
