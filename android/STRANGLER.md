# Strangling the NeoLAB SDK over time

Goal: keep the SDK's *working* value (Ncode dot decoding + the BLE pen protocol) while
progressively replacing its *broken/legacy* parts, until the app could run with little or none of
the original SDK — without ever rewriting the app.

## The seam
Everything routes through one boundary: **`com.nibhaus.pen.NeoPenSdk`** in **`:pencore`**.

```
:app ──depends on──> :pencore  (NeoPenSdk interface, PenDot, PenConnState, FakeNeoPenSdk)
                        ▲
        binds one of   │
   ┌────────────────────┼─────────────────────────┐
:neosdk (NeoSdkAdapter, the only kr.neolab importer)   FakeNeoPenSdk (tests/dev)
```

The app never imports `kr.neolab.sdk`. CI builds `:app` + `:pencore` against `FakeNeoPenSdk`; the
real SDK lives in the gated `:neosdk` module. To swap *any* implementation, you change one line in
`di/ServiceLocator.kt` — nothing else moves.

## How to gut it, one piece at a time (strangler fig)
Each step adds a small in-house implementation behind the **same** `NeoPenSdk` boundary, guarded by
the fake + unit tests, then flips the binding. Nothing downstream changes.

1. **BLE transport** — replace the SDK's `BTLEAdt`/GATT layer with our own
   `BluetoothGatt`-based connector (we already saw the exact service/characteristic UUIDs in the
   GO/NO-GO log: notify char `64cd86b1-…`). Keep using the SDK only for packet parsing.
2. **Packet framing** — reimplement the `c0 … c1` DLE framing + checksum we observed
   (`ProtocolParser20`) in Kotlin; feed decoded payloads to the SDK's command handlers, or…
3. **Command/handshake** — reimplement `reqPenInfo` / pen-status / password / offline-list requests
   from the captured packet shapes. At this point the SDK is only doing dot→coordinate math.
4. **Dot/Ncode decode** — the last and hardest piece (the real reason to keep the SDK). Replace
   only if/when it's worth it; until then it stays quarantined in `:neosdk`.

Each replacement is its own `NeoPenSdk`-implementing class (or an internal collaborator of the
adapter), swapped in via `ServiceLocator`, with `FakeNeoPenSdk`-style tests proving parity before
the flip. Captured real-pen data (via `tools/logcat_to_replay.py`) is the regression fixture.

## Why not gut it all now
Steps 1–3 are tractable from the captured packets; step 4 (Ncode decode) is months of work the
brief says to wrap, not rewrite. So we strangle opportunistically: every time the SDK's legacy
behavior bites us, we replace *that* piece behind the seam — and the blast radius is one file.

## Status: `:penble` is the default driver; `:neosdk` kept as opt-in

All four strangler steps are done. The clean-room driver lives in **`:penble`** (`PenBleSdk`), imports nothing from `kr.neolab.sdk`, and implements the entire `NeoPenSdk` contract: BLE transport (`NeoGattTransport`), framing (`NeoFraming`), commands/handshake/auth (`NeoProto`), live-dot decode (`NeoDotDecoder`), and offline-note download (`NeoOfflineDecoder`). Every layer was validated byte-for-byte against the real pen, including a full **377 + 298 = 675-stroke offline recovery that is byte-identical to the GPL adapter**.

**The default is now `:penble` (Wave 3, commit 8ec3306, approved).** The `PEN_DRIVER` build flag default in `app/build.gradle.kts` was flipped from `neosdk` to `penble`, so an unflagged debug (dogfood) build resolves to the clean-room driver in `ServiceLocator.loadRealPenSdk()`. Release builds are independently pinned to `penble` by the gate below. Selection:

```
./gradlew :app:installDebug                      # default -> :penble (clean-room, GPL-free)
./gradlew :app:installDebug -PpenDriver=neosdk   # -> :neosdk (GPL adapter), opt-in A/B; needs the AAR in neosdk/libs
```

`:neosdk` is not deleted. It stays wired as an opt-in A/B path for regression checks against the original SDK, selectable with `-PpenDriver=neosdk`. The module is included and linked only when the patched GPL AAR is present in `neosdk/libs` (see `settings.gradle.kts` and `app/build.gradle.kts`). That AAR is a local, uncommitted artifact built by `neosdk/patch-and-build-sdk.fish`, so CI and clean clones never carry it. When the AAR is absent, `:neosdk` is neither built nor on any classpath, and the default `penble` build is the only pen driver present.

**Release gate (already GPL-free).** `verifyGplFree` runs on every `assembleRelease` and `bundleRelease` and fails the build on two independent conditions: (1) any `:neosdk` or `neolab` artifact on the release runtime classpath, and (2) `penDriver` set to anything other than `penble`. It passes an unflagged release (default `penble`, no GPL AAR present) and fails a forced `-PpenDriver=neosdk` release. Because `:neosdk` is linked only when the GPL AAR is present, condition (1) also means a release that would physically bundle the GPL AAR cannot be built. A GPL-contaminated release is structurally blocked, not just discouraged. See `LICENSING.md` and `android/penble/CLEAN_ROOM.md`.

**Dogfood signal (still on).** The owner runs the default `penble` build as the daily driver to accumulate real-world miles, with the GPL adapter one `-PpenDriver=neosdk` rebuild away for A/B comparison. Per-sync diagnostic log lines are left in on purpose:

- `PenBle`: connect/auth/offline flow (`offline note list`, `offline chunk pos=...`, `offline note done: N stroke(s)`).
- `NeoOffline`: `inflate failed: ...` should be **zero**. Any occurrence means a chunk or blob layout regression.

Watch during dogfood: first-connect reliability on a power-cycled pen (random LE address), live ink landing on the right page, and offline recovery returning the **full** declared stroke count (the `0xA3` note-start total must equal the flushed `offline note done` count; a shortfall means chunk truncation is back). If any of these bite, switch to `-PpenDriver=neosdk`, reinstall, and capture logs.

**Remaining strangler step (FTO track).** One step is left, and it is future work sequenced with the legal/FTO review: delete `:neosdk` and its build wiring, remove the uncommitted GPL AAR path, and drop the reflective neosdk branch from `ServiceLocator.loadRealPenSdk()`. The release artifact is already GPL-free today because the gate guarantees it. This final step makes the **source tree** GPL-free as well and retires the A/B fallback once `penble` has enough real-world miles. The gating item is the owner's separate legal/FTO track (patent and NeoLAB SDK-terms review of the clean-room reimplementation), not the code change itself.
