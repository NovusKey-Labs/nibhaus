# On-Device VLM OCR (Tier 1) — Manual Validation Procedure

Run this on a real device **before** enabling Tier-1 (`VlmInk`) for ship. Everything in is
unit-tested + the native `.so` builds in CI, but the things below can only be proven on hardware:
real inference, the ~3.2 GB download, latency UX, and out-of-memory behavior.

**NEVER run `connectedAndroidTest`** — it wipes the tablet. Validate by installing the debug APK
and exercising the app by hand (`adb install`, not instrumented tests).

## Build + install
```bash
cd android
./gradlew :app:assembleDebug                 # builds libvlm_jni.so (arm64-v8a, x86_64)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -iE 'vlm|libvlm|inkvault'  # watch in a second terminal
```

## Checks (each: do → expect → pass/fail)

1. **Premium loads in the real full build** (guards the C1 synthetic-ctor bug). After install, the
   ✨ Improve-transcription menu item appears and Settings shows the Transcription section. If premium
   features are absent, `loadPremium()` nulled out → a C1-class regression (the `ServiceLocatorSeamTest`
   guards the ctor selection, but confirm on a device).
2. **Native lib loads.** Logcat shows `libvlm_jni` loaded, no `UnsatisfiedLinkError`. (On Android 15+
   confirm the 16 KB-page-aligned `.so` loads.)
3. **Real inference quality.** Write/import a page → tap ✨ Improve. Expect a sane verbatim
   transcription (~14% WER on real notes, better on neat writing), **no thinking-runaway / repeat-loop**,
   and it completes. Sanity-compare to the eager Tier-0 transcript.
4. **Latency UX** (known gap I3). Time the Improve op — **~30–120 s/page on CPU**. Does the UI read as
   *working* or *hung*? Currently it shows a static "Transcribing on device…" banner with **no progress
   and no cancel**, and on first run the ~3.2 GB download runs behind that same banner (the % is only in
   Settings). Decide if that's shippable or fix I3 first.
5. **Real model download.** First Improve (or the Settings status row) triggers the ~3.2 GB download
   (model 1.93 GB + mmproj 1.34 GB). Verify: **Wi-Fi-only by default** (on cellular it should NOT
   download); turn on **"Allow model download over mobile data"** → it downloads on cellular; **resumable**
   (kill the app mid-download, reopen → it resumes from the `.part`); **storage precheck** (low free space →
   fails gracefully, no half-file); **integrity** (the real sha256 is now wired — a truncated/corrupt file
   is rejected and re-fetched).
6. **RAM gate + override.** On a device under ~6 GB RAM, VLM is gated off (Improve falls back to Tier-0).
   Turn on **"Enable on-device VLM on this device anyway"** → it runs. **I5 watch:** on a genuinely
   low-RAM device a native ggml allocation can `abort()`/`SIGSEGV` (uncatchable by the JNI try/catch) and
   crash the process — confirm it degrades to null instead. If it can crash, keep the override gated or add
   a RAM-headroom precheck before forcing.
7. **Device-disable (too-slow).** If an inference exceeds ~120 s, `onTooSlow` persists the disable flag;
   later Improve taps skip VLM (Tier-0). Confirm the flag **sticks across an app restart**. (Minor known
   race: a rapid second tap before the async flag write may slip one more slow run through — benign.)
8. **Graceful degradation end-to-end.** With no BYO server + VLM disabled/incapable, Improve still returns
   the Tier-0 result, never an error/crash. Airplane mode mid-Improve → no crash, falls back.

## Known v1 limitations / decisions before ship
- **F — no native context caching:** `VlmInk` re-loads the 2.4 GB model on *every* Improve tap (safe but
  slow; avoids holding 2.4 GB resident). Optimize with a cached handle + lifecycle (free on idle /
  `onTrimMemory`) in v1.1.
- **I3 — Improve UX:** needs a real progress indicator (surface `VlmDownloadState` % at the trigger, plus an
  inference spinner) and a **cancel** button (end-to-end cancellation now propagates, so cancel will work).
- **Transcription-quality dropdown removed** (it was dead — read by nothing). Re-add once wired to the eager
  on-save transcribe path (Instant / Accurate / Auto).
- **`ON_DEVICE_LLM` translation tier** is plumbed + labeled but not invoked in production (forward-looking).
- **Model URLs use HF `resolve/main`** (mutable) — pin a specific revision so the wired sha256 stays valid,
  or self-host the GGUFs. Current sha256: model `d02fe9b6…486c12`, mmproj `b9160fe9…e60d5e`.
- **Accuracy disclaimer copy** is in verbatim — pending owner final sign-off.

## Go / No-Go for Tier 1
**GO** if checks 1–8 pass on a representative mid-range device, real-handwriting quality is acceptable, the
download is reliable, and nothing crashes under the override on low-RAM. **Otherwise:** leave Tier-1 disabled
— the router already degrades to Tier-0 (ML Kit) on-device + BYO server (`ServerInk`) for accuracy — ship
that, and iterate. The architecture supports flipping Tier-1 on later (and dropping in the v2 fine-tuned
weights) without re-plumbing.
