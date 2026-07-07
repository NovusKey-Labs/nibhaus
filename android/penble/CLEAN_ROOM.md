# Clean-Room Record — `:penble` pen driver

**Purpose of this document:** to record that the `:penble` module is an independent, clean-room
reimplementation of the NeoLAB Ncode pen's Bluetooth Low Energy (BLE) wire protocol, created for
**interoperability**, and that it contains **no NeoLAB source code**. It is the evidentiary basis for
Nibhaus's freedom-to-operate on the pen link. (Not legal advice; retained for counsel + due diligence.)

## What `:penble` is

`:penble` (`PenBleSdk`, `NeoGattTransport`, `NeoProto`, `NeoFraming`, `NeoDotDecoder`) is a plain
Android `BluetoothGatt` client that speaks the pen's protocol-2.x wire format. It is a drop-in
replacement for the GPL `:neosdk` adapter, selected at build time via `-PpenDriver=penble`.

## How it was built (the clean-room process)

1. **Derived from observation, not from source.** The command opcodes, frame structure
   (`C0 <cmd> <len:2LE> <payload> C1`, byte-stuffed), and dot-event decoding were reconstructed by
   **observing the pen's actual BLE traffic** — Android HCI Bluetooth snoop logs captured from a paired
   pen during normal use — and by reading **publicly documented** Ncode/BLE-GATT behavior. No NeoLAB
   SDK source, headers, or decompiled binaries were copied, transcribed, or referenced while writing
   `:penble`.
2. **Purpose is interoperability.** The sole function is to let Nibhaus communicate with a pen the
   user already owns. Reverse-engineering strictly for interoperability is protected in the U.S.
   (*Sega v. Accolade*, *Sony v. Connectix* — intermediate copying / RE for interoperable, independently
   written code is fair use) and in the EU (Software Directive 2009/24/EC Art. 6, the decompilation-for-
   interoperability right, which cannot be contractually waived).
3. **No NeoLAB code present.** Verified by search: the `:penble` source imports **zero** symbols from
   `kr.neolab.*` / `com.neolab.*` / the `NASDK` SDK. The only NeoLAB references in `:penble` are prose
   comments describing that the code was *reimplemented from the observed protocol* — descriptive,
   nominative references, not copied code.
4. **The GPL SDK is quarantined.** The one module that links NeoLAB's GPLv3 SDK (`:neosdk`, the only
   code importing `kr.neolab`) is a **development-only** adapter. It is built only when a developer
   manually supplies the SDK (`neosdk/libs/`), is **never included in released/distributed builds**
   (enforced in the release build configuration), and carries its own GPLv3 notice
   (`neosdk/THIRD_PARTY_NOTICES.md`). Shipped Nibhaus uses `:penble` and is GPL-free.

## What was deliberately NOT done

- No copying of NeoLAB SDK source, class/method names as-authored, resource files, or documentation.
- No decompilation of NeoLAB binaries to extract protected expression.
- No use of NeoLAB trademarks/logos to imply affiliation or endorsement (see the trademark notice in
  the repository `NOTICE`).
- No circumvention of a copyright-protection measure: the pen password is the *user's own* device
  access control for *their own* pen, not a technological protection measure guarding copyrighted work.

## Standing verification (run before each release)

```
# :penble must contain zero NeoLAB SDK code
grep -rniE 'kr\.neolab|com\.neolab|NASDK' android/penble/src/   # expect: no import/reference hits
# :neosdk must be the ONLY module importing the SDK
for m in app pencore penble premiumapi premium; do grep -rl 'import kr.neolab' android/$m/src/; done  # expect: none
```

Keeping this record current — and keeping the release artifact `:neosdk`-free — is what preserves the
interoperability defense.
