# MirrorDeck

Cast your Android phone's screen to any other Android device on the same
Wi-Fi — no accounts, no ads, no setup wizard, and no IP addresses to type.
Devices find each other automatically; you tap a name and you're casting.

MirrorDeck exists because most casting apps are overly complex and
unreliable. This one does a single job, simply.

## How it works

Install the **same app on both devices**, on the same Wi-Fi network:

1. On the receiving device: open MirrorDeck → **Show a screen here**.
   It announces itself under a friendly name like
   "MirrorDeck Google Pixel Tablet" and waits.
2. On the sending phone: open MirrorDeck → **Share my screen**.
   The receiver appears in the list by itself.
3. **Tap its name**, approve Android's screen-share prompt, and your
   screen appears on the other device. Press Home and open whatever
   you want to show. Tap **Stop casting** when done.

## Automatic discovery — three finders at once

Any single discovery method can be blocked by a router, so MirrorDeck
runs three independent ones and merges the results:

1. **NSD / mDNS** — the receiver advertises `_mirrordeck._tcp.` on the LAN.
2. **UDP broadcast** — the sender broadcasts a discovery shout on port
   47476 and receivers answer directly with their name and stream port.
   This works on routers that filter multicast, where mDNS fails.
3. **Subnet scan** — if nothing has been found after a few seconds, the
   sender quietly scans its own /24 for the MirrorDeck stream port
   (47475) as a last resort.

A manual-address entry exists behind a small link on the sender screen,
purely as a fallback — the normal flow never needs it.

## The stream

- Sender: Android **MediaProjection** → VirtualDisplay → hardware
  **H.264** encoder (long edge capped at 1280 px, 4 Mbps, 30 fps)
  → TCP socket.
- Receiver: H.264 decoder rendering full-screen.
- Wire protocol (`Proto.java`): a small header (magic `CDK1`, dimensions,
  SPS/PPS) followed by length-prefixed access units.
- Video only for now — no audio yet. One sender to one receiver,
  same network only. Everything stays on your LAN; nothing leaves it.

## Layout

- `MainActivity` — pick a role: share, or show.
- `SenderActivity` — the three finders + device list; tap to cast.
- `ScreenCastService` — mediaProjection foreground service that captures
  and streams the screen.
- `ReceiverActivity` — advertises, listens on TCP 47475, decodes.
- `Proto` — ports, discovery messages, and the stream framing.

Package: `com.mirrordeck.app` · minSdk 24 · targetSdk 34 · plain Java,
no dependencies beyond the Android SDK.

## Build

No Gradle. You need a JDK 17+ and an Android SDK (platform android-34,
build-tools 34.0.0):

```sh
export ANDROID_HOME=/path/to/android-sdk   # default: ~/android-sdk
./build.sh                                  # writes MirrorDeck-v1.2.apk
```

`build.sh` runs aapt2 → javac → dex → zipalign → apksigner and generates
a local debug keystore on first run (personal test builds, not a Play
Store release). Dexing prefers a standalone R8 jar at `tools/r8.jar`
(`R8_JAR` to override): the d8 bundled with build-tools 34 crashes on
this code's anonymous NSD listener classes; any newer standalone R8
(8.5+) works.

## Status

Working prototype (v1.2). Known limits: video only, 1-to-1, same
Wi-Fi network.
