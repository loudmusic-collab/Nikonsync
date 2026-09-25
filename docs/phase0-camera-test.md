# Phase 0: Testing with the real D5500

This checks that our protocol code works with your actual camera before any Android work starts.
You run a small command-line tool on a laptop joined to the camera's Wi-Fi. It does the same
things the app will: connect, list the card, fetch thumbnails, download, and stay connected.

Allow about 45 minutes. Most of that is the 30-minute stability test, which runs unattended.

---

## 1. One-time setup (do this while the laptop still has internet)

1. Install **Java 17 or newer**: <https://adoptium.net> (Temurin, default options).
2. Get the code:
   ```
   git clone -b claude/nikon-d5500-android-app-5hgm37 https://github.com/loudmusic-collab/Nikonsync.git
   cd Nikonsync
   ```
3. Build the tool (the first run downloads build tools, so it needs internet):
   - macOS / Linux: `./gradlew :ptpip-cli:installDist`
   - Windows: `gradlew.bat :ptpip-cli:installDist`
4. Optional: check it works against the built-in simulated camera. In one terminal run
   `nikonsync-cli fake-server`, in another run `nikonsync-cli --host 127.0.0.1 list`.

The tool is at `ptpip-cli/build/install/nikonsync-cli/bin/`. Run it from that folder, or use its
full path. On Windows it's `nikonsync-cli.bat`. Commands below just say `nikonsync-cli`.

## 2. Prepare the camera

1. Charge the battery and insert the card you normally use, with a mix of RAW+JPEG shots.
2. **Setup menu → Auto off timers → Custom → Standby timer: 30 min**, so the camera doesn't
   sleep during the test.
3. Make sure no phone is connected to the camera. It only accepts one device at a time, so
   close the Nikon app or turn off the phone's Wi-Fi.
4. **Setup menu → Wi-Fi → Network connection → Enable.**
5. Under **Wi-Fi → Network settings**, note the network name (SSID) and whether a password is set.

## 3. Join the camera's network

Connect the laptop's Wi-Fi to the camera network, usually named `Nikon_WU2_…`.
The laptop will warn that there's no internet. That's expected.

## 4. Run the tests

Run these in order. `--trace` records every packet so I can diagnose anything that goes wrong.

| # | Command | What it checks |
|---|---|---|
| 1 | `nikonsync-cli --trace trace-info.log info > info.txt` | Handshake, camera model and firmware, supported features |
| 2 | `nikonsync-cli --trace trace-list.log list > list.txt` | Listing the whole card. Note how long it takes. |
| 3 | `nikonsync-cli thumb 0x…` (a handle from `list.txt`) | Thumbnails. Open the saved `_thumb.jpg`. |
| 4 | `nikonsync-cli --trace trace-get.log get DSC_1234.NEF DSC_1234.JPG --out test-dl` | Downloading a RAW+JPEG pair. Note the MB/s. |
| 5 | Start `nikonsync-cli get DSC_1235.NEF --out test-dl`, press **Ctrl+C** halfway, then run the same command again | Resuming an interrupted download. It should continue from where it stopped, not restart. |
| 6 | `nikonsync-cli --trace trace-soak.log soak --minutes 30 > soak.txt` | Stability. Leave everything alone for 30 minutes. |

Use real file names from `list.txt` in steps 4 and 5.

**Optional extra for test 6:** part way through, turn the camera's Wi-Fi off and on again, or
switch the camera off and on. The log should show `DROPPED` and then reconnect on its own.

## 5. Things to note while you test

- Does the camera show any message or prompt when the tool connects?
- Does `list.txt` include **every** photo on the card, or only some?
- Does the camera's Wi-Fi turn itself off at any point during the soak test?
- The download speed shown in test 4

## 6. Send back the results

Commit the output files into `docs/phase0/`, or attach them in a message:
`info.txt`, `list.txt`, `soak.txt`, the `trace-*.log` files, and any error messages.

The trace files contain file names and the camera's serial number, nothing else.

## If something fails

- **"Couldn't connect … timed out / refused":** check the laptop is on the camera's network and
  its IP address starts with `192.168.1.`. Check the camera's Wi-Fi is still on (the standby
  timer), then try again.
- **"Camera refused the connection":** another device is connected. Disconnect it, wait
  30 seconds and retry.
- **Anything else:** send the trace file. It shows exactly where the conversation with the
  camera stopped.
