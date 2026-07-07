# Licensing

Nibhaus is **open-core**, published by **NovusKey Labs LLC**. Different modules carry different
licenses. This file is the map; the shipped app is **Apache-2.0 + proprietary premium, and GPL-free.**

| Module | License | Ships in the released app? |
|---|---|---|
| `:app` | **Apache-2.0** | ✅ yes |
| `:pencore` | **Apache-2.0** | ✅ yes |
| `:penble` (clean-room pen driver) | **Apache-2.0** | ✅ yes — this is the shipped driver |
| `:premiumapi` (open contract layer) | **Apache-2.0** | ✅ yes |
| `:premium` (monetized features) | **Proprietary — © NovusKey Labs LLC, all rights reserved** | ✅ yes (paid) |
| `:neosdk` (NeoLAB SDK adapter) | **GPLv3** (derivative of NeoLAB's GPLv3 SDK) | ❌ **never** — dev-only |

## Why this split

NeoLAB's Android SDK is licensed **GPLv3** (copyleft). Any build that links it inherits GPLv3, which is
incompatible with distributing the closed-source `:premium` module. So the **shipped** app never links
it: it uses the clean-room, GPL-free `:penble` driver (see `android/penble/CLEAN_ROOM.md`). The
`:neosdk` adapter — the only code that imports `kr.neolab.*` — exists purely as a local development A/B
reference; it is built **only** when a developer manually supplies the SDK (`android/neosdk/libs/`), is
**excluded from every released/distributed artifact** (enforced in the release build configuration), and
retains its own GPLv3 notice (`android/neosdk/THIRD_PARTY_NOTICES.md`).

## What this means for you

- **Use, fork, and build on the Apache-2.0 modules** freely under the Apache License 2.0 (see `LICENSE`),
  including its patent grant. Retain the `NOTICE` file per §4(d).
- The `:premium` module is proprietary and lives in a separate private repository; it is not licensed for
  reuse.
- If you build with `:neosdk` (by supplying NeoLAB's SDK yourself), **that** build is GPLv3 and cannot be
  combined with proprietary code for distribution — use it for local reference only.

Copyright © 2026 NovusKey Labs LLC. "Nibhaus" and "NovusKey Labs" are trademarks of NovusKey Labs LLC.
Third-party marks are the property of their owners — see `NOTICE`.
