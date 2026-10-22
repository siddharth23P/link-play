# Link Play

A Game Boy Advance emulator for Android with online link-cable play, phone to phone. No server, no
account: one phone hosts, friends join with a code.

Built on the [mGBA](https://mgba.io) core. No games are included: bring your own ROM.

## Features

- **Link cable over the internet, 2–4 players.** The host shares an invite code (`LP1I-…`); friends
  paste it in *Join a game*. If the host's network blocks the friend, the friend sends back a reply
  code (`LP1R-…`) and the host pastes it. Codes can also be shared straight to the app from a chat
  app (*Share → Link Play*).
- **Direct and encrypted.** Phones talk to each other over UDP, encrypted with AES-128-GCM under a key
  that only the invite carries. Nothing goes through a server.
- **Same game for everyone.** A room checks that every player has the same ROM (SHA-1).
- **Autosave** every 5 minutes, plus save states and fast forward (hold for 4×, double-tap to lock).
  Snapshots never touch the in-game save.
- **Filters:** Pixel, Sharp, Smooth, AMD FSR 1.0 (default), MMPX, and an AI upscaler (Anime4K CNN).
- **Controls:** on-screen D-pad or thumbstick, kept clear of camera cutouts; game controllers work too.
- **Import and export** of in-game saves (`.sav`), to back up or move to another phone.

## Install

1. Download the APK from the [Releases](../../releases) page.
2. Open it on your phone and allow installing from that app when Android asks.
3. In Link Play, tap *Choose ROM file* and pick your `.gba` file.

Needs Android 9 or newer on a 64-bit ARM phone (arm64-v8a).

## Play together

1. **Host:** *Host a game* → *Share invite*, and send the code to your friends.
2. **Friend:** *Join a game* → paste the message (the whole message is fine).
3. If it doesn't connect within a few seconds, the friend taps *Share reply* and sends that code
   back. The host taps *Paste reply code*.
4. Everyone goes to the game's link-cable spot (in Pokémon games, the Pokémon Center upstairs).

### Which networks work

- **IPv6 (most mobile data, many home Wi-Fi networks):** works directly, usually with the invite alone.
- **Wi-Fi without IPv6:** the app moves link play to mobile data when that has IPv6 (Wi-Fi stays on
  for everything else). *Advanced → Keep link play on this Wi-Fi* turns that off.
- **IPv4 only:** works when the host's router opens a port by UPnP, NAT-PMP or PCP. Two phones that
  both sit behind carrier-grade NAT with no IPv6 can't connect: there is no relay, by design.
- *Advanced → STUN* (off by default) asks public STUN servers for your IPv4 address, which helps on
  some networks. It contacts Google and Cloudflare, so it is opt-in.

## Build from source

You need JDK 17, the Android SDK (platform 37), NDK 28.2.13676358 and CMake 3.22.1.

```bash
git clone --recursive https://github.com/siddharth23P/link-play.git
cd link-play/android
./gradlew assembleDebug
```

Point Gradle at your SDK with `ANDROID_HOME` or `android/local.properties` (`sdk.dir=…`). The debug
build installs next to the release build, as *Link Play dev*.

Tests (plain JVM, mostly networking): `./gradlew test`.

### Release build

Create `android/keystore.properties` (it is git-ignored, never commit it):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Then run `./gradlew assembleRelease`. Keep the keystore safe: a new version only installs over an old
one when both are signed with the same key.

## Project layout

| Path | What |
| --- | --- |
| `android/` | The app: Kotlin UI and networking, JNI bridge (`cpp/bridge.c`), GLES renderer (`cpp/render.c`) |
| `native/netlink.c` | NetLink: mGBA's serial port driven by a sequenced stream of link events |
| `native/touchnav*.c` | Touch navigation for menus (work in progress, switched off) |
| `vendor/mgba` | mGBA, as a git submodule (unmodified) |
| `scripts/` | Desktop dev tools: `build-native.sh`, `build-probe.sh` (RAM probe), `kt.sh` |

## Legal

Link Play does not include any game, BIOS or copyrighted game data. Use ROMs you have the right to use.

Game Boy Advance is a trademark of Nintendo. Pokémon is a trademark of Nintendo, Creatures and Game
Freak. This project is not affiliated with or endorsed by any of them.

## License

Link Play's own code is MIT licensed, see [LICENSE](LICENSE). It links mGBA (MPL-2.0); third-party code
and its licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
