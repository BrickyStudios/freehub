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

## 0x18FF E-TUBE

The settings and memory channel of E-TUBE PROJECT. Freehub uses it only in the developer options, to read the gear usage history the derailleur keeps itself. Status: **login and the gear-usage command are confirmed on a real RD-R7150** (firmware 4.1.0.0). The command is accepted, but this derailleur — never set up in E-TUBE — answers "nothing stored" (reply command `0x07`), so there is no history to read on it; see below.

| UUID | Name |
|---|---|
| `0x2AF3` | auth control (write, indicate) |
| `0x2AF4` | auth nonce (read, 16 bytes) |
| `0x2AF8` | passkey (six ASCII digits, default `000000`) |
| `0x2AF9` / `0x2AFA` | PCE response / command |
| `0x2AFB` / `0x2AFC` | boot response / command |
| `0x2AFD` / `0x2AFE` | free response / command |
| `0x2AFF` | feature (`FF 00` switches to command mode, live reports pause) |

### Login

The handshake [BikeBridge](https://codeberg.org/bg443/BikeBridge) uses for Shimano e-bike motors:

```
read 2AF4                     → nonce (16 bytes)
write 2AF3  01 + AES-ECB(key = 00011147a53fa2795248 + passkey as ASCII, nonce)
indicate    10 01 01          (10 01 02 = wrong passkey)
write 2AF3  02 + AES-ECB(key = 0002d39ca426c694e30bc922e87bb58d, nonce)
indicate    10 02 01          logged in
write 2AFF  FF 00
```

Confirmed live: the RD answers `10 01 01` to the passkey frame and `10 02 01` to the app frame, so the two BikeBridge AES keys and the default passkey `000000` are correct for the derailleur, not only for e-bike motors. The RD indicates `10 02 01` more than once; act on the first.

### Gear usage history

The unit command is group `0x33`, command `0x04`, payload `00 01 01` (log `0` = travelling time, sequence `1`, transfer mode `1` = high speed). E-TUBE calls it `DCC_CMD_PCA2_HIST2_USE_GET`. A 2×12 reports sequences 1 to 13 (front-low 1–6, front-top 7–12, overflow 13); a rear derailleur alone 1 to 7. The overflow sequence is a bitmask (the counters are 16 bit and wrap at 65,536).

The framing was found by reading the decompiled E-TUBE PROJECT and [BikeBridge](https://codeberg.org/bg443/BikeBridge), then tried against a real RD-R7150. Source citations are kept out of this repo.

**The command is written to `0x2AFA` as `00 13 33 04 00 <seq> 01`** — slot `0x00`, write opcode `0x13`, group `0x33`, command `0x04`, log 0, the sequence, transfer mode 1. The leading `0x13` is the piece Freehub was missing: BikeBridge uses it for every unit command on this characteristic, and the derailleur confirmed it.

The reply on `0x2AF9` is `33 33 <command> <data…>`: byte 0 is the PCE response opcode `0x33`, byte 1 the group `0x33`, byte 2 the reply command. E-TUBE expects the reply command to be the request command with bit 1 set, `0x04 | 2 = 0x06`, for data. The data is plain, not encrypted — only the login on `0x2AF3`/`0x2AF4`/`0x2AF8` is.

**On the tested RD-R7150 the reply command is `0x07`, not `0x06`** — `0x06 | 1`, the derailleur's "nothing stored" answer. It is the same record every time (`33 33 07 3b 00 b9 c1 fd c2 77`), whatever the sequence byte, whether sent once or per sequence; and the DCAS `0x48` form of the command (control byte, body, FCS at the second-to-last byte, end byte — no BLE byte-stuffing, since `setEscape`/`removeEscape` are never called on this path) drew no reply at all. So there is no history to stream: this derailleur was never set up in E-TUBE (it reports its cassette and chainrings as `ff ff ff ff`), so it logged no gear usage, and E-TUBE itself — which keeps only replies whose command byte is the `0x06` history reply — would read nothing here either.

Freehub sends `00 13 33 04 00 01 01` once and reads the reply command: `0x06` would mean gear data to decode (little-endian `uint16` minutes per gear, the last sequence a `uint32` overflow bitmask adding 65,536 per set bit, shown as percentages by the largest-remainder method); `0x07` it reports plainly as "no stored gear usage". A derailleur configured in E-TUBE would be needed to capture and decode a real `0x06` stream.

### Unit commands (the rest of E-TUBE's features)

Every E-TUBE feature is a unit command in the same form — `00 13 <group> <command> <params…>` to `0x2AFA`, reply `33 <group> <command | 2> …` on `0x2AF9`. The group and command bytes come from E-TUBE's own `UnitCommandSetting(…, group, command, …)` call sites (names below only to say what each is for; no Shimano code is in this repo). The reads worth having in a derailleur app:

| group·cmd | name | reads |
|---|---|---|
| `01·2C` | `UNIT_FW_VER_GET` | firmware version |
| `01·94` | `SYS_HAVE_UNIT_NO_GET` | how many units on the system |
| `02·14` | `SYS_SLOT_STOCK_NO_GET` | slot / part number |
| `4A·04` | `TMR_DATE_GET` | the unit's internal date |
| `32·AC` | `PCA_SYNC_GET` | synchronized-shift config (the shift mode) |
| `32·EC` | `PCA_SUB_SYNC_GET` | semi-synchronized config |
| `32·CC` | `PCA_HIST_ERR_GET` | error log, one entry index per request |
| `32·C4` | `PCA_HIST_USE_GET` | older usage history |
| `32·84` | `PCA_DATA_VALUE_PART_UNIT_GET` | a general value reader |
| `0B·EC` | `EXTM_RA_AUTO_SFT_GET` | auto-shift config |
| `33·64` | `PCA2_TAMPERING_INFO_GET` | tampering flags |

Writes (they change the derailleur, so they stay behind a deliberate action, never the read sweep): `0B·F8` `EXTM_RA_SFT_MODE_SEL_SET` sets the shift mode (`00 13 0b f8 <1 = manual, 0 = synchronized> 00`), `0B·E8` auto-shift, `0B·60` multishift, `32·68` `PCA_POSSET_LV_SET` the adjust level (fine-tune). Skipped: SHIMANO ID / cloud, firmware (`0x18FE`), and the e-bike motor / power-meter groups (`16`, `17`, `38`, `39`).

The current shift mode is already in the live `0x2AC1` data (byte 1). To learn every reply format at once, the developer options sweep all the reads above after login and the listener logs each reply; the per-feature decoding and screens follow from that one capture.

Freehub never writes a setting on its own and never touches the firmware service `0x18FE`.

## How this was worked out

A GATT scan of a real RD-R7150 with nRF Connect, then reading how Shimano's own apps parse the data (E-TUBE RIDE 2.0.13, E-TUBE PROJECT Cyclist 5.4.6), for the sole purpose of interoperability with a component the author owns (§ 69e UrhG, Art. 6 of Directive 2009/24/EC). No Shimano code is part of this repository. Earlier work: [BikeBridge](https://codeberg.org/bg443/BikeBridge) (e-bike side of the E-TUBE service) and [shimano-di2-media-controls](https://github.com/NitorCreations/shimano-di2-media-controls) (D-Fly buttons).

Shimano, Di2, D-Fly and E-TUBE are trademarks of SHIMANO INC. This project is not affiliated with Shimano.
