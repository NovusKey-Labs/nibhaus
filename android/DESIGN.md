# DESIGN.md — Neo / LAMY Smartpen Capture App (local-first, no forced lock-in)

Authoritative project design doc. Local-first Android app that
replaces NeoLAB's "Neo Studio 2" for **capture, storage, and export**. Data lives **on-device
first as the source of truth**, then syncs to **destinations the user explicitly selects** — own
infrastructure (NAS over Tailscale / a SAF folder watched by Syncthing) **or a cloud provider the
user picks** (Drive, Dropbox, OneDrive, WebDAV, S3). Transcription is handed to an external OCR
the user runs (self-hosted vision model) **by default** — within your own network (NAS over
Tailscale); an explicit, opt-in on-device path also exists (see Phase 4).

> **Hard rule (the only privacy absolute):** no tracking, no telemetry, no ads. Outbound network
> is allowed **only** to the sync/OCR targets the user explicitly selects; no other outbound
> activity. Privacy is a per-target choice the user makes, not a hardcoded "no cloud" limit.
> *(One disclosed carve-out: the optional, opt-in on-device ML Kit features (OCR/translate) fetch a
> one-time model from Google the first time the user invokes them — never automatically, never in the
> background.)*
>
> Cloud sync uses a pluggable provider abstraction and only targets services explicitly selected
> by the user.

> **Status: Phase 0 GO/NO-GO cleared (LAMY NWP-F80 = GO, verified on hardware 2026-06-21).**
> Phases 1–5 are built and unit-tested against fakes; the remaining hardware step is Phase 1's
> real-pen validation — one real capture (to replace the synthetic replay fixture) plus running
> `NeoSdkAdapter` on a real pen. See "GO/NO-GO" and "Open items".

---

## Hardware targets (real, not assumed)

| Pen | Model | Memory | Port | BT | Paired device |
|-----|-------|--------|------|----|---------------|
| A | Neo Smartpen **M1+** | NWP-F51 | ~1,000 A4 pp | USB-C | 4.2 | an Android 14+ phone, OneUI 8.5 |
| B | **LAMY safari all-black ncode** | NWP-F80 | ~160 A4 pp | micro-USB | 4.2/4.3 | an Android tablet, OneUI 8.5 |

Both are genuine NeoLAB Ncode dot-pattern pens (LAMY = rebranded Neo). **One pen registered per
install** (M1+ on the phone, LAMY on the tablet) — not two pens on one device. Must not
choke/duplicate on re-encountering the same pen or notebook.

## SDK

- **NeoLAB Android SDK 2.0**, GPL-3.0: https://github.com/NeoSmartpen/Android-SDK2.0
- Consume the Java library (`kr.neolab.sdk`) as a **module**; wrap it, don't rewrite the
  dot-decode logic. It's 2017-era Java → must modernize Gradle/AGP/Kotlin and the BLE
  permission/foreground-service layer for current Android.
- **Provides** (we reuse): BLE connect, Ncode dot→stroke decode, offline page retrieval from
  pen memory, firmware-update calls.
- **Does NOT provide** (we replace): NeoLAB cloud/account, and the closed HWR engine — which
  is why official transcription is bad. We replace storage with local+NAS and OCR with an
  external pipeline.

### Verified SDK surface (read from source on 2026-06-21, not from memory)

Singleton controller `kr.neolab.sdk.pen.PenCtrl implements IPenCtrl`, via `PenCtrl.getInstance()`.
Verified against
[PenCtrl.java](https://github.com/NeoSmartpen/Android-SDK2.0/blob/master/NASDK2.0_Studio/app/src/main/java/kr/neolab/sdk/pen/PenCtrl.java)
and `IPenCtrl.java`.

**Connection**
```java
void connect(String address) throws BLENotSupportedException;
void connect(String sppAddress, String leAddress);
void connect(String sppAddress, String leAddress, BTLEAdt.UUID_VER uuidVer, short appType, String reqProtocolVer);
void disconnect();
String getConnectedDevice();
String getConnectingDevice();
void unpairDevice(String address);
```

**Listeners** (package `kr.neolab.sdk.pen.penmsg`)
```java
void setListener(IPenMsgListener listener);          // connection/lifecycle PenMsg
void setDotListener(IPenDotListener listener);        // live strokes
void setOffLineDataListener(IOfflineDataListener listener);  // offline retrieval
void setMetadataListener(IMetadataListener listener);

// IPenDotListener:
void onReceiveDot(String macAddress, Dot dot);
// IOfflineDataListener (offline pages pulled from pen memory):
void onReceiveOfflineStrokes(Object extra, String penAddress, Stroke[] strokes,
                             int sectionId, int ownerId, int noteId, Symbol[] symbols);
```

**Offline data — pull stored pages from pen memory**
```java
void setAllowOfflineData(boolean allow);
void reqOfflineDataList();                                   // which (section,owner) have data
void reqOfflineDataList(int sectionId, int ownerId);
void reqOfflineDataPageList(int sectionId, int ownerId, int noteId);
void reqOfflineNoteInfo(int sectionId, int ownerId, int noteId);
void reqOfflineData(int sectionId, int ownerId, int noteId);
void reqOfflineData(int sectionId, int ownerId, int noteId, boolean deleteOnFinished);
void reqOfflineData(int sectionId, int ownerId, int noteId, int[] pageIds);
void reqOfflineData(int sectionId, int ownerId, int noteId, boolean deleteOnFinished, int[] pageIds);
void reqOfflineData(Object extra, int sectionId, int ownerId, int noteId);            // extra = correlation token
void removeOfflineData(int sectionId, int ownerId);
void removeOfflineData(int sectionId, int ownerId, int[] noteIds);
void removeOfflineDataByPage(int sectionId, int ownerId, int noteId, int[] pageIds);
```

**Password / registration**: `inputPassword`, `reqSetupPassword`, `reqSetUpPasswordOff`.
**Using-note filter**: `reqAddUsingNote(...)`, `reqAddUsingNoteAll()`.

**`Dot` fields** (`kr.neolab.sdk.ink.structure.Dot`):
`float x, y;  int pressure;  int dotType;  long timestamp;  int penTipType;  int tiltX, tiltY,
twist;  int sectionId, ownerId, noteId, pageId;  int color;  String macAddress;`
→ Ncode address = `(sectionId, ownerId, noteId, pageId)`. **Note: `noteId` is the notebook,
`pageId` the page.** (An earlier draft guessed `Note`/`Page`/`fx`/`fy` — corrected.)

**STILL TO VERIFY in Phase 0 (do not assume):**
- Exact `dotType` encoding (PEN_DOWN/MOVE/UP integer values) — source uses bare ints (e.g. 17,
  20) and constants live in `Stroke`/protocol classes, not `Dot`. Confirm before mapping phases.
- `IPenMsgListener.onReceivedMessage(...)` `PenMsg` type constants for connect success/failure,
  offline-progress, low-battery, password-required.
- Whether `connect(String)` SPP vs the `(spp, le, ...)` overload is required for M1+/LAMY on
  Android 12+ with the modern BLE stack.

## Architecture & stack

**Modules (anti-corruption layer):** `:app` → `:pencore` (the `NeoPenSdk` boundary + `PenDot`/
`PenConnState`/`FakeNeoPenSdk`). The NeoLAB SDK is quarantined in the gated `:neosdk` module
(`NeoSdkAdapter`, the only `kr.neolab.sdk` importer); `:app` never imports the SDK. CI builds
`:app` + `:pencore` against the fake. Progressive replacement plan: **android/STRANGLER.md**.

- **Kotlin + Jetpack Compose.** Java SDK consumed as the quarantined `:neosdk` module.
- **BLE = a foreground service** (type `connectedDevice`) that owns the connection with
  auto-reconnect (exponential backoff), Android 12+ runtime perms (`BLUETOOTH_SCAN` with
  `neverForLocation`, `BLUETOOTH_CONNECT`). Document the battery-optimization exemption the
  user must grant on OneUI.
- **Storage = Room/SQLite as source of truth.** Rendered exports → a local folder, then synced.
- **Sync = a pluggable `StorageProvider` abstraction** (Phase 3): **one interface, one impl per
  backend**, selected at runtime via the "Sync method" dropdown, choice persisted (DataStore),
  nothing hardcoded. Targets: Local folder (SAF, **default**), Direct push (Tailscale), Local
  only; and cloud — Google Drive, Dropbox, OneDrive, WebDAV (Nextcloud/ownCloud), S3
  (MinIO/B2/AWS/Wasabi). *(Cloud per-provider auth/scope/secret-storage spec is **not yet
  provided** — see "Phase 3–4 spec" gap note; don't invent it.)*
- **OCR hand-off = "OCR trigger" dropdown** (Phase 4): NAS watcher (**default**, app does no OCR,
  just exports + tags metadata for an external watcher within your own network), In-app (background
  worker POSTs the page image to a configurable endpoint/backend/model, stores verbatim transcript),
  or Off. Plus an explicit **opt-in On-device** path (`ocr/OnDeviceInk`, ML Kit Digital Ink — off by
  default; tapping "Transcribe on device" downloads a one-time ML Kit model from Google, disclosed).

## Configuration keys

Full, current shape + defaults: `app/src/main/assets/config.example.json`. At runtime these are
set in the in-app **Settings** screen and persisted with **Jetpack DataStore** (not
SharedPreferences). Defaults: `sync.method = local_folder`, `ocr.trigger = nas_watcher`. Never
hardcode or invent a value; unknowns get a documented default in that file.

## Phased plan & TODO

- [x] **Phase 0 — Foundation & de-risk.** Study SDK ✅ (surface above). Modern build ✅.
  **Connection spike** → `:app` `PenConnectSpikeActivity` (fake-driven). Phase 1's real-hardware
  validation is the next gate.
- [~] **Phase 1 — MVP capture.** Live strokes → Room → render: in place from the foundation.
  **Foreground BLE service done** (`pen/PenForegroundService`, type `connectedDevice`): owns the
  connection, mirrors `PenConnState` in a persistent notification, delegates auto-reconnect to
  `PenConnectionManager`; `MainActivity` requests `BLUETOOTH_SCAN/CONNECT` (+ `POST_NOTIFICATIONS`)
  then starts it. **Battery-opt exemption** helper: `pen/BatteryOptimization` (the user must mark
  the app "Don't optimize" on OneUI — onboarding step). *Remaining for Phase 1: validate on real
  hardware once the GO/NO-GO clears + wire the real `NeoSdkAdapter`.*
- [~] **Phase 2 — Offline sync.** **Done (vs fakes):** idempotent/resumable offline-stroke
  ingestion (`ingest/OfflineSync`, content-derived stroke id → no dupes, no page loss; triggered on
  connect by the foreground service) + **finished-notebook locking** (`organize/AutoOrganizer` now
  instances notebooks, fixing the reuse-overlap bug) — both unit-tested. *Remaining: validate the
  real `reqOfflineData` stream + delete-after-verify on hardware.*
- [~] **Phase 3 — Export + Sync-method dropdown.** **Done (vs fakes, CI-green):** the
  `StorageProvider` abstraction (`export/StorageProvider`) with three built impls — **Local folder
  (SAF)** [default], **Direct push (Tailscale)** (HTTP PUT, stdlib), **Local only**; a settings
  **Sync method** `ExposedDropdownMenuBox` persisted via **DataStore** (`export/SettingsStore`) with
  a SAF `OPEN_DOCUMENT_TREE` picker (`ui/SettingsScreen`); an **idempotent** export engine
  (`export/ExportEngine`) that drains the durable outbox to **SVG (vectors) + JSON sidecar** per
  page, skips unchanged pages, re-exports on content/target change, never drops a failed page; run
  as a **WorkManager** job (`export/ExportWorker`, enqueued on each committed stroke, retry+backoff).
  Idempotency ledger = `export_records` table. Unit-tested: `ExportEngineTest`, `ExportArtifactsTest`.
  Per page the engine now writes **SVG (vectors) + InkML (online ink) + PNG + PDF + Markdown +
  JSON sidecar** (the sidecar lists the formats actually written). Raster formats (PNG/PDF) are
  rendered from one bitmap pass and injected by the app so the engine stays framework-free.
  *Remaining: TXT is intentionally NOT emitted (the watcher owns `<pageId>.txt`); **cloud providers
  blocked on the missing auth/scope spec** (see Open items #2); validate real SAF/NAS writes on
  hardware.*
- [~] **Phase 4 — OCR-trigger dropdown.** NAS-watcher path **built end-to-end (vs fakes, CI-green):**
  export tags the sidecar + writes PNG/InkML for the external watcher; `ocr/TranscriptImporter`
  pulls `<pageId>.txt` back; transcripts are stored (Room v9 — an FTS4 porter index `page_fts` over
  transcripts, added by `MIGRATION_8_9`, validated by the instrumented `FtsMigrationTest`) and re-queue the page so its Markdown
  refreshes; **Search** (`ui/SearchScreen`) matches notebook titles + transcripts + recents. The
  watcher and full deploy plan are maintained privately (server-side premium infrastructure).
  *Remaining: the in-app POST-to-endpoint trigger is still optional/unbuilt.*
- [~] **Phase 5 — Editing & search.** **Done (vs fakes):** lasso-select (≥60%-inside precision),
  recolor/resize/delete, **undo of edits** (inverse-op stack), pen-width Fine/Medium/Large, and
  search over notebook titles + transcripts + recent pages.
- [ ] **Phase 6 — Optional.** Planner/calendar mapping; multi-device niceties. **Calendar event
  creation is already built** (`calendar/CalendarGateway` + `ui/CalendarUi`, local `CalendarContract`,
  no network) — ahead of plan; the planner page→date mapping is still pending data.

### Built beyond the documented phases (fold these into the plan)
These ship in the code but the phase list above never specified them:
- **Audio "pencast"** — `audio/RecordingController` (MediaRecorder/MediaPlayer) + `audio/Pencast`
  (shared-wall-clock stroke↔audio sync), `ListenCanvas` playback.
- **Action zones** — `zones/ActionZone(Store)` + `ui/ActionZoneUi`: tap a printed icon (calibrated by
  circling it) to fire Share/Email × PNG/PDF; wired through `StrokeIngestor`.
- **Translate** — `translate/Translator`: ML Kit language-id + a user-configured LLM POST, with an
  ML Kit on-device translate fallback. Note: ML Kit downloads a one-time model from Google on first
  use — disclose this (same opt-in treatment as on-device OCR).
- **On-device OCR** — `ocr/OnDeviceInk` (ML Kit Digital Ink), an explicit **opt-in** "Transcribe on
  device" action (off by default; the default OCR path is the NAS watcher). First use downloads a
  one-time ML Kit model from Google — disclose it (planned: a first-use confirmation dialog).
- **Per-notebook backgrounds** — `background/BackgroundStore` (template image per notebook).
- **Implemented but NOT wired (intentional staging — keep):** the `$P/$Q` `gesture/UnistrokeRecognizer`
  and `gesture/HotZone` margin-gesture package, and `text/AutoTitle` (no `PageEntity.title` field yet).
  Kept for future wiring, not dead weight. *(Distinct from the 2026-Planner measurement path, which is
  already wired — `ui/CaptureLabScreen` + `capture/CaptureLog` + `NotebookType.PLANNER_2026` geometry
  awaiting your traced measurements — a data edit, not code.)*

### Phase 3–4 spec (Settings + dropdowns) — GATED: build only after Phase 0 GO/NO-GO + Phase 1 + Phase 2
From the "Implementation Order: Settings Screen + Sync-Method & OCR-Trigger Dropdowns" doc.

- **Settings screen:** two `Material3 ExposedDropdownMenuBox` dropdowns; selecting an option
  reveals only that option's contextual field(s). All selections + fields persist via **Jetpack
  DataStore (Preferences)**. Changing either takes effect on the **next export — no rebuild/restart**.
- **Sync method** (`StorageProvider`, one impl each): **Local folder (SAF)** [default] — pick via
  `ACTION_OPEN_DOCUMENT_TREE`, persist the tree URI with `takePersistableUriPermission` (do **not**
  assume legacy `WRITE_EXTERNAL_STORAGE` paths); a folder-sync app you run watches it · **Direct
  push (Tailscale)** — validate NAS endpoint URL, handle unreachable gracefully · **Local only** ·
  **cloud** (Drive/Dropbox/OneDrive/WebDAV/S3). The export pipeline reads the current setting at
  export time.
- **OCR trigger:** **NAS watcher** [default] — no OCR calls; just export + tag sidecar metadata
  for the external watcher · **In-app** — endpoint URL + backend (Ollama|vLLM) + model; after
  export a background worker POSTs the page image and stores the verbatim transcript · **Off**.
- **Export layer:** formats PDF/PNG/SVG(vectors)/TXT; deterministic names + **sidecar metadata**
  (pageId, notebook/Ncode id, pen id, capture timestamp); **idempotent** (re-export never
  dupes/corrupts; failed export retries, never silently dropped); run export + sync + in-app OCR
  as **WorkManager** jobs that survive process death and never block the UI/export.
- **Edge cases:** unreachable endpoint → queue+retry (backoff), non-blocking status, local source
  of truth intact; missing contextual config → block only that downstream step, keep capturing;
  switching sync method mid-session must not strand already-exported files.
- **⚠ Spec gap:** cloud providers still need a full specification covering auth,
  least-privilege scopes, secret storage, per-provider setup) is "in the Settings/dropdowns
  implementation order," but the dropdowns doc available only specs the 3 local options + OCR. **Need
  the cloud-provider spec before building cloud targets** — will not invent OAuth scopes/secrets.

### Finished-notebook locking — RESOLVED
A reused notebook *model* repeats the same `(section, owner, note)`, so the old "one notebook per
note id" mapping would **overlap strokes** of a new physical notebook onto the old one (the
official-app bug). Fixed: `NotebookEntity` is now a **physical instance** `(book, instanceSeq)`
with a `locked` flag; pages are unique by `(notebookId, page)`. `AutoOrganizer` files into the
current unlocked instance and starts a new instance once the user finishes one
(`NoteRepository.finishNotebook`). Covered by `AutoOrganizerTest`.

### Notebook types, calibration & type-driven export naming
Each Ncode **book id** identifies a notebook *product*; `NotebookType` (`export/NotebookType`) maps
it to that product's physical layout + how its pages file. Resolution precedence: a **user
assignment** (persisted by book id in DataStore via the new-notebook dialog) wins over the
**built-in defaults** (`NotebookType.forBook`); an unknown product falls back to flat page-UUID
naming so export never blocks. Pure parts are unit-tested (`NotebookPathsTest`).

- **Calibration.** Ncode units → mm is `ExportArtifacts.MM_PER_UNIT = 2.36` (isotropic; ≈4.24 u/cm;
  corrected 2026-07-03 from 2.32 via the ruler-measured printed-ruling pitch, closer to Neo's
  documented 56/600 in ≈ 2.37 mm/unit scale), measured from ruler traces and cross-checked against the
  Professional sheet (13.75×21 cm) — the writable dot area can't exceed the paper, which refuted an
  earlier value. Re-derive any
  notebook with `tools/calibrate_ncode.py` (least-squares fit → mm/unit, lead-in/out intercept, R²,
  per-axis isotropy).
- **Page geometry → full-page SVG.** A measured `PageGeometry` (writable dot-rectangle in units +
  physical sheet mm) makes the **archival** SVG render at TRUE physical size, ink placed where it
  sits on the sheet. **Share/Print stay auto-fit** — the render mode follows the function invoked.
- **Type-driven naming** (`NotebookPaths.exportBaseName`): `pnb/Work/PNB_Work_Pg038` (notebook) or
  `plnr/2026/06_June/PLNR_School_Pg100` (planner, when its `PlannerLayout` page→date table is set).
  Zero-padded pages, sanitised labels, sortable month folders. The base name is **cosmetic** —
  identity/idempotency stay keyed by pageId (ledger + sidecar), so renaming never dupes. The OCR
  round-trip (`ocr/TranscriptImporter`, `tools/ocr_watcher.py`) walks the sub-folders and maps
  `<base>.txt` back to its page via the sibling `<base>.json` sidecar.
- **New-notebook dialog** (`ui/NotebookSetupDialog`): the first time you write in an un-acknowledged
  notebook, pick its type (remembered by book id — once per product) and label this copy (Work/School,
  saved as the notebook title). Reactive trigger via a DataStore "acknowledged" set → asks once.

**Measured:** Professional Notebook (book 438; 13.75×21 cm; geometry set). **Pending measurement:**
2026 Planner — its book id, `PageGeometry`, and `PlannerLayout` are wired but empty; filling them in
from its 8 corner/edge traces (+ printed page→date pages) is a data edit, no code change. Until then
a planner files by label.

### Replay capture helper
`tools/logcat_to_replay.py` converts a `adb logcat -s SPIKE` capture into a `.jsonl` replay fixture
(`app/src/test/resources/replay/`), so a real pen session feeds the automated pipeline tests.

## GO/NO-GO — LAMY NWP-F80 → **GO** (verified on hardware 2026-06-21)

The SDK's docs list N2/M1/M1+/dimo, not the LAMY — but it **works**. Confirmed by running NeoLAB's
own SDK sample on an Android 14+ phone (Android 15 / OneUI):

```
[CommProcessor20] connection is establised.
  version 3.02; deviceName NWP-F80; subName LAMY_safari; receiveProtocolVer 2.20; sensorType 4
penMsg=16  "device_name":"NWP-F80","sub_name":"LAMY_safari"
penMsg=2   PEN_CONNECTION_SUCCESS         ← full SDK handshake
penMsg=17  battery 99, isLock=true, beep:false, force_max 852
```

Both target pens connect via the SDK (M1+ reports `NWP-F55`/`Neosmartpen_M1+`; LAMY reports
`NWP-F80`/`LAMY_safari`). Notes from the capture:
- "No beep / green LED" on the LAMY was a red herring — the pen has `beep:false` set; green LED = connected.
- Both pens are **password-locked** (`isLock=true`); the SDK emits `penMsg=81` (PASSWORD_REQUEST)
  after connect, and **no ink/offline data flows until `inputPassword()` is called**. The sample
  doesn't implement that dialog, which is why no strokes drew. Our app MUST handle PASSWORD_REQUEST.

### Required SDK fixes for modern Android (Phase 0 risk #2 — confirmed & fixed)
The 2017 SDK crashes on Android 14+ until patched (all `SecurityException: RECEIVER_EXPORTED or
RECEIVER_NOT_EXPORTED`):
1. **`kr.neolab.sdk.pen.usb.UsbAdt.setContext()`** registers a USB receiver with no flag → add
   `RECEIVER_NOT_EXPORTED` (guarded by `SDK_INT >= 33`). **This is the one we must carry into our
   app's SDK module.**
2. The SDK library module is `compileSdkVersion 23` — too old to even reference the flag; bump to 34.
3. (Sample-only) `samplecode.MainActivity.onResume` has the same unflagged `registerReceiver`.

Also: Samsung blocks installing apps with low `targetSdk` (must build the SDK consumer at
`targetSdk 34`), and Auto Blocker silently re-enables itself and blocks sideloads.

## Engineering standards (applied)
Command blocks are labeled by device+shell (the OCR host = fish, TrueNAS = zsh, Windows = PowerShell,
Android via `adb` from the OCR host). No placeholders or fabricated URLs/APIs; unknown values go in
the labeled config with documented defaults. Conventional commits. Tests where integrity
matters (sync completeness). Build artifacts to be metadata-stripped (no tooling watermarks).
**Privacy is one hard rule:** no tracking/telemetry/ads, and no outbound network except to the
user-selected sync/OCR targets — not a blanket "no cloud."

## Testing infrastructure (so verification doesn't depend on manual runs)

- **CI** (`.github/workflows/android.yml`): builds the app, runs JVM unit tests, and runs
  emulator instrumented tests on every push. GitHub runners aren't network-restricted, so the
  build resolves AGP/AndroidX there. (A sandbox **may not** be able to build if its network policy blocks
  `dl.google.com` — the Android SDK download *and* Google Maven both 403. To build there instead,
  widen the environment's network policy to allow Google's Android hosts.)
- **Replay harness** (`pen/replay/ReplayPenSource` + `ReplayPipelineTest` + a `.jsonl` fixture):
  feeds recorded pen data through the exact live capture pipeline. *(The only fixture so far is
  synthetic — `sample_session.jsonl` — so this does not yet exercise real-pen data; replacing it with
  a real capture is the open hardware step.)* Capture once per pen
  (Phase 0 spike → `adb logcat -s SPIKE` → convert to `app/src/test/resources/replay/*.jsonl`),
  and every pipeline check afterward is automated — no hardware.
- **What still needs hardware, once:** the physical-pen GO/NO-GO and the initial real capture.
  Emulators have no Bluetooth; device farms have no pen attached — neither can substitute.

## Open items
1. ✅ **DONE — LAMY GO/NO-GO = GO** (M1+ + LAMY both verified on an Android 14+ phone, Android 15). See
   the GO/NO-GO section above. Phase 1 is unblocked.
2. **(Blocks Phase 3 cloud) Cloud-provider spec.** Drive, Dropbox, OneDrive,
   WebDAV/S3 but the auth/scope/secret-storage/per-provider setup isn't in the dropdowns doc
   available. With that, the local providers + the abstraction are built so cloud drops in cleanly.
   (Local-provider default is now **Local folder (SAF)**, not Syncthing-specifically.)
3. Provide config values when ready: NAS endpoint, OCR endpoint/model/backend, and (per chosen
   targets) cloud credentials.

## Motion

`ui/VaultMotion.kt` is the canonical motion vocabulary — reuse these instead of hand-rolled specs:
**`sharedAxisX`** (screen/tab transitions, `Screens.kt`), **`containerTransform`** (library → page
open, a perspective page-turn, `Screens.kt`), **`Modifier.riseIn`** (per-item staggered rise+fade
entrance), **`DialSpinner`** (the one loading indicator — sync/OCR/processing waits), **`VaultSplash`**
(cold-start only, not reused mid-app).

**Reduced-motion gating:** each primitive checks `rememberReducedMotion()` (system "Remove
animations") and snaps to rest with no animation when it's on — new motion must gate the same way.

**Norm for new screens:** entry uses `riseIn` (staggered) + `sharedAxisX`/`containerTransform` for
navigation; waits use `DialSpinner`; pressable cards/tiles get a small press-scale (~0.96, spring)
rather than flat feedback — see `PaletteCard` in `ui/settings/AppearanceTab.kt`.
