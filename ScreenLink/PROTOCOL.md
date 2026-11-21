# ScreenLink wire protocol (version 1)

Implemented in `android/app/src/main/java/io/screenlink/app/net/Protocol.kt` and
`windows/src/ScreenLink.Core/Protocol/Protocol.cs`. Both test suites check the same byte layouts.

## Discovery (UDP 47421)

Phone → broadcast: `SCREENLINK?1` (ASCII).
Laptop → phone (unicast): `SCREENLINK!1\n<tcp port>\n<laptop certificate SHA-256, 64 hex chars>\n<name>` (UTF-8).

## Connection (TCP 47420, or up to 47429 if taken)

Mutual TLS (1.3, or 1.2 on Windows 10). Then frames: `type:u8 | length:u32 BE | payload` (payload ≤ 4 MiB).
Integers are big-endian; strings are `len:u16 | UTF-8`.

| Type | Name | Direction | Payload |
|---|---|---|---|
| 0x01 | HELLO | both | `version:u16, name:string` (name ≤ 128 bytes) |
| 0x02 | PAIR_CONFIRM | phone → laptop | `ok:u8` (user confirmed the code, or already paired) |
| 0x03 | PAIR_RESULT | laptop → phone | `ok:u8` (1 = connected) |
| 0x10 | VIDEO_CONFIG | phone → laptop | `width:u16, height:u16, SPS+PPS (Annex B)` |
| 0x11 | VIDEO_FRAME | phone → laptop | `pts_us:i64, flags:u8 (1 = keyframe), H.264 Annex B` |
| 0x12 | REQUEST_KEYFRAME | laptop → phone | — |
| 0x13 | CAST_STATE | phone → laptop | `casting:u8` |
| 0x20 | KEY_TEXT | phone → laptop | UTF-8 text ≤ 1024 bytes (`\n` = Enter, `\t` = Tab) |
| 0x21 | KEY_PRESS | phone → laptop | `vk:u16` (Windows virtual-key, allow-listed), `modifiers:u8` (1 Ctrl, 2 Alt, 4 Shift, 8 Win) |
| 0x30 / 0x31 | PING / PONG | both | `token:8 bytes` (echoed) |
| 0x3F | BYE | both | — |

Unknown types are ignored. Pairing code: `SHA-256("ScreenLink-pair-v1" ‖ laptopFp ‖ phoneFp)`, first 4 bytes as an
unsigned big-endian integer, mod 1 000 000, zero-padded to 6 digits.
Test vector: laptopFp = bytes 0..31, phoneFp = bytes 32..63 → `337226`.
