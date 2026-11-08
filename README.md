# Klipper Cam Widget

An open-source Android app and home-screen widget that shows **live camera streams from Klipper
printers**. Add as many printers as you like by IP address. Each widget shows up to 9 cameras from
any mix of printers, and the widgets update live while their streams are connected.

It was built for the **Snapmaker U1 running the [paxx12 Extended Firmware](https://github.com/paxx12-snapmaker-u1/SnapmakerU1-Extended-Firmware)**,
and it works with any Klipper + Moonraker setup (Mainsail, Fluidd, crowsnest, camera-streamer, go2rtc, MediaMTX…).

## Download

Get the latest APK from the [Releases page](https://github.com/abdukir/KlipperCamWidget/releases/latest):

- **`app-arm64-v8a-release.apk`**: almost every phone from the last ~8 years. Pick this one if unsure.
- `app-armeabi-v7a-release.apk`: older 32-bit phones.
- `app-x86_64-release.apk`: emulators and Chromebooks.
- `app-universal-release.apk`: works everywhere, but it's larger.

Android asks you to allow installing apps from your browser or file manager the first time. Newer
releases install as updates and keep your printers and widgets. Requires Android 8.0 or newer.

## Features

- **Multiple printers.** Each printer is set up from its IP or hostname. Moonraker API keys and logins (`force_logins`) are supported.
- **Automatic camera discovery** via Moonraker's `/server/webcams/list`, the same list Mainsail and Fluidd use.
- **Every Moonraker camera service**, plus RTSP:

  | Moonraker `service`        | How the app plays it                                   |
  |----------------------------|--------------------------------------------------------|
  | `webrtc-camerastreamer`    | WebRTC, camera-streamer protocol (**paxx12 default**)  |
  | `webrtc-go2rtc`            | WebRTC over go2rtc's WebSocket API                     |
  | `webrtc-mediamtx`          | WebRTC via WHEP                                        |
  | `webrtc-janus`             | WebRTC via the Janus streaming plugin (WebSocket)      |
  | `mjpegstreamer`, `uv4l-mjpeg` | MJPEG multipart stream                             |
  | `mjpegstreamer-adaptive`   | Snapshot polling                                       |
  | `jmuxer-stream`            | Raw H.264 over WebSocket, hardware decoded             |
  | `hlsstream`                | HLS (ExoPlayer)                                        |
  | `ipstream`                 | Any video URL ExoPlayer can play                       |
  | `iframe`                   | paxx12 `/webcam/player` → raw H.264; otherwise snapshots |
  | *(not in Moonraker)*       | **RTSP** (`rtsp://<ip>:8554/stream`, paxx12)           |
  | paxx12 `[webcam gui]` / `/screen/` | **Printer touchscreen**: mirrored and touch-controllable |

- **Live widgets.** Tiles arrange themselves to fit the widget's size. Each tile shows a LIVE /
  SNAPSHOT / OFFLINE badge, and a ▶/⏸ button toggles live mode.
- **Resilient.** Connections reconnect automatically. If a live stream can't connect, the app shows
  the camera's snapshot URL instead and keeps retrying the live stream.
- **Battery-aware.** Streaming happens only while the screen is on and unlocked. When the screen
  turns off, every connection is closed.
- **Full-screen viewer.** Tap a tile to open it. You can swipe between cameras, pinch to zoom, and
  double-tap to reset the zoom.
- **Printer touchscreen (paxx12 Remote Screen).** The U1's display can be shown in widgets like a
  camera. In the full-screen viewer you can tap, long-press and drag on it to control the printer.
- **Rotation and flips** come from the Moonraker webcam config, and you can change them per camera.

## Snapmaker U1 (paxx12 firmware)

The paxx12 firmware serves these endpoints for the internal camera (`/webcam/`). The USB camera
uses the same paths under `/webcam2/`:

| Endpoint                         | Type                                  |
|----------------------------------|---------------------------------------|
| `POST /webcam/webrtc`            | WebRTC signaling (camera-streamer compatible) |
| `/webcam/stream.mjpg`            | MJPEG                                 |
| `/webcam/stream.h264`            | Raw Annex-B H.264                     |
| `/webcam/snapshot.jpg`           | JPEG snapshot                         |
| `rtsp://<ip>:8554/stream`        | RTSP (internal), `:8555` for USB; enable it in firmware-config → Camera |
| `GET /screen/snapshot`           | Touchscreen framebuffer (PNG, ETag/304); enable in firmware-config → Web → Remote Screen |
| `POST /screen/touch?a=down\|move\|up&x=&y=` | Touch input in framebuffer pixels |

Setup:

1. Install the app, tap **Add printer**, and enter the U1's IP address.
2. Tap **Find cameras**. The app reads the cameras from Moonraker and checks for the RTSP ports
   and the remote screen.
   If Moonraker can't be reached, tap **U1 paxx12 preset** to add the default endpoints.
3. Long-press your home screen → **Widgets** → **Klipper Cam**, then pick the cameras.

### Controlling the printer's touchscreen

Turn on **Remote Screen** in the paxx12 firmware-config (Web section). **Find cameras** then adds a
**Touchscreen** entry. You can add it to a widget like any camera, but widget tiles can't receive
touches, so tap the tile to open the full-screen viewer and control the screen from there. Swiping
is forwarded to the printer as a drag, so use the ‹ › buttons to switch between cameras on that
page. The app downloads a new image only when the screen changes, so a mirrored screen costs almost
nothing while it's idle. If the printer requires a login (`force_logins`), set the API key or
username/password on the printer.

> The paxx12 WebRTC server accepts at most **4 simultaneous viewers per camera**. The app opens only
> one connection per camera, shared by all widgets and the viewer, but browser tabs running
> Mainsail/Fluidd count toward the limit too.

## How live widgets work

Android app widgets can't host video views: they can only show views that the launcher draws from
`RemoteViews`. So the app decodes the streams itself and pushes frames into the widget as bitmaps:

```
             ┌──────────────── StreamHub (one connection per camera, shared) ────────────────┐
WebRTC  ───► │ libwebrtc (HW H.264) ─► VideoSink ─► I420 scale ─► Bitmap                     │
MJPEG   ───► │ multipart parser ─► JPEG decode (subsampled)                                  │
Screen  ───► │ PNG poll with ETag (304 = unchanged); touch → POST /screen/touch              │──► Widgets (LiveWidgetService,
H.264   ───► │ Annex-B parser ─► MediaCodec ─► SurfaceTexture ─► GL readback                 │      partial RemoteViews updates)
RTSP/HLS ──► │ ExoPlayer ─► SurfaceTexture ─► GL readback                                    │──► Full-screen viewer (Compose)
Snapshot ──► │ HTTP poll ─► JPEG decode                                                      │
             └───────────────────────────────────────────────────────────────────────────────┘
```

- `LiveWidgetService` is a foreground service with a silent, minimum-priority notification. It
  runs at 2 fps by default (adjustable from 1 to 15 fps in Settings).
- Each widget update is limited to about 320k pixels, which keeps binder transactions well under
  Android's limits.
- Android 12+ doesn't let apps start foreground services from the background. If live mode can't
  start on its own (for example after a reboot), tap ▶ on the widget or open the app. While live
  mode is off, widgets refresh a snapshot every 30 minutes.

## Building

Requirements: JDK 17+ and the Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # parser, URL and Moonraker client tests
./gradlew assembleRelease        # per-ABI + universal APKs
```

GitHub Actions builds, tests and lints every push. Pushing a `v*` tag publishes the APKs as a
GitHub release. To sign releases with your own key, add these repository secrets:
`KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
Without them, release APKs are signed with the CI runner's throwaway debug key, so they can't be
installed as updates over each other.

### Project layout

```
app/src/main/java/io/github/abdukir/klippercam/
├── data/      models (printers, cameras, widgets, settings), JSON persistence, URL resolution
├── net/       OkHttp clients, Moonraker API (discovery, auth, RTSP probing)
├── stream/    StreamHub + sources: WebRTC (camera-streamer, go2rtc, WHEP, Janus), MJPEG,
│              snapshot, raw H.264 (MediaCodec), ExoPlayer (RTSP/HLS), GL frame grabber
├── widget/    AppWidgetProvider, RemoteViews renderer, configuration activity
├── service/   live widget foreground service, boot receiver
└── ui/        Compose screens: printers, cameras, settings, full-screen viewer
```

## Contributing

Issues and pull requests are welcome, especially reports of camera setups that don't work. Please
include the `service`, `stream_url` and `snapshot_url` from Moonraker (`/server/webcams/list`).

## License

[GPL-3.0](LICENSE), the same license as Klipper, Moonraker and the paxx12 firmware. This project is
not affiliated with Snapmaker, Klipper, or the paxx12 firmware project.
