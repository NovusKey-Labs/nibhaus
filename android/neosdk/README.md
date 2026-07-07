# :neosdk — quarantined NeoLAB SDK driver

The **only** module that imports `kr.neolab.sdk`. It adapts the SDK to our `NeoPenSdk` boundary
(`:pencore`) via `NeoSdkAdapter`. Everything else in the app is SDK-agnostic. Built **only when you
provide the SDK** (settings.gradle.kts gates it on `neosdk/libs/` containing a jar/aar), so CI keeps
building `:app` + `:pencore` against the fake without the GPL SDK in the repo.

## Enable it (on the OCR host, where your patched SDK clone lives)

```fish
# patches the SDK for Android 14+, builds it, and drops the AAR into neosdk/libs/
android/neosdk/patch-and-build-sdk.fish ~/Android-SDK2.0
```

Then wire the real driver in `app/.../di/ServiceLocator.kt`:
```kotlin
// val penSdk: NeoPenSdk = FakeNeoPenSdk()
val penSdk: NeoPenSdk = com.nibhaus.neosdk.NeoSdkAdapter(
    appContext,
    passwordFor = { mac -> penPasswordStore.get(mac) },   // both pens are isLock=true
)
```
(`:app` must then `implementation(project(":neosdk"))` — add it next to the `:pencore` line.)

## The patches (verified during the hardware GO/NO-GO)
1. `NASDK2.0_Studio/app/build.gradle`: `compileSdkVersion 23 → 34` (too old to reference the flag).
2. `kr/neolab/sdk/pen/usb/UsbAdt.java`: the USB `registerReceiver` needs `RECEIVER_NOT_EXPORTED`
   (guarded by `SDK_INT >= 33`), or the app crashes on Android 14+.
The script applies both.

## Confirm on first real build (couldn't be verified without the SDK compiling)
- `NeoSdkAdapter.phaseOf()` — the `dotType` int→DOWN/MOVE/UP mapping (kept in sync with
  `tools/logcat_to_replay.py`). Tweak both together if a capture shows different ordering.
- `Stroke.dots` / `Stroke.color` accessor names in `toOfflineStroke()` — adjust to the SDK's
  actual getters if the names differ.
- `PenMsgType` constant names (`PEN_INFORMATION`, `PEN_CONNECTION_FAILURE_BTDUPLICATE`).
These are isolated to this one file — the app never sees them.
