# Gallery and download test (v0.2.0)

Install `DropFoto-0.2.0-debug.apk` over the previous version; pairing and settings are kept.

## Gallery

1. Open DropFoto and tap **Connect** (the gear icon has the connection settings and log).
2. The first time, the card is read newest-first, with a progress bar showing *Reading the card… N of 671*.
   Thumbnails appear as you scroll, and you can browse while it reads.
3. Check:
   - RAW+JPEG pairs show as **one tile** marked `RAW+JPG`.
   - **Portrait shots are upright.**
   - Days have headers, with a **Select** button for the whole day.
4. Disconnect and reconnect. The grid should be back almost at once, without reading all 671 files
   again. The log (under the gear icon) says how many came from memory.
5. While connected, take a photo. It should appear at the top within a few seconds.
6. Tap a photo to see a larger preview and its files.

## Downloads

1. Long-press a photo to start selecting, then tap more photos or use a day's **Select**.
2. At the bottom, pick **JPEG**, **RAW** or **RAW + JPEG**. The button shows how many files and MB.
   Your choice is remembered for next time.
3. Tap **Download**. The panel shows progress, MB/s and time left; so does the notification.
4. Check the photos in Samsung Gallery under **Pictures → DropFoto**. RAW files are there too (or
   in **Download/DropFoto** if the phone refused them, which the log would mention).
5. Tiles show `✓` once everything is on the phone, `JPG ✓` when only the JPEG is, and `↓` while queued.
   Use the **Not downloaded** filter to see what's left.
6. Add the RAW later: select a shot that shows `JPG ✓`, choose **RAW**, then **Download**.
7. **Interrupt test:** start a RAW download and turn the camera's Wi-Fi off and on within a few seconds.
   It should say *Waiting for the camera to reconnect…* and then continue where it stopped.

## Please note down

- The **MB/s** shown while downloading JPEGs and RAWs. This is our first real speed figure.
- How long the first card read takes, and the second.
- Anything that looks wrong. Screenshots help.
