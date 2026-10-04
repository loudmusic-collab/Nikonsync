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
3. **Pair once:** with the camera's Wi-Fi on, tap **Pair camera**. Android lists nearby
   `Nikon_WU2_…` networks; tap yours and confirm. The card then says *Paired with Nikon_WU2_…*.
   This is what lets DropFoto reconnect later without Android asking you to approve the network.
4. Tap **Connect** and allow the permissions it asks for (nearby devices and notifications).
5. Android may show a "connect to device" dialog listing the camera network. Select it and
   tap **Connect**.
6. The status card should say **Connected to Nikon Corporation D5500** within a few seconds.
7. Tap **List files**. Check the file count and the number of RAW+JPEG pairs look right.
8. **Stability:** turn the screen off and leave the phone for 30 minutes. The notification shows
   "Link checked N times". Afterwards, check that the count kept rising and look in the log for
   any `lost` / `Retrying` lines.
9. **Recovery:** while connected, turn the **camera's** Wi-Fi off, wait 10 seconds, and turn it
   back on. The app shows *Waiting for the camera's Wi-Fi…* and then *Connected* again, without
   any pop-up. It keeps waiting for up to 5 minutes. (Turning off the **phone's** Wi-Fi is a
   different test: the app says the phone's Wi-Fi is off and waits for it.)

Also worth trying: toggle mobile data off and on while connected. Nothing should change.

**Battery settings** opens Android's battery-optimization list. If the link drops only with the
screen off, set DropFoto to *Unrestricted* there, then repeat test 8.

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
