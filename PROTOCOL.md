# Shimano Di2 Bluetooth LE protocol (12-speed)

What a 12-speed Di2 rear derailleur (RD-R7150, RD-R8150, RD-R9250 and relatives) exposes over Bluetooth LE, as far as Freehub uses it. Checked against an RD-R7150 (105 Di2) with firmware read live over GATT.

All Shimano UUIDs share one base: `0000XXXX-5348-494d-414e-4f5f424c4500`. The last twelve bytes are ASCII for `SHIMANO_BLE`.

## Advertisement

| Field | Value |
|---|---|
| Local name | `RDR7150 123` (model without dash, then a number) |
| Manufacturer data | company `0x044A` (SHIMANO INC.) |
| 128-bit service | `000018ef-…` |
| Appearance | 1152, Generic Cycling |

The derailleur advertises only while awake. It sleeps when the bike stands still; one click on a shifter wakes it. A plain connection works without bonding.

## Services

| UUID | Name | Used by |
|---|---|---|
| `0x18EF` | Shimano Bicycle Information (SBI) | bike computers, E-TUBE RIDE, **Freehub** |
| `0x18FF` | E-TUBE | E-TUBE PROJECT (settings, logs), **Freehub** (reads the stored settings) |
| `0x18FE` | DFU | firmware updates |
| `0x180F` | Battery | standard |
| `0x180A` | Device Information | standard |

## 0x18EF Shimano Bicycle Information

| UUID | Name | Properties |
|---|---|---|
| `0x2AC0` | Feature | read, indicate |
| `0x2AC1` | Periodic information | notify |
| `0x2AC2` | Instantaneous information | indicate |
| `0x2AC3` | D-Fly channel switch information | read, indicate |
| `0x2AC4` | SBI control point | write, indicate |

Enabling notifications on `0x2AC1` is enough to get the gears; no control point write is needed.

### 0x2AC1: periodic information

Byte 0 is the record type. Type `0` is the Di2 gear record; type `7` carries speed and cadence when a sensor feeds the system (the derailleur alone sends none). Other types belong to e-bike systems.

**Type 0, Di2 gears (17 bytes)**

| Byte | Meaning | Notes |
|---|---|---|
| 0 | record type | `0` |
| 1 | shift mode | 0 manual, 1 adjust, 2 auto, 3 RD protection reset, 4 auto 2, 5 synchro S1, 6 synchro S2, 255 unknown |
| 2 | multi-shift interval | 255 unknown |
| 3 | front position | 1-based, 0 reserved, 255 unknown |
| 4 | front positions in total | |
| 5 | rear position | 1-based, 0 reserved, 255 unknown |
| 6 | rear positions in total | 12 on 12-speed |
| 7–9 | front fine-tune: current, min, max | signed, −128 unknown |
| 10–12 | rear fine-tune: current, min, max | signed, −128 unknown |
| 13–14 | chainring pattern | little-endian, see table |
| 15–16 | cassette pattern | little-endian, see table |

On both derailleurs the highest position is the hardest gear. With a pattern listed smallest cog first, the teeth in use are `pattern[max − position]`. Example from a 105 Di2 on the big ring, 7th of 12:

```
00 05 ff 02 02 07 0c 00 00 00 00 f8 08 06 00 12 00
```

Mode synchro S1, front 2 of 2, rear 7 of 12, rear fine-tune 0 (range −8 to +8), chainrings pattern 6 (50/34), cassette pattern 18 (11–34): the gear is 50 × 17.

**Chainring patterns** (largest first)

| ID | Teeth | ID | Teeth | ID | Teeth |
|---|---|---|---|---|---|
| 0 | 38/28 | 5 | 52/36 | 10 | 52/38 |
| 1 | 36/26 | 6 | 50/34 | 11 | 46/36 |
| 2 | 34/24 | 7 | 54/42 | 12 | 48/31 |
| 3 | 40/30/22 | 8 | 55/42 | 13 | 46/30 |
| 4 | 52/39 | 9 | 53/39 | 14 | 54/40 |

255 means a single ring.

**Cassette patterns** (smallest first)

| ID | Teeth |
|---|---|
| 0 | 11-13-15-17-19-21-24-27-31-35-40 |
| 1 | 11-13-15-17-19-21-24-28-32-37-42 |
| 2 | 11-12-13-14-15-16-17-19-21-23-25 |
| 3 | 11-12-13-14-15-17-19-21-23-25-28 |
| 4 | 11-12-13-14-15-17-19-21-24-27-30 |
| 5 | 12-13-14-15-16-17-18-19-21-23-25 |
| 6 | 12-13-14-15-16-17-19-21-23-25-28 |
| 7 | 11-12-13-14-15-16-17-18-19-21-23 |
| 8 | 11-12-13-14-16-18-20-22-25-28-32 |
| 9 | 14-15-16-17-18-19-20-21-23-25-28 |
| 10 | 11-13-15-17-19-21-24-28-32-37-46 |
| 11 | 11-13-15-17-19-21-23-25-27-30-34 |
| 12 | 11-13-15-17-20-23-26-30-36-43-50 |
| 13 | 11-13-15-17-20-23-26-30-34-39-45 |
| 16 | 11-12-13-14-15-16-17-18-19-21-24-28 |
| 17 | 11-12-13-14-15-16-17-19-21-24-27-30 |
| 18 | 11-12-13-14-15-17-19-21-24-27-30-34 |
| 19 | 14-15-16-17-18-19-20-22-24-27-30-34 |
| 20 | 11-12-13-14-15-17-19-21-24-28-32-36 |
| 21 | 10-12-14-16-18-21-24-28-33-39-45-51 |
| 22 | 9-11-13-15-17-19-21-24-28-33-39-45 |
| 32 | 11-13-15-17-20-23-26-30-36-43 |
| 33 | 11-13-15-17-20-23-28-34-41-48 |

### 0x2AC2: instantaneous information

High nibble of byte 0 is the type, low nibble a sequence number that changes with every event.

* Type `2`, switch state: bytes 1–4 are D-Fly channels 1–4. The high nibble of each is the press: `1` short, `2` long, `4` double, `F` nothing on that channel.
* Type `1`, alert: byte 1 is the alert pattern (`3` = over the limit of operations).

Some phones need a bonded link before the indication arrives. Pairing is "just works", no PIN.

## 0x18FF E-TUBE

The settings channel of E-TUBE PROJECT. Freehub uses it to read what the derailleur has stored about itself (Bike → "Stored in the derailleur"). Everything below is confirmed on two real RD-R7150 (firmware 4.1.0.0): one never set up in E-TUBE, one set up.

| UUID | Name |
|---|---|
| `0x2AF3` | auth control (write, indicate) |
| `0x2AF4` | auth nonce (read, 16 bytes) |
| `0x2AF5` | written `01` then `00` while the session opens |
| `0x2AF8` | passkey (six ASCII digits, default `000000`) |
| `0x2AF9` / `0x2AFA` | PCE response / command |
| `0x2AFB` / `0x2AFC` | boot response / command |
| `0x2AFD` / `0x2AFE` | free response / command |
| `0x2AFF` | feature (`FF 00` switches to command mode, live reports pause) |

### Login

The handshake [BikeBridge](https://codeberg.org/bg443/BikeBridge) uses for Shimano e-bike motors works for the derailleur too:

```
read 2AF4                     → nonce (16 bytes)
write 2AF3  01 + AES-ECB(key = 00011147a53fa2795248 + passkey as ASCII, nonce)
indicate    10 01 01          (10 01 02 = wrong passkey)
write 2AF3  02 + AES-ECB(key = 0002d39ca426c694e30bc922e87bb58d, nonce)
indicate    10 02 01          logged in (it may come more than once; act on the first)
```

### The session

What E-TUBE sends right after the login, taken byte for byte from an Android HCI snoop of E-TUBE PROJECT and then reproduced by Freehub:

```
write 2AFF  ff 00             command mode
write 2AFA  00 05             reply 25 …
write 2AFA  00 13 01 1c 00    unit id
write 2AF5  01, then 00
write 2AFA  00 13 01 2c 00    firmware version
write 2AFA  00 03 00          setProtocolMode → the derailleur streams its settings on 2AFD
```

**Press the button on the back of the derailleur first.** E-TUBE asks for it too; without it the session opens but nothing streams.

A write to `0x2AFA` is `00 <control> <payload>`: byte 0 is the BLE block header (`00` for one block), then the control byte. Replies on `0x2AF9` lead with `control | 0x20` (`13` → `33`, `03` → `23`, `05` → `25`). A unit command is control `0x13`: `00 13 <group> <command> <params…>`, answered `33 <group> <reply command> <data…>`. The reply command tells data from no data: `command | 2` when the unit has something, `command | 3` when it has nothing.

When the session is done, drop the link: the derailleur stays in command mode until it reconnects.

### The settings stream on 0x2AFD

After `00 03 00` the derailleur sends one notification per stored setting, unasked, as `00 <group> <command> <p0 p1 p2 p3>`. Every notification is 10 bytes; whatever the record does not fill is left over from an earlier frame, so the tail means nothing. The stream is not complete every time: on the same bike one read brought `0B·18`, `0B·00` and `0B·20`, the next did not. Freehub keeps an earlier value where a later read is silent. `0xFF` in a parameter means "not stored". Group `0x0A` is the front derailleur, `0x0B` the rear, `0x20` common, `0x26` the battery, `0x40` radio, `0x4A` the clock.

| group·cmd | meaning | example |
|---|---|---|
| `0B·18` | rear positions, min and max | `01 0c` = 1–12 |
| `0A·18` | front positions, min and max | `01 02` = 1–2 |
| `0B·08` | current rear position | `0a` = 10 |
| `0A·08` | current front position | `01` |
| `0B·00` | shift mode, same codes as byte 1 of `0x2AC1`: `0` manual, `5` S1, `6` S2 | read `00` in manual and `06` after two presses on the derailleur's button |
| `0A·B0` | chainring pattern (table above) | `06 00` = 50/34 |
| `0B·B0` | cassette pattern (table above) | `12 00` = 11–34 |
| `0B·20` | rear fine-tune range, signed | `ee 12` = −18 … +18 |
| `26·10` | battery state of charge | `64` = 100 % |
| `26·40` | capacity left | `5a` = 90 % |
| `20·0C` | suspension (none on a road bike) | `00` |

The bike set up in E-TUBE stored `0A·B0 06 00` and `0B·B0 12 00`: exactly its 50/34 crankset and 11–34 cassette. The one never set up stores `ff ff` there and also reports `ff ff ff ff` as its patterns in `0x2AC1`, which is why Freehub lets the rider pick the teeth by hand.

Not decoded yet: `0B·0C`, `0B·14`, `0B·44`, `0B·54`, `0B·6C`, `0B·7C`, `0A·10`, `01·B0`, `02·00`, `40·48`. **Error log:** `00 13 32 cc 00` (index 0) answers `33 32 cf …` on `0x2AF9` when the log is empty (the never-set-up RD-R7150). A derailleur with entries sends them as `32·CE` records (`00 32 ce 00 00 01 01`, `00 32 ce 01 09 00 00` from the bike set up in E-TUBE); Freehub shows whether there are entries, decoding them needs more captures. **Synchronised-shift map:** `00 13 32 ac 10 01 00 00` gets `33 32 af` (nothing) on a derailleur that runs manual front shifting; not captured with data so far. After the reads, `00 02 00 40` repeats on `0x2AFD` about every 0.75 s until the link drops.

### Gear usage history

E-TUBE's gear usage request is `00 13 33 04 00 01 01` (group `0x33`, command `0x04`). Both test derailleurs answer `33 33 07 …`, that is `0x04 | 3`: nothing stored. A derailleur that logged gear usage would answer `0x06` with little-endian `uint16` minutes per gear; none has been seen yet.

### Writes

Freehub never writes to the derailleur. A shift-mode write was tried and dropped: `00 13 0b f8 00 00` answers `33 0b fb`, the "no" reply (`0xF8 | 3`), and the mode does not change. E-TUBE never writes a mode in the snoop either. On 12-speed Di2 the rider switches modes at the bike: two presses on the button of the rear derailleur step manual → S1 → S2, and its LED shows the mode (blue for 2 s = manual, 2 blinks = S1, 3 blinks = S2). Skipped on purpose: fine-tune, button mapping, teeth patterns, SHIMANO ID, the firmware service `0x18FE`, and the e-bike groups (`16`, `17`, `38`, `39`).

### Firmware versions

The derailleur reports its firmware as a string in `0x2A26` (`4.1.0.0` on both test derailleurs). Freehub compares it with a list of Shimano's releases that ships with the app (`Firmware.kt`, taken from Shimano's firmware page) and says what is newer. It does not install anything: firmware goes on through E-TUBE PROJECT Cyclist, and Freehub stays off the derailleur meanwhile so the two apps don't fight over the link.

What the releases since 4.1.0 mean for Freehub, as far as Shimano's notes say:

| Version | Date | Note | For Freehub |
|---|---|---|---|
| 4.2.0 / 4.2.1 | May 2024 | FRONT SHIFT NEXT button function | a button mapping, nothing new on the wire Freehub reads |
| 4.2.3 | Aug 2024 | wireless link between levers and derailleur secured; levers need 4.2.x too, wired only | lever ↔ derailleur radio, not the Bluetooth LE side |
| 4.3.0 | May 2025 | MULTI PURPOSE channels | none known |
| 4.3.1 | Dec 2025 | E-TUBE needs 5.4.4 or newer to connect | Freehub's E-TUBE handshake comes from E-TUBE 5.4.6, so it should still log in; not yet checked on a derailleur with 4.3.1 or later |
| 4.4.0 | Aug 2026 | pairing only with matching levers and derailleurs | lever pairing, not the phone |

The gear, battery and button reports (`0x18EF`) are the ones bike computers use, so Shimano has reason to keep them stable. A capture from a derailleur on 4.3.1 or newer would settle the rest.

## How this was worked out

A GATT scan of a real RD-R7150 with nRF Connect, reading how Shimano's own apps parse the data (E-TUBE RIDE 2.0.13, E-TUBE PROJECT Cyclist 5.4.6), and an Android Bluetooth HCI snoop of E-TUBE PROJECT talking to a derailleur the author's family owns, for the sole purpose of interoperability with a component the author owns (§ 69e UrhG, Art. 6 of Directive 2009/24/EC). No Shimano code is part of this repository. Earlier work: [BikeBridge](https://codeberg.org/bg443/BikeBridge) (e-bike side of the E-TUBE service) and [shimano-di2-media-controls](https://github.com/NitorCreations/shimano-di2-media-controls) (D-Fly buttons).

Shimano, Di2, D-Fly and E-TUBE are trademarks of SHIMANO INC. This project is not affiliated with Shimano.
