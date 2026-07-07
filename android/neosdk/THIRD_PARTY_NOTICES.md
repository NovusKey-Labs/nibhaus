# Third-party notices — `:neosdk`

The `:neosdk` module wraps the **NeoLAB Smartpen Android SDK 2.0**, licensed under the
**GNU General Public License v3.0 (GPL-3.0)**.

- Upstream / corresponding source: <https://github.com/NeoSmartpen/Android-SDK2.0> (GPL-3.0)
- Upstream license: <https://github.com/NeoSmartpen/Android-SDK2.0/blob/master/LICENSE>

Because Nibhaus links this GPL-3.0 SDK, the combined work is distributed under **GPL-3.0** (see the
repository's root `LICENSE`).

## The vendored binary (`neosdk/libs/*.aar`) is user-provided, not bundled

The build links the SDK only when you drop its `.aar`/`.jar` into `android/neosdk/libs/` — see
`settings.gradle.kts`, where the `:neosdk` module is included only if that directory contains a
binary. CI and a fresh clone build against `FakeNeoPenSdk` instead. The binary is **not** tracked in
this repository (`*.aar`/`*.jar` under the `libs/` dirs are git-ignored); obtain it from the upstream
source above and apply the patches below.

## Patches applied to the upstream SDK

To run on modern Android (API 33+), the 2017-era SDK needs these changes (see `android/DESIGN.md`,
"Required SDK fixes for modern Android"):

1. `kr.neolab.sdk.pen.usb.UsbAdt.setContext()` registers a USB `BroadcastReceiver` with no export
   flag — add `RECEIVER_NOT_EXPORTED`, guarded by `Build.VERSION.SDK_INT >= 33`.
2. The SDK library module is `compileSdkVersion 23` — too old to reference that flag; bump it to a
   modern `compileSdk`.

These are the only modifications. The corresponding source for the patched binary is the upstream
repository above with these two changes applied.
