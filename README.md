# NikonSync

An Android app that connects to a Nikon D5500 over the camera's built-in Wi-Fi, shows
thumbnails of the card, and downloads RAW+JPEG files to the phone. It's a reliable
replacement for Nikon's discontinued Wireless Mobile Utility.

**Status:** Phase 1. The protocol library and a laptop test tool are working against a
simulated camera; testing on the real camera is next. There's no Android app yet.

- [Plan](docs/PLAN.md): approach, architecture and phases
- [Phase 0 camera test](docs/phase0-camera-test.md): how to check the real D5500 with the laptop tool

## Layout

| Module | What it is |
|---|---|
| `ptpip/` | PTP/IP protocol library in pure Kotlin (no Android dependencies), plus a simulated camera for tests (`src/testFixtures`) |
| `ptpip-cli/` | Laptop tool: `info`, `list`, `thumb`, `get`, `get-all`, `soak`, `fake-server` |
| `app/` | Android app (Phase 2, not started) |

## Build and test

Requires JDK 17+.

```
./gradlew :ptpip:test                 # unit + simulated-camera tests
./gradlew :ptpip-cli:installDist      # builds ptpip-cli/build/install/nikonsync-cli/bin/nikonsync-cli
```

Try the tool without a camera:

```
nikonsync-cli fake-server                       # terminal 1: simulated D5500 on 127.0.0.1:15740
nikonsync-cli --host 127.0.0.1 list             # terminal 2
nikonsync-cli --host 127.0.0.1 get-all --out downloads
```

`fake-server --dir <folder>` serves your own photos. `--drop-after-mb <n>` cuts the connection
once, to exercise resuming.
