# Nibhaus: Local-First Ncode Smartpen Note Vault

*Nibhaus — by **NovusKey Labs**.*

**Nibhaus** is a privacy-first Android app that replaces the vendor's Neo Studio 2 for capture, storage, and export. Data lives on-device first, then syncs to destinations you explicitly select. Beats the vendor app on capture reliability, transcription quality (pluggable OCR pipeline), customizable UI theming, and handwriting replay. **Hard rule: no telemetry, no accounts, no ads. Outbound network only to sync/OCR targets you pick.**

Supports **Neo M1+** and **LAMY safari** Ncode smartpens (both NeoLAB SDK–compatible). Designed for privacy-conscious researchers, journalists, students, and anyone keeping handwritten notes private and searchable.

## Module architecture

| Module | Purpose | Notes |
|--------|---------|-------|
| **`:app`** | Main UI + app logic (Jetpack Compose) | Depends on `:pencore` and `:premiumapi` only |
| **`:pencore`** | Pen-abstraction boundary (`NeoPenSdk` interface) | The single seam: swap implementations here, nothing else moves |
| **`:penble`** | Clean-room, GPL-free BLE driver | Full replacement for NeoLAB SDK (BLE, framing, handshake, dot decode, offline retrieval) |
| **`:neosdk`** | Quarantined NeoLAB SDK adapter (optional) | Only this module imports `kr.neolab.sdk`; conditional on SDK availability || **`:premiumapi`** | Open-core contract layer (interfaces) | Monetized feature contracts (ML Kit OCR, on-device VLM, translation, sync) |
| **`:premium`** | Proprietary implementations (conditional) | Linked only if `-Pnibhaus.premium=true` (default); freemium via `-Pnibhaus.premium=false` |

**CI builds** (public branch): `:app` + `:pencore` + `:penble` + `:premiumapi`, freemium-only (`-Pnibhaus.premium=false`). The GPL-free build is the default.

## Build & Test

**Toolchain:**
- Gradle wrapper **9.4.1** / AGP **9.2.0** / Kotlin **2.2.21** / JDK 17
- compileSdk **37**, minSdk 24, targetSdk 36

**Build commands:**

```bash
# Clean-room (GPL-free), premium features included (default)
./gradlew :app:assembleDebug -PpenDriver=penble

# Freemium variant (no proprietary features)
./gradlew :app:assembleDebug -Pnibhaus.premium=false

# With GPL adapter (requires SDK in neosdk/libs/ or as a source module)
./gradlew :app:assembleDebug -PpenDriver=neosdk
```

**Unit tests (JVM, no device):**

```bash
./gradlew test  # Runs ~35+ unit tests across all modules
```

Per-module tests: `./gradlew :app:testDebugUnitTest`, `./gradlew :penble:test`, etc.

**NEVER run `connectedAndroidTest` on personal devices** — project convention. This command wipes test data; use CI/emulator only.

**Native builds (`:premium` on-device VLM):**
The `:premium` module includes a **git submodule** for `llama.cpp` (JNI bindings). Initialize once:

```bash
git submodule update --init  # Inside android/ directory
```

Then `./gradlew :premium:assembleDebug` compiles the native layer.

## Status

On-device capture, OCR, and search work end to end. The freemium build is the default; premium
features (accurate server OCR, translation, sync) sit behind the open-core seam.

- Real pen capture and the on-device OCR pipeline working end to end
- On-device ML Kit OCR
- On-device VLM (Tier-1 via llama.cpp/JNI) built and staged for validation
- Page render (scroll + anchor ruling) research complete

See **DESIGN.md** for the full roadmap, phases, and open items.

## Where to go next

- **[DESIGN.md](DESIGN.md)** — authoritative project design, SDK surface, phases, open items
- **[STRANGLER.md](STRANGLER.md)** — pen driver seam (how to swap `:neosdk` ↔ `:penble`, strangler fig pattern)
- **[docs/notebook-physical-specs.md](docs/notebook-physical-specs.md)** — notebook physical specs (sheet, ruling, page layout, dot area)
- **[docs/PRE_SHIP_CHECKLIST.md](docs/PRE_SHIP_CHECKLIST.md)** — pre-release security + compliance baseline

## Contributing

Contributions welcome. Follow the conventions in `docs/PRE_SHIP_CHECKLIST.md` (privacy, security, no AI branding, commit style). Tests + docs required.
