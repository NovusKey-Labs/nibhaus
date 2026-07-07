> **RESOLVED — GO (2026-06-21, verified on hardware). Historical runbook; see DESIGN.md for current status.**

# Phase 0 — LAMY NWP-F80 GO/NO-GO spike (how-to)

Goal: prove whether the **LAMY safari all-black ncode (NWP-F80)** works with the **NeoLAB Android
SDK 2.0** — the #1 unknown, since the SDK's documented model list is N2/M1/M1+/dimo and does not
include the LAMY. The **M1+ (NWP-F51)** is the control: it's a documented model, so it should
work and proves your test setup is good.

## What counts as GO vs NO-GO

- **GO:** the LAMY (1) pairs over BLE, (2) completes the SDK handshake (the app reports
  connected/authorized), and (3) streams live dots while you write on Ncode paper, with sane
  values — non-zero `(sectionId, ownerId, noteId, pageId)` and moving `x/y`.
- **NO-GO:** it fails at one of those three. **Record exactly which stage** (pairing / handshake /
  dot format) — that determines what we do next.

## Prerequisites

- Pens charged and powered on. Ncode paper/notebook for each (the LAMY ships with Ncode paper;
  the pen only emits dots on genuine Ncode dot-pattern paper).
- **One pen bonds to one device at a time.** Before testing, make sure the pen you're testing is
  not still bonded to another phone/tablet or to the official Neo Studio app — if it is, "forget"
  it there and in Android Bluetooth settings first.
- Dev machine = **the OCR host (fish shell)** with `adb` and the Android SDK; **USB debugging** enabled
  on the Android 14+ phone and the Android tablet; each device authorized for adb.
- Pairing: M1+ → the Android 14+ phone; LAMY → the Android tablet.

---

## Primary instrument: NeoLAB's SDK sample app (uses the real SDK)

The repo `NeoSmartpen/Android-SDK2.0` ships a working sample (`NASDK2.0_sample_code`) that scans,
connects, and renders dots via the real SDK. Running it against each pen answers the
SDK-compatibility question directly, with no code from us.

> **Caveat (this is also Phase 0 risk #2):** the sample is 2017-era Gradle/AGP and may not build
> as-is on a current toolchain. If it won't open/build, that's expected — its
> Gradle/AGP/SDK levels need a small, mechanical bump so it compiles. Don't fight it.

```fish
# OCR host (fish shell)
git clone https://github.com/NeoSmartpen/Android-SDK2.0.git
cd Android-SDK2.0/NASDK2.0_sample_code
# Open in Android Studio (recommended) OR try a CLI build:
./gradlew :app:assembleDebug      # if it fails on old Gradle/AGP, stop and modernize the toolchain
```

### Install and grant BLE permissions

```fish
# OCR host (fish shell)  (repeat per device; -s <serial> picks the device)
adb devices                                   # confirm both show up
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

# Find the sample's package id, then grant runtime BLE perms (Android 12+):
set PKG (adb -s <serial> shell pm list packages | grep -i neolab | sed 's/package://')
adb -s <serial> shell pm grant $PKG android.permission.BLUETOOTH_SCAN
adb -s <serial> shell pm grant $PKG android.permission.BLUETOOTH_CONNECT
# Older Androids (<12) use location instead:
adb -s <serial> shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
```

### Connect and observe

1. Open the sample app, start a **scan**, and select your pen when it appears.
2. Wait for it to report **connected/authorized**.
3. **Write on the Ncode paper.** Confirm strokes render live (or dots log).
4. Do the M1+ on the Android 14+ phone first (control), then the **LAMY on the Android tablet** (the real test).

### Capture evidence (this doubles as a replay fixture)

```fish
# OCR host (fish shell)  — capture a full session log while you write a few lines
adb -s <serial> logcat -v time > lamy_session.txt
# write on paper for ~20s, then Ctrl-C. Repeat for the M1+ as m1plus_session.txt
```

Keep `lamy_session.txt` (and `m1plus_session.txt`). Beyond the GO/NO-GO, the logged dots let you
build a `.jsonl` replay fixture so the whole capture pipeline gets automated tests against
real pen data (see `android/README.md` → replay harness).

---

## If NO-GO

Record the failure stage precisely and grab the log around it:

- **Pairing** — pen never appears in scan, or BLE connect fails. Note the Android Bluetooth state
  and any `GATT`/`133` errors in logcat.
- **Handshake** — connects at the BLE level but the SDK never reports authorized/connected (e.g.,
  password/protocol-version mismatch). Capture the `PenMsg`/error lines.
- **Dot format** — connects and "streams," but dots are empty/garbage (zero ids, no x/y movement).
  Capture a few dot lines.

That stage tells us whether it's a permissions/pairing issue (fixable in our app), a protocol/
handshake gap (may need a protocol-version tweak), or a genuine incompatibility.

---

## Secondary: our own wrapper spike (after the SDK is added to our app)

Once the SDK module is in our project, our `PenConnectSpikeActivity` confirms **our** wrapper
(`NeoSdkAdapter`) decodes the same pen correctly:

```fish
# OCR host (fish shell)
cd android
./gradlew :app:installDebug
adb shell am start -n com.nibhaus/.spike.PenConnectSpikeActivity
adb logcat -s SPIKE
```

Today this runs the fake path; it needs (a) the `kr.neolab.sdk` module added and (b) the verified
`PenCtrl` block in `PenConnectSpikeActivity.kt` activated, plus a minimal scan + runtime-permission
request. That real wrapper spike is the next Phase 0 step. For the GO/NO-GO decision itself, the
NeoLAB sample above is the authoritative instrument.

## Reporting back

Send: GO or NO-GO **per pen**; if NO-GO, the failure stage; and the `logcat` capture(s). That
unblocks Phase 1 and seeds the replay fixtures.
