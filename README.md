# HomeCam

Turn an old Android phone into a **home monitoring / security camera**. The
phone captures video and serves a live MJPEG stream over your local Wi‑Fi, so
you can watch it from a browser on another phone, tablet, or computer — no
cloud account, no third‑party server, nothing leaves your network.

This is the same idea as apps like *IP Webcam* or *Alfred Camera*, built as a
small, transparent, self‑hostable project.

> **Use it responsibly.** Point it at your own space (a room, a doorway, a pet,
> a garage). Recording other people without their knowledge or consent may be
> illegal where you live. Tell anyone who might be filmed. The app always shows
> a persistent "camera is running" notification so it is never hidden.

---

## Features

- 📹 Live video from the phone's front or back camera (CameraX).
- 🌐 View in any browser on the same Wi‑Fi: `http://<phone-ip>:8080`
  - `/` — simple viewer page
  - `/video` — MJPEG stream (embeddable in Home Assistant, etc.)
  - `/snapshot` — single JPEG
  - `/healthz` — health check
- 🔒 Optional username + password (HTTP Basic auth).
- 🔔 Persistent foreground notification while streaming, with a **Stop** action.
- 🔋 Keeps running with the screen off (wake lock + Wi‑Fi lock).
- ⚙️ Configurable port, camera, and JPEG quality.
- 🚫 No internet upload, no analytics, no accounts. Everything stays on your LAN.

## Requirements

- Android 7.0 (API 24) or newer.
- The viewing device must be on the **same Wi‑Fi / local network**.

## Build & install

You need Android Studio (or the Android SDK command‑line tools). Then:

```bash
# from the project root
./gradlew assembleDebug
# APK is written to app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected phone:

```bash
./gradlew installDebug
# or
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or just open the project in **Android Studio** and press Run.

> The Gradle wrapper is included, so you don't need a local Gradle install — but
> you do need the Android SDK. Android Studio sets `sdk.dir` in `local.properties`
> automatically; from the CLI, set `ANDROID_HOME` / `ANDROID_SDK_ROOT` or create
> `local.properties` with `sdk.dir=/path/to/Android/sdk`.

## How to use

1. Install and open **HomeCam** on the phone that will be the camera.
2. (Optional) set a username and password, and choose the port / camera.
3. Tap **Start camera** and grant the camera + notification permissions.
4. The screen shows the address, e.g. `http://192.168.1.20:8080`.
5. On another device on the same Wi‑Fi, open that address in a browser.
6. Tap **Stop camera** (or the notification's **Stop**) when done.

### Tips

- Keep the phone **plugged into power** — streaming and the camera drain the
  battery quickly.
- For 24/7 use, disable battery optimization for HomeCam so Android doesn't kill
  the service.
- Mount the phone where you want to watch (a cheap phone holder works well).
- To reach it from outside your home, don't expose the port directly — use a VPN
  back into your home network (e.g. WireGuard / Tailscale). Exposing an
  unauthenticated camera to the public internet is a bad idea.

## How it works

```
CameraX ImageAnalysis (YUV_420_888)
        │  (background thread, ~15 fps)
        ▼
YuvToJpeg  ──►  FrameBus (latest frame)  ──►  MjpegServer  ──►  browser / viewer
                                          └►  on‑screen preview (ImageView)
```

- `CameraService` is a foreground service that owns the camera and the server.
- `MjpegServer` is a tiny raw‑socket HTTP server (no networking dependency).
- `FrameBus` hands the newest JPEG to every connected viewer.

## Project layout

```
app/src/main/java/com/damir/homecam/
├── MainActivity.kt    # UI: start/stop, settings, local preview
├── CameraService.kt   # foreground service, CameraX, notification, locks
├── MjpegServer.kt      # embedded HTTP/MJPEG server + Basic auth
├── FrameBus.kt        # latest-frame hand-off between threads
├── YuvToJpeg.kt       # YUV_420_888 -> JPEG (+ rotation)
├── NetworkUtils.kt    # local IP discovery
└── Settings.kt        # SharedPreferences-backed settings
```

## Privacy

HomeCam makes **no outbound internet connections**. It only listens on the LAN
port you choose and serves frames to clients that connect to it. There is no
telemetry and no cloud component. The only data stored is your settings
(port, camera choice, quality, and the optional credentials you set), in the
app's private SharedPreferences.

## License

MIT — see `LICENSE`.
