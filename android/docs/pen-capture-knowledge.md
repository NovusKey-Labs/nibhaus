# Pen Live-Capture — Working Knowledge

The durable record of how the pen link works, how to debug it, and what has been found. Read this
BEFORE touching pen capture or asking anyone to re-explain the protocol. Append to it after every
debug pass — this is the file that makes the knowledge compound instead of getting re-derived.

**STATUS (2026-07-05): live ink capture WORKS on the Neo M1+ with the correct password.** The
multi-hour "no ink" saga was a single bug: penble accepted wrong passwords, so the pen was never
authorized and refused every operational command. Fixed (commit `ac7e21b`).

**Debugging rule learned the hard way:** when a known-good reference exists (Neo Studio 2), CAPTURE
and DIFF against it FIRST. Do not black-box guess protocol bytes. One HCI snoop decoded in minutes
what hours of guessing could not. See memory `feedback-diagnose-against-working-reference`.

Clean-room rule (non-negotiable): everything here is derived from OBSERVED BLE traffic and public
protocol behavior. Never copy NeoLAB SDK source (`kr.neolab.*`) into `:penble`. See `CLEAN_ROOM.md`.

## The capture pipeline (end to end)

1. **Scan + connect** — the LE address rotates; only `sppAddress` is stable, so reconnect RE-SCANS.
2. **PenInfo handshake** — `REQ_PEN_INFO (0x01)` -> `RES_PEN_INFO (0x81)`; carries model, fw, name,
   lock flag (payload[56]). NOTE: LAMY pens use a different handshake code — needs its own capture.
3. **Auth** — `REQ_PASSWORD (0x02, ASCII password in a 16-byte field)` -> `RES_PASSWORD (0x82)`.
   **The frame err byte only means "received." The REAL result is `payload[0]`** (see root cause).
4. **Clock set** — `REQ_PEN_STATUS_CHANGE (0x05)` sub `0x01` + 8-byte LE epoch millis. The pen refuses
   operational commands (err=2) until its clock is set. Frame: `C0 05 09 00 01 <ms:8LE> C1`.
5. **Live-ink registration** — `REQ_USING_NOTE_NOTIFY (0x11)` payload `FF FF` -> `RES 0x91 err=0`.
6. **Dots stream** — unsolicited `EVT_DOT_DATA (0x65)` -> `NeoDotDecoder` -> UI. No `0x65` + screen
   stuck on "WAITING FOR INK" = an earlier step failed (almost always auth; see root cause).

Neo Studio's exact post-auth order (from the capture): offlineDataSave -> SetCurrentTime ->
usingAllNotes -> offlineNoteList -> dots. penble does clock -> using-note; both work.

## Protocol map (`NeoProto.kt` is the source of truth)

Requests (TX): `0x01` PenInfo · `0x02` Password · `0x03` PasswordSet · `0x04` PenStatus ·
`0x05` PenStatusChange (sub `0x01` SetCurrentTime, sub `0x07` OfflineDataSave) · `0x11` UsingNote ·
`0x21` OfflineNoteList · `0x23` OfflineData · `0xA4` OfflineChunkAck.
Responses (RX) = request | 0x80. Events (RX, unsolicited): `0x63/0x65/0x69/0x6A`.
Frame: `C0 <cmd> <len:2LE> <payload> C1`, byte-stuffed. RX bodies add an err byte: `[cmd,err,len2,payload]`.

## ROOT CAUSE (2026-07-05): penble accepted wrong passwords

`RES_PASSWORD (0x82)` carries a **3-byte payload** that is the actual auth result. penble read the
frame `err` byte (always 0 = "received") and ignored the payload, so it reported "authorized" for ANY
password. With a wrong password the pen quietly stays unauthorized and rejects every later command
with err=2 — no clock, no using-note, no dots. It looked like a using-note/format problem; it was auth.

Decoded byte-exact from a Neo Studio capture (unlock of THIS M1+):
- a correct password -> payload `01 00 0a`
- a wrong password -> payload `00 06 0a`

So: **`payload[0]`: 0x01 = correct, 0x00 = wrong.** `payload[1]` = failed attempts used, `payload[2]`
= `0x0a` = 10 (max before a DESTRUCTIVE reset). The wrong attempt's `06` matched the user being "4 from
a reset." Fix (`PenBleSdk.onPasswordResult`): check `payload[0] == 0x01`; on wrong, report
`PasswordRequired` and log attempts remaining so we never silently burn them toward the reset.

Everything else penble sends was already correct — the clock-set format and the 2-byte using-note
(`C0 11 02 00 FF FF C1`) match Neo Studio byte-for-byte. They only failed because auth failed. The
using-note "format negotiation" that briefly existed was chasing a non-problem and has been removed.

## Attempt counter (PenStatus 0x84)

Decoded from a controlled capture (connect at 0 used -> 7 deliberate wrong tries -> correct reset):
`RES_PEN_STATUS (0x84)` payload `[0]` = lock flag (1 locked, 0 unlocked), `[1]` = max attempts
(`0x0a` = 10), `[2]` = attempts USED (byte tracked 4 -> 5 -> 0-after-reset), then RTC at `[3..]`,
battery at `[20]`. So **remaining = 10 - payload[2]**, and a correct password resets used to 0. The
`0x82` password reply carries the same `[used][max]` after each attempt. penble queries status on a
locked connect and shows the count at the first prompt (commit 551b4a5), plus the wrong-password
count after each failure (commit b064e3f) — matching Neo Studio. 10 wrong attempts = destructive reset.

## Change password (0x03 request → 0x83 reply)

Decoded byte-exact from a Neo Studio capture (two changes back to back, each old→new). Request is
`C0 03 21 00 01 <old:16 ASCII> <new:16 ASCII> C1` — matches `NeoRequest.setPassword` exactly. The
**0x83 reply uses the OPPOSITE success marker from the 0x82 unlock**: a successful change replies
`payload[0] == 0x00` (captured `00 0a 00`, and on older fw a 2-byte `00 0a`), where the unlock reply
uses `0x01` for correct. Proof it applied: Neo Studio immediately re-unlocks with the NEW password
and gets `82 … 01 00 0a` (correct). penble was reusing the unlock's `0x01` check for the change reply,
so it reported every successful change as a failure — fixed via the pure `passwordChangeSucceeded`
predicate in `NeoProto` (regression-tested against both markers). NOTE: Neo Studio re-sends the 0x02
unlock with the new password right after the change; penble does not (the pen accepts both old and
new within the same power session, and the new one enforces only after a power-cycle). The old field
must carry the pen's CURRENT password or the change is silently rejected (old≠current → no-op).

## Reading a capture logcat (healthy vs broken)

```
adb -s <serial> logcat -d | grep -iE "PenBle|password (ACCEPTED|REJECTED)|SetCurrentTime|using-note|0x65"
```
- Healthy: `password ACCEPTED (correct)` -> `SetCurrentTime result err=0` -> `using-note accepted —
  live ink streaming enabled` -> dot events when writing.
- Wrong password: `password REJECTED (wrong) — N of 10 attempts used`.

## How to capture the ground truth (the method that worked)

1. Device: Developer options -> Enable Bluetooth HCI snoop log = Enabled; toggle Bluetooth off/on.
2. Release the pen from Nibhaus (`adb shell am force-stop com.nibhaus`) so Neo Studio can connect.
3. In Neo Studio 2: connect the pen, do the thing (write / change password / connect a LAMY), a few times.
4. Pull + parse: `adb bugreport <dir>` -> unzip -> `FS/data/log/bt/btsnoop_hci.log` -> `btmon -r <log>`.
   The pen frames are ATT `Write Request` (Handle `0x0066`) and `Handle Value Notification`; the Data
   is the `C0 .. C1` frame. Grep btmon output for `Data\[` around the ATT ops.

## Known remaining (do these against a capture — do NOT guess)

- **Change Password (`0x03`) — decoded + fixed, pending on-device confirm.** The `0x03` request and
  `0x83` reply are now decoded byte-exact (see "Change password" above); the success-marker bug is
  fixed. Still needs one on-device change on the M1+ to confirm end-to-end before it's called done.
- **LAMY safari — CONFIRMED working on penble (2026-07-05).** Captured a LAMY session (start count
  10, 5 wrong tries, change ×2) and confirmed on-device: connect + password + change all work with no
  code change. The `0x81` handshake identity differs (model `NWP-F80` vs M1+ `NWP-F55`, name
  `LAMY_safari` vs `Neosmartpen_M1+`, payload `0x45`=69 vs `0x41`=65) but every field penble reads is
  at the same offset (name `[40..55]`, lock flag `[56]`, MAC `[58..63]`); the LAMY just carries extra
  trailing bytes, and `onPenInfo`'s `payload.size > 56` guard already handles the longer packet. The
  attempt-counter (`0x84`), unlock (`0x82`), and change (`0x83 → 00 0a 00`) replies are byte-identical
  to the M1+. Neo Studio sent the LAMY a slightly different `0x01` request (zeroed appType/appVer,
  protocol "2.22" vs penble's "2.18"); the LAMY accepted penble's 2.18 request on-device
  (connect/password/change all worked). penble now declares the protocol per-pen — V5/LAMY gets
  "2.22" to match Neo Studio, V2/M1+ keeps "2.18" (`PenBleSdk.onReady`). That specific 2.22 outgoing
  value has NOT been re-validated on device yet (only Neo Studio's 2.22 traffic was captured); one
  LAMY ink/offline pass on the 2.22 build is still needed before the per-pen change is called done.

## Files

`android/penble/src/main/java/com/nibhaus/penble/` — `PenBleSdk` (driver: auth, clock, using-note,
dots, offline), `NeoProto` (opcodes + builders + parsers), `NeoFraming`, `NeoDotDecoder`,
`NeoGattTransport`. Docs: `CLEAN_ROOM.md` (FTO), `../DESIGN.md` (SDK API surface), `../STRANGLER.md`.
UI: `android/app/.../ui/livecapture/LiveCaptureScreen.kt` (the bottom coord/fps readout is fed by the
incoming dots — blank readout == no dots == the same failure as a blank canvas).
