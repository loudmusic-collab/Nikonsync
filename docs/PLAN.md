# NikonSync: Plan

An Android app that connects to a **Nikon D5500** over the camera's built-in Wi-Fi,
shows thumbnails of what's on the memory card, and downloads selected photos to the phone.
It replaces Nikon's Wireless Mobile Utility (WMU), whose connection was unreliable.

## 0. Decisions so far

| Question | Answer | What it means for the build |
|---|---|---|
| Main test phone | Samsung Galaxy S21 Ultra, Android 15 (One UI 7) | Test on this phone first. Handle Samsung Wi-Fi and battery settings explicitly (§2). Follow Android 15's foreground-service rules. |
| Shooting format | RAW + JPEG | The gallery shows each NEF+JPG pair as **one tile**. Downloads grab JPEGs first, then RAWs (§5). |
| Distribution | Personal use first (sideloaded APK), possibly the Play Store later | Build to Play Store rules from day one so publishing later needs no rework (§10). Keep minSdk 29 so more phones can install it. |

---

## 1. How the camera talks

With Wi-Fi turned on, the D5500 acts as its own **access point**. Nikon bodies of this
generation typically use an SSID like `Nikon_WU2_xxxxxxxxxxxx`, and the network can be
open or WPA2 (set on the camera under *Wi-Fi → Network settings*).

- The camera is at **`192.168.1.1`** and gives the phone an address by DHCP.
- The protocol is **PTP/IP** (ISO 15740 over TCP) on port **15740**. It's the same
  Picture Transfer Protocol the camera speaks over USB, carried on two TCP sockets:
  - a **command/data channel** for requests, responses and file bytes
  - an **event channel** where the camera pushes notices like "object added"
- WMU uses standard PTP operations plus a few Nikon vendor extensions.
  Open-source tools already talk to these cameras this way, so we aren't guessing:
  - **airnef** (Python): downloads from Nikon Wi-Fi bodies of this generation
  - **libgphoto2**: `gphoto2 --port ptpip:192.168.1.1`

### PTP operations we need

| Purpose | Operation | Code |
|---|---|---|
| Handshake | PTP/IP Init Command / Init Event | packet types 1–4 |
| Capabilities | `GetDeviceInfo` | 0x1001 |
| Session | `OpenSession` / `CloseSession` | 0x1002 / 0x1003 |
| Cards | `GetStorageIDs`, `GetStorageInfo` | 0x1004 / 0x1005 |
| List files | `GetObjectHandles` | 0x1007 |
| File metadata (name, size, date, format) | `GetObjectInfo` | 0x1008 |
| Small thumbnail (about 160×120 JPEG) | `GetThumb` | 0x100A |
| Chunked, resumable download | `GetPartialObject` | 0x101B |
| *Nikon, optional:* larger preview | `GetLargeThumb` | 0x90C4 |
| *Nikon, optional:* 64-bit partial read | `GetPartialObjectEx` | 0x9431 |
| Keep-alive | PTP/IP Probe Request/Response | packet types 13/14 |

We feature-detect vendor operations from the `GetDeviceInfo` list of supported
operations and never assume they exist.

---

## 2. Why the old app drops the connection, and what we do about each cause

Most "unstable Wi-Fi" complaints come from Android, not the camera. The plan goes after
each known cause:

| Cause | Fix |
|---|---|
| The camera network has **no internet**, so Android flags it and switches back to mobile data or home Wi-Fi, or sends our sockets over mobile data | Connect with **`WifiNetworkSpecifier`** + `ConnectivityManager.requestNetwork()` (Android 10+). This creates a local-only network just for our app, so the phone keeps mobile data for everything else. All camera sockets are opened through `network.socketFactory` (or `bindProcessToNetwork`), so traffic can't leak onto another interface. |
| Wi-Fi **power save** or **Doze** stalls transfers when the screen is off | A **foreground service** of type **`connectedDevice`** runs while connected, plus a `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`) during transfers. We don't use the `dataSync` type because Android 15 limits it to 6 hours per day. |
| **Samsung "Switch to mobile data"** (under *Intelligent Wi-Fi*) and Samsung's warning that the network has no internet | The per-app network request keeps our connection in place regardless. Onboarding also detects Samsung phones and links to the setting in case the user joined the network manually. |
| **Samsung battery management** ("sleeping apps") kills the service | Onboarding asks the user to exempt the app from battery optimization, then deep-links to Samsung's *Never sleeping apps* list |
| The camera's **auto-off timer** kills Wi-Fi when idle | Send a PTP/IP **probe** or cheap PTP call every ~10 s. Onboarding tells the user to raise *Setup → Auto off timers*. |
| **Stale sessions**: the camera accepts one client, and a socket that wasn't closed cleanly blocks reconnecting until it times out | Always send `CloseSession` and close both sockets, even on errors. On reconnect, back off (1 s, 2 s, 4 s…) and show "waiting for camera to release the previous session". |
| A long download fails midway and you start over | Download in **1–4 MB chunks** with `GetPartialObject`, write to a temp file, and **resume from the last byte** after reconnecting |
| The UI freezes while a download runs | PTP runs **one transaction at a time**. A single command queue interleaves thumbnail requests between download chunks, so browsing stays responsive during downloads. |

---

## 3. Tech stack

- **Kotlin**, **Jetpack Compose** (Material 3), **Coroutines + Flow**
- **minSdk 29** (Android 10, required for `WifiNetworkSpecifier`). **targetSdk 36**, the latest,
  which the Play Store requires for new apps. The primary test device runs Android 15 (API 35).
- **Room** for the cached file index and download state
- **Coil** for images, with a custom `Fetcher` that loads thumbnails through PTP
- **MediaStore** for saving to `Pictures/NikonSync/` (scoped storage, no storage permission needed)
- **Hilt** for dependency injection. That's optional; manual DI is fine at this size.
- Permissions, kept minimal so a Play Store review is easy:
  - `NEARBY_WIFI_DEVICES` with `neverForLocation` (Android 13+); `ACCESS_FINE_LOCATION` limited
    to `maxSdkVersion=32` for Android 10–12
  - `CHANGE_WIFI_STATE`, `ACCESS_WIFI_STATE`, `INTERNET`
  - `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`
  - No storage permission. MediaStore covers writing our own files.

---

## 4. Architecture

```
┌─────────────────────────── :app (Android) ───────────────────────────┐
│  UI (Compose)                                                        │
│   ConnectScreen ─ GalleryScreen ─ ViewerScreen ─ DownloadsScreen     │
│          │             │               │               │             │
│          └──────── ViewModels (StateFlow) ─────────────┘             │
│                               │                                      │
│  CameraRepository  ◄──── Room (index cache, download jobs)           │
│          │                                                           │
│  CameraService (foreground)  ── WifiConnector (NetworkSpecifier,     │
│          │                        WifiLock, network callbacks)       │
└──────────┼───────────────────────────────────────────────────────────┘
           │ uses Network.socketFactory
┌──────────▼──────────── :ptpip (pure Kotlin/JVM, no Android) ─────────┐
│  PtpIpTransport   – 2 sockets, packet framing, timeouts, probe       │
│  PtpSession       – transaction IDs, serialized command queue        │
│  PtpCodec         – little-endian datasets (DeviceInfo, ObjectInfo)  │
│  NikonCamera      – high-level API: listFiles(), thumb(), download() │
└──────────────────────────────────────────────────────────────────────┘
```

The protocol library in **`:ptpip`** is plain Kotlin with no Android dependencies. We can
unit-test it on a laptop against a **fake PTP/IP camera server** and drive a real camera
from a small command-line tool, all without deploying to a phone.

### Connection state machine

```
Idle → RequestingNetwork → NetworkAvailable → TcpConnecting → PtpIpHandshake
     → SessionOpen → Ready ⇄ Busy
Any state → (error / onLost) → Reconnecting(backoff) → TcpConnecting …
User disconnect → Closing (CloseSession, close sockets, release network) → Idle
```

The UI observes this as a `StateFlow` and shows the exact stage, so a failure reads like
"camera not answering on 192.168.1.1" instead of a generic "connection failed".

---

## 5. Features, screen by screen

### Connect
- First run: explain how to turn on the camera's Wi-Fi (*Setup menu → Wi-Fi → Network connection → Enable*)
  and suggest a longer auto-off timer
- Scan for `Nikon_WU2_*` networks (or let the user type the SSID and password once).
  Remember the camera, so later connections are one tap.
- Fallback: if the user already joined the camera network in system settings, find that
  network and bind to it
- Show progress for each stage of the state machine

### Gallery (thumbnails)
1. `GetStorageIDs`, then `GetObjectHandles(storage=all, format=any, parent=root/all)`
2. Drop folders (format `0x3001`) and keep images and videos
3. Fetch `GetObjectInfo` **progressively, newest first**, and cache it in Room keyed by
   (camera serial, file name, size, capture date). PTP handles can change between
   sessions; file name + size + date is the stable key.
4. **Group RAW+JPEG pairs.** `DSC_1234.NEF` and `DSC_1234.JPG` in the same folder become one
   shot with a "RAW+JPG" badge. The thumbnail comes from the JPEG. Files without a partner
   are still shown on their own.
5. Lazy grid grouped by date. Thumbnails load through `GetThumb` as cells scroll into view,
   are cancelled when they scroll out, and are cached in memory and on disk.
6. Badges for RAW+JPG, RAW only, JPEG only, video, and downloaded (partly or fully)
6. Filters (JPEG only, RAW only, not yet downloaded) and multi-select, including select-by-date

### Viewer
- Tapping a thumbnail shows a larger preview (`GetLargeThumb` if the camera supports it,
  otherwise the small thumbnail upscaled, with a "download full size" button)
- Basic info: file name, size, date, format

### Download
- A queue of selected files, with progress per file and in total, persisted in Room so
  it survives the app being killed
- Each file downloads in chunks to a temp file, then is published to MediaStore with its
  original file name and capture date (`DATE_TAKEN`)
- Resume after a reconnect. Skip files already downloaded.
- Runs in the foreground service with a progress notification, so it continues with the
  screen off
- **Order for RAW+JPEG:** all JPEGs in a selection download first, then the NEFs. You get
  shareable photos within seconds while the large RAW files (~20–25 MB each on the D5500)
  keep downloading in the background.
- Settings:
  - **What to download:** RAW+JPEG (default), JPEG only, or RAW only. You can also override
    this per selection, for example "just the JPEGs for now".
  - **Where to save:** `Pictures/NikonSync/` by default. JPEG and NEF go in the same folder
    so Lightroom and Snapseed pair them.
- NEF files are saved with MIME type `image/x-nikon-nef`. Phase 4 confirms that MediaStore on
  One UI accepts them in the Images collection; if not, they go to the Downloads collection.

---

## 6. Build phases

### Phase 0: Protocol spike (de-risk first, before any UI)
- Connect a **laptop** to the D5500 network and run `airnef` or `gphoto2 --port ptpip:192.168.1.1 --list-files`
  to confirm listing and downloading work with this exact body and firmware
- Capture the real handshake with Wireshark on the laptop while gphoto2 or airnef connects.
  Save the captures as test fixtures. Capturing the old WMU app on the S21 is optional:
  Android 15 may refuse to install it because it targets an old SDK.
- Record: SSID format, security, `GetDeviceInfo` operation list, whether all card
  images are visible or only "selected for upload" ones, and real transfer speed
- **Exit criteria:** a written protocol notes file plus captures committed to `docs/`

### Phase 1: `:ptpip` library
**Status:** built and passing 23 tests against the simulated camera (`ptpip/src/testFixtures`), including
chunked downloads, resuming after a mid-transfer drop, single-client refusal, events and probes. The
`ptpip-cli` tool is ready for the real-camera run in [phase0-camera-test.md](phase0-camera-test.md).
- Packet framing, init handshake (persistent GUID + friendly name), session,
  the operations listed above, and dataset parsing
- Fake camera server for unit tests. Replay the captured bytes as golden tests.
- A JVM command-line tool (`list`, `thumb`, `get`) run against the real camera from a laptop
- **Exit criteria:** the CLI downloads a full NEF from the camera, including a forced
  mid-transfer disconnect and resume

### Phase 2: Android connection layer
- `WifiConnector` (specifier request, network callbacks, socket factory, WifiLock)
- `CameraService` foreground service + state machine + keep-alive + reconnect
- A minimal debug screen that shows the state and the `GetDeviceInfo` output
- **Exit criteria:** stays connected for 30+ minutes with the screen off; the connection
  survives toggling mobile data and a camera Wi-Fi power cycle (it reconnects automatically)

### Phase 3: Gallery
- Room index, progressive `GetObjectInfo`, Coil fetcher, lazy grid, filters, selection
- **Exit criteria:** a card with 1,000+ files shows its first thumbnails within a few
  seconds and scrolls smoothly

### Phase 4: Downloads
- Persistent queue, chunked resumable downloads, MediaStore publish, dedupe, notification
- **Exit criteria:** 50 RAW+JPEG pairs download on the S21 Ultra with the screen off. All
  JPEGs arrive before any NEF, the queue survives one forced disconnect, and no file is
  corrupt (verified by size and by opening them in Samsung Gallery and Lightroom).

### Phase 5: Polish and hardening
- Error messages in plain language, onboarding, settings, dark theme, and handling for
  "camera battery low" and "card removed"
- Samsung onboarding: exemption from battery optimization, and a hint about *Intelligent Wi-Fi*
- Main device: the S21 Ultra (Android 15). Before the Play Store, also test at least one
  Pixel and one Android 10–12 phone, since phone makers handle Wi-Fi without internet
  differently.

### Later (out of scope for v1)
- Auto-import of new shots while connected (listen for the `ObjectAdded` event)
- Geotagging from phone GPS
- Remote shutter (`InitiateCapture`) and Live View

---

## 7. Repository layout (planned)

```
Nikonsync/
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── ptpip/                     # pure Kotlin/JVM protocol library
│   └── src/{main,test}/kotlin/…/ptpip/
├── ptpip-cli/                 # laptop test tool for the real camera
├── app/                       # Android app
│   └── src/main/kotlin/…/
│       ├── connection/        # WifiConnector, CameraService, state machine
│       ├── data/              # Room, CameraRepository, MediaStore writer
│       └── ui/                # Compose screens + ViewModels
└── docs/
    ├── PLAN.md
    └── protocol-notes.md      # from Phase 0
```

CI: GitHub Actions running `./gradlew ptpip:test app:lint app:assembleDebug` on every push.

---

## 8. Risks and unknowns

| Risk | Mitigation |
|---|---|
| Nikon-specific handshake quirks, such as a GUID or friendly name check, or a pairing step | Phase 0 captures the real WMU handshake; airnef and gphoto2 have already solved this for these bodies |
| The camera may show only images marked "select to send to smart device" | Confirm in Phase 0. If so, document the camera setting and support both modes. |
| Slow radio: realistic throughput is about 1–3 MB/s, so a ~25 MB NEF takes 10–25 s | Honest progress and ETA, a JPEG-only default, and background downloading |
| Phone makers' Wi-Fi quirks, especially Samsung's One UI | Test on the S21 first; keep the manual "join in settings, then bind" fallback |
| Android 15 foreground-service rules | Use only the `connectedDevice` type, start it only while the app is visible (when the user taps Connect), and stop it when disconnecting |
| The approval dialog appears on every `WifiNetworkSpecifier` request | Look into a `CompanionDeviceManager` association, which can pre-approve the request on newer Android versions |

---

## 9. Open questions for the owner

1. ~~Phone and Android version~~ → Samsung S21 Ultra, Android 15
2. ~~RAW, JPEG or both~~ → RAW+JPEG; download both by default, JPEGs first
3. Download folder: `Pictures/NikonSync/` unless you'd prefer another location
4. ~~Personal or Play Store~~ → personal first, Play Store maybe later (see §10)

---

## 10. Staying ready for the Play Store

v1 is a sideloaded APK, but these choices keep publishing later cheap:

- **Signing:** create a release keystore now and keep it safe, outside the repo. Enroll in
  Play App Signing when publishing.
- **Permissions:** only the minimal set in §3. No location on Android 13+ and no storage
  permissions, which means no special Play declarations beyond the foreground-service one.
- **Foreground-service declaration:** Play Console requires a justification for
  `connectedDevice` and a short video of the feature. "Keeps the connection to the camera
  open while transferring photos" fits the policy directly.
- **Data safety form:** the app collects and sends no data. Traffic only goes between the
  phone and the camera. No analytics SDKs, which keeps the form trivial.
- **Privacy policy:** a one-page "we collect nothing" statement hosted on GitHub Pages
- **Name and branding:** "Nikon" is a trademark. The published listing needs a neutral name,
  for example "Sync for D-series cameras", plus a line saying it isn't affiliated with Nikon.
  The internal code name can stay NikonSync.
- **Wider camera support:** because the protocol is standard PTP/IP, other Nikon bodies with
  built-in Wi-Fi (D5300, D7200, D750, …) will probably work too. That's worth listing as
  "tested on" or "may work on" if the app ships.
