# DropFoto

An Android app that connects to a Nikon D5500 over the camera's built-in Wi-Fi, shows
thumbnails of the card, and downloads RAW+JPEG files to the phone. It's a reliable
replacement for Nikon's discontinued Wireless Mobile Utility.

**Status:** Phase 2 done. The Android app connects to the real D5500 from a Galaxy S21 Ultra, lists the
card and reconnects on its own when the camera's Wi-Fi drops. The photo gallery comes next (Phase 3).

- [Plan](docs/PLAN.md): approach, architecture and phases
- [Phase 0 camera test](docs/phase0-camera-test.md): how to check the real D5500 with the laptop tool
- [Phase 2 phone test](docs/phase2-phone-test.md): installing DropFoto on the phone and testing the connection

## Layout

| Module | What it is |
|---|---|
| `ptpip/` | PTP/IP protocol library in pure Kotlin (no Android dependencies), plus a simulated camera for tests (`src/testFixtures`) |
| `ptpip-cli/` | Laptop tool: `info`, `list`, `thumb`, `get`, `get-all`, `soak`, `fake-server` |
| `app/` | Android app: camera Wi-Fi connection, foreground service, reconnect logic and a connection test screen |

## Build and test

Requires JDK 17+.

```
./gradlew :ptpip:test                 # unit + simulated-camera tests
./gradlew :ptpip-cli:installDist      # builds ptpip-cli/build/install/dropfoto-cli/bin/dropfoto-cli
./gradlew :app:testDebugUnitTest      # connection state machine against the simulated camera
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

The Android build needs the Android SDK (compile SDK 37), via `ANDROID_HOME` or `local.properties`.

Try the tool without a camera:

```
dropfoto-cli fake-server                       # terminal 1: simulated D5500 on 127.0.0.1:15740
dropfoto-cli --host 127.0.0.1 list             # terminal 2
dropfoto-cli --host 127.0.0.1 get-all --out downloads
```

`fake-server --dir <folder>` serves your own photos. `--drop-after-mb <n>` cuts the connection
once, to exercise resuming.
