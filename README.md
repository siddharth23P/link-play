# Link Play

A Game Boy Advance emulator for Android, macOS and Windows with online link-cable play, between phones
and computers. No server, no account: one device hosts, friends join with a code.

Built on the [mGBA](https://mgba.io) core. No games are included: bring your own ROM.

## Features

- **Link cable over the internet, 2–4 players.** The host shares an invite code (`LP1I-…`); friends
  paste it in *Join a game*. If the host's network blocks the friend, the friend sends back a reply
  code (`LP1R-…`) and the host pastes it. On Android, codes can also be shared straight to the app
  from a chat app (*Share → Link Play*).
- **Phones and computers play together.** The Android app and the macOS and Windows apps use the same
  codes and the same protocol, so any mix of them can share a room.
- **Direct and encrypted.** Devices talk to each other over UDP, encrypted with AES-128-GCM under a key
  that only the invite carries. Nothing goes through a server.
- **Same game for everyone.** A room checks that every player has the same ROM (SHA-1).
- **Autosave** every 5 minutes, plus save states and fast forward (hold for 4×, double-tap to lock).
  Snapshots never touch the in-game save.
- **Filters:** on Android Pixel, Sharp, Smooth, AMD FSR 1.0 (default), MMPX, and an AI upscaler (Anime4K CNN);
  on computers Pixel, Sharp (default) and Smooth.
- **Controls:** on a phone, an on-screen D-pad or thumbstick, kept clear of camera cutouts, and game
  controllers; on a computer, the keyboard (see [Keyboard controls](#keyboard-controls)).
- **Import and export** of in-game saves (`.sav`), to back up or move to another device.

## Install on Android

1. Download the APK from the [Releases](../../releases) page.
2. Open it on your phone and allow installing from that app when Android asks.
3. In Link Play, tap *Choose ROM file* and pick your `.gba` file.

Needs Android 9 or newer on a 64-bit ARM phone (arm64-v8a).

## Install on a computer

Download the file for your computer from the [Releases](../../releases) page. The computer apps are not
signed with a paid developer certificate, so macOS and Windows warn the first time. That is expected;
the steps below get past it. Then choose your `.gba` file in Link Play, as on a phone.

### Mac

Needs macOS 11 or newer. Take `LinkPlay-<version>-macos-arm64.dmg` for an Apple Silicon Mac (M1 and
later) or `LinkPlay-<version>-macos-x64.dmg` for an Intel Mac.

1. Open the dmg and drag *Link Play* onto *Applications*. Eject the dmg.
2. Open *Link Play* from Applications. macOS says it cannot verify the app. Click *Done*.
3. Open *System Settings → Privacy & Security*, scroll down to *Security*, and click *Open Anyway* next
   to "Link Play was blocked". Enter your password, then click *Open*. This is needed once.
   (The button only shows up after the blocked attempt in step 2.)

On macOS 14 and earlier you can also right-click the app and choose *Open*. To skip the dialogs, run this
once in Terminal before the first start:

```bash
xattr -dr com.apple.quarantine "/Applications/Link Play.app"
```

macOS 15 and later may ask whether Link Play can find devices on your local network. Click *Allow*. If
friends on the same Wi-Fi cannot connect, check *System Settings → Privacy & Security → Local Network*
and switch *Link Play* on.

### Windows

Needs Windows 10 or 11, 64-bit (x64). Take `LinkPlay-<version>-windows-x64.exe` (installer) or
`LinkPlay-<version>-windows-x64.zip` (portable: unzip it and run `Link Play\Link Play.exe`).

1. Run the installer. Windows shows "Windows protected your PC": click *More info*, then *Run anyway*.
   The installer needs no administrator rights; it adds a Start menu entry and a desktop shortcut.
2. The first time you host or join, Windows Firewall asks about Link Play. Tick *Private networks* and
   click *Allow access*.

For the zip, right-click the downloaded file, choose *Properties*, tick *Unblock* and click *OK*
before unzipping; this avoids the warning for every file. Windows 11 with *Smart App Control* switched on
blocks apps that are not signed and has no *Run anyway*: turn it off under *Windows Security → App &
browser control*, or use the phone app.

Where Link Play keeps its files: `~/Library/Application Support/Link Play` on a Mac and
`%LOCALAPPDATA%\Link Play` on Windows (your ROM copy, saves, save states, settings and `log.txt`).
Back up the `saves` folder, or use *Export save* in the app.

- **Importing a save** (*Import save*, or dropping a `.sav` file on the window) asks first. The save it
  replaces stays in the `saves` folder as `<ROM SHA-1>.sav.<yyyyMMdd-HHmmss>.bak`, and the newest 5 of
  these backups are kept. Autosaves of that game are removed (they would no longer match the new save);
  your save-state slots stay.
- **Changing the ROM** keeps the old game's save and save states in the data folder: choose that ROM again
  and they are back. Only the copy of the ROM file itself is replaced. Dropping a ROM on the window asks
  first when it would replace the current one.
- **Quitting** (closing the window, or Cmd+Q on a Mac) while you are in a room asks first, because it ends
  the link for the others. A host who is alone in the room is not asked.

### Keyboard controls

You can change every key in the app (*Controls*). The defaults:

| Key | Does |
| --- | --- |
| Arrow keys | D-pad |
| X / Z | A / B |
| A / S | L / R |
| Enter / Backspace | Start / Select |
| Hold Tab | Fast forward (4×); double-tap Tab to keep it on. Off while linked. |
| Esc | Game menu |
| F1–F3 / Shift+F1–F3 | Load / save state slot 1–3 |
| Ctrl/Cmd + and - | Window size |
| F11 (Windows), Ctrl+Cmd+F (Mac) | Full screen |

Mac laptops need the Fn key for F1–F3 (Fn+F1 and so on), unless the function keys are set to standard
function keys in *System Settings → Keyboard*. The *Game* menu has *Save State* and *Load State* too.

The computer apps have no gamepad support yet: keyboard only.

## Play together

1. **Host:** *Host a game* → *Share invite* (on a computer: *Copy*), and send the code to your friends.
2. **Friend:** *Join a game* → paste the message (the whole message is fine).
3. If it doesn't connect within a few seconds, the friend taps *Share reply* (on a computer: *Copy*)
   and sends that code back. The host taps *Paste reply code*.
4. Everyone goes to the game's link-cable spot (in Pokémon games, the Pokémon Center upstairs).

### Which networks work

- **IPv6 (most mobile data, many home Wi-Fi networks):** works directly, usually with the invite alone.
- **Wi-Fi without IPv6:** on a phone, the app moves link play to mobile data when that has IPv6 (Wi-Fi
  stays on for everything else). *Advanced → Keep link play on this Wi-Fi* turns that off. A computer
  uses its normal connection, so it needs IPv6 or a router that opens a port (next point).
- **IPv4 only:** works when the host's router opens a port by UPnP, NAT-PMP or PCP. Two devices that
  both sit behind carrier-grade NAT with no IPv6 can't connect: there is no relay, by design.
- **Same Wi-Fi or Ethernet:** devices in one home find each other directly, phones and computers alike.
- *Advanced → STUN* (off by default) asks public STUN servers for your IPv4 address, which helps on
  some networks. It contacts Google and Cloudflare, so it is opt-in.

## Build from source

### Android

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

### macOS and Windows app

The computer app is Kotlin and Swing on the same mGBA core and link-play code as the phone app, packaged
with `jlink` and `jpackage` from the JDK (no plugins). You need JDK 17 (`JAVA_HOME` pointing at it) and
CMake 3.22 or newer, and a C compiler. `-PdesktopOnly=true` leaves the Android app out of the build, so
no Android SDK is needed. Clone with `--recursive` (mGBA is a submodule).

On a Mac (Xcode command line tools and CMake, for example `brew install cmake`):

```bash
cd android
./gradlew -PdesktopOnly=true :desktop:run                   # start it from Gradle
./gradlew -PdesktopOnly=true :desktop:selfTest              # headless check of the emulator core
./gradlew -PdesktopOnly=true :desktop:packageDmg            # desktop/build/dist/LinkPlay-<version>-macos-<arm64|x64>.dmg
```

The dmg is for the architecture of the Mac that builds it. `:desktop:packageAppImage` stops one step
earlier and leaves `Link Play.app` in `desktop/build/app-image`.

On Windows, build inside the MSYS2 **UCRT64** shell. The one-time setup:

1. Install a 64-bit JDK 17 (for example Temurin) and WiX Toolset 3.x (`choco install wixtoolset`; JDK 17's
   `jpackage` does not work with WiX 4 or newer).
2. Install [MSYS2](https://www.msys2.org) and, in its UCRT64 shell,
   `pacman -S mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja`.
3. Start the UCRT64 shell with the Windows PATH kept, so it sees Java and WiX as well:
   `C:\msys64\msys2_shell.cmd -ucrt64 -here -use-full-path`.

Then, in that shell:

```bash
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot'   # your JDK 17
cd android
./gradlew -PdesktopOnly=true :desktop:packageExe :desktop:packageZip   # desktop/build/dist/LinkPlay-<version>-windows-x64.{exe,zip}
```

`:desktop:run` and `:desktop:selfTest` work there too. Build options: `-PappArgs='--data /tmp/x'` passes
arguments to `run`; `-PskipNative=true` leaves out the native library.

For testing without a window, `--cli-host <rom>` and `--cli-join <rom> <codeFile|->` host or join a room
from the command line. They use a throw-away data folder, so your real ROM and saves are never touched,
unless you name one with `--data <dir>`.

### Automatic builds

`.github/workflows/build.yml` builds everything on GitHub Actions for pushes, pull requests and by
hand (*Actions → Build → Run workflow*): the Android APK, the two Mac dmgs and the Windows installer and
zip. Each desktop build runs the app's self-test on the packaged app first. Pushing a tag such as `v1.1.0`
(it has to match `version` in `desktop/build.gradle.kts`) also collects the files into a draft release.

The APK is only a signed release build when the repository has the secrets `ANDROID_KEYSTORE_B64` (the
`.jks` file, base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.
Otherwise CI builds the debug APK, keeps it as a CI artifact and does not put it in a release.

## Project layout

| Path | What |
| --- | --- |
| `android/` | The phone app: Kotlin UI and networking (`com.linkplay.app.net` is shared with the computer app), GLES renderer (`cpp/render.c`) |
| `desktop/` | The macOS and Windows app: Swing UI, `native/CMakeLists.txt` for its JNI library, `packaging/` (icons, Info.plist, installer checks) |
| `native/bridge.c` | JNI bridge between Kotlin and mGBA, one file for the phone and the computer app |
| `native/netlink.c` | NetLink: mGBA's serial port driven by a sequenced stream of link events |
| `native/touchnav*.c` | Touch navigation for menus (work in progress, switched off) |
| `.github/workflows/` | CI: APK, dmgs, Windows installer, releases |
| `vendor/mgba` | mGBA, as a git submodule (unmodified) |
| `scripts/` | Desktop dev tools: `build-native.sh`, `build-probe.sh` (RAM probe), `kt.sh` |

## Legal

Link Play does not include any game, BIOS or copyrighted game data. Use ROMs you have the right to use.

Game Boy Advance is a trademark of Nintendo. Pokémon is a trademark of Nintendo, Creatures and Game
Freak. This project is not affiliated with or endorsed by any of them.

## License

Link Play's own code is MIT licensed, see [LICENSE](LICENSE). It links mGBA (MPL-2.0); third-party code
and its licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
