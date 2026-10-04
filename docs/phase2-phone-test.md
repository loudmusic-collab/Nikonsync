# Phase 2: Testing the connection on the phone

The current DropFoto build is a **connection test**. It joins the camera's Wi-Fi, keeps the link
alive with the screen off, reconnects when it drops, and can list the card. Photo thumbnails and
downloads come in Phases 3 and 4.

## Install

1. Get `app-debug.apk`: either the file sent in chat, or from the **dropfoto-debug-apk** artifact
   of the latest GitHub Actions run on this branch.
2. Open it on the S21. Android asks to allow installs from that app (Chrome, Files, …). Allow it,
   then tap **Install**.
3. If Play Protect warns about an unknown developer, choose **Install anyway**. This build is
   signed with a debug key, not a release key.

## Test with the camera

1. On the D5500: **Setup → Auto off timers → Custom → Standby timer: 30 min**, then
   **Setup → Wi-Fi → Network connection → Enable**.
2. Open DropFoto and keep **Join camera Wi-Fi** selected. Leave the network name blank so it
   matches any `Nikon_WU2_…` network, or type the exact name. Enter the password only if
   you set one on the camera.
3. Tap **Connect** and allow the permissions it asks for (nearby devices and notifications).
4. Android shows a "connect to device" dialog listing the camera network. Select it and
   tap **Connect**.
5. The status card should say **Connected to Nikon Corporation D5500** within a few seconds.
6. Tap **List files**. Check the file count and the number of RAW+JPEG pairs look right.
7. **Stability:** turn the screen off and leave the phone for 30 minutes. The notification shows
   "Link checked N times". Afterwards, check that the count kept rising and look in the log for
   any `lost` / `Retrying` lines.
8. **Recovery:** while connected, turn the camera's Wi-Fi off, wait 10 seconds, and turn it
   back on. The app should show *Reconnecting* and then *Connected* again by itself.
   It gives up after 8 failed attempts.

Also worth trying: toggle mobile data off and on while connected. Nothing should change.

**Battery settings** opens Android's battery-optimization list. If the link drops only with the
screen off, set DropFoto to *Unrestricted* there, then repeat test 7.

## Without the camera

Run the simulated camera on a laptop on the same home Wi-Fi as the phone:

```
dropfoto-cli fake-server --bind 0.0.0.0
```

In DropFoto choose **Current Wi-Fi**, enter the laptop's IP address (for example
`192.168.0.23`) and port `15740`, then **Connect**.

## What to send back

Take screenshots of the status card and the log, especially any lines with *lost*,
*Retrying*, *Stopped* or *doesn't answer probes*.
