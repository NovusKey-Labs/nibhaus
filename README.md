> **Neo / LAMY smartpen capture app** (Android) — a local-first replacement for Neo Studio 2:
> capture, store, export, and sync to targets you select (local SAF folder, NAS over Tailscale, or
> a cloud provider you pick), with transcription handed to an external OCR you run. One hard rule:
> no tracking/telemetry/ads.

## Highlights

- **Local-first.** On-device storage is the source of truth; sync is opt-in, per-target.
- **No tracking, telemetry, or ads.** Outbound network only to sync/OCR targets you explicitly select.
- **Real hardware.** Neo Smartpen M1+ (NWP-F51) and LAMY safari all-black ncode (NWP-F80) — genuine NeoLAB Ncode dot-pattern pens.
- **Ncode dot decode** via the NeoLAB Android SDK 2.0, quarantined in a gated `:neosdk` module — `:app` never imports it.
- **Storage & search.** Room with an FTS4 full-text index over transcripts (multi-word, porter-stemmed).
- **Gesture input.** Point-cloud ($P/$Q) unistroke recognizer.
- **External OCR.** Transcription is handed to a vision model you host — never in-app.
- **Pluggable sync.** SAF folder, NAS over Tailscale, or a cloud provider you choose (Drive, Dropbox, OneDrive, WebDAV, S3).
- **Jetpack Compose** UI, Material 3 Expressive.

**Status:** Phase 0 (de-risk gate). The dual-pen GO/NO-GO spike runs on real hardware before Phase 1.

See [`android/DESIGN.md`](android/DESIGN.md) for the full architecture and phased plan, and [`android/`](android/) for the app.

## License

Apache-2.0. The shipped app is GPL-free. It uses the clean-room `:penble` pen driver (`PenBleSdk`),
which imports no NeoLAB code. See [LICENSE](LICENSE) for the full text and [LICENSING.md](LICENSING.md)
for the per-module license map.

The GPL `:neosdk` adapter is optional and dev-only. It is the only module that links NeoLAB's GPLv3
SDK, it is built only when a developer supplies that SDK, and it is excluded from every release by the
`verifyGplFree` Gradle gate, which fails any release build that is not the clean-room `:penble` driver.
Its own GPL terms are recorded in
[`android/neosdk/THIRD_PARTY_NOTICES.md`](android/neosdk/THIRD_PARTY_NOTICES.md) (the SDK binary is
user-provided, not bundled).
