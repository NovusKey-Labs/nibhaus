# Bundled fonts

These typefaces are bundled in `src/main/res/font/` and shipped inside the APK
(no runtime download — that would violate the project's no-outbound-network
rule). All three are licensed under the **SIL Open Font License 1.1**; the full
license text is in `OFL.txt` next to this file.

| Family | Files | Copyright |
|---|---|---|
| **Sora** | `sora_variable.ttf` (variable: wght) | Copyright 2019 The Sora Project Authors (https://github.com/be5invis/Sora) |
| **Inter** | `inter_variable.ttf` (variable: opsz, wght) | Copyright 2016 The Inter Project Authors (https://github.com/rsms/inter) |
| **IBM Plex Mono** | `ibm_plex_mono_regular.ttf`, `ibm_plex_mono_medium.ttf` (static) | Copyright 2017 IBM Corp. |

(v1 used Newsreader + IBM Plex Sans; the "Vault & Ink" v2 direction replaced them with Sora + Inter.)

Sourced from the `google/fonts` repository. Weight mapping is in
`ui/theme/Type.kt` — the variable fonts are pinned per Material weight via
`FontVariation` (effective on API 26+; the target devices are Android 15).
