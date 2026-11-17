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
| `0x18FF` | E-TUBE | E-TUBE PROJECT (settings, logs) |
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

## 0x18FF E-TUBE (not used by Freehub yet)

The settings channel of E-TUBE PROJECT. It needs an authentication handshake first (passkey plus an app key, AES over a nonce from `0x2AF4`); [BikeBridge](https://codeberg.org/bg443/BikeBridge) implements it for Shimano e-bike motors, and the derailleur uses the same characteristics.

| UUID | Name |
|---|---|
| `0x2AF3` | auth control |
| `0x2AF4` | auth nonce |
| `0x2AF8` | passkey (default `000000`) |
| `0x2AF9` / `0x2AFA` | PCE response / command |
| `0x2AFB` / `0x2AFC` | boot response / command |
| `0x2AFD` / `0x2AFE` | free response / command |
| `0x2AFF` | feature |

The derailleur keeps its own gear usage history (time per chainring and cog) which E-TUBE PROJECT reads over the PCE channel with command group `0x33`, command `0x04`. Reading it is planned for Freehub 0.2. Freehub never writes to `0x18FF` or `0x18FE`.

## How this was worked out

A GATT scan of a real RD-R7150 with nRF Connect, then reading how Shimano's own apps parse the data (E-TUBE RIDE 2.0.13, E-TUBE PROJECT Cyclist 5.4.6), for the sole purpose of interoperability with a component the author owns (§ 69e UrhG, Art. 6 of Directive 2009/24/EC). No Shimano code is part of this repository. Earlier work: [BikeBridge](https://codeberg.org/bg443/BikeBridge) (e-bike side of the E-TUBE service) and [shimano-di2-media-controls](https://github.com/NitorCreations/shimano-di2-media-controls) (D-Fly buttons).

Shimano, Di2, D-Fly and E-TUBE are trademarks of SHIMANO INC. This project is not affiliated with Shimano.
