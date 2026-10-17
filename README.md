# HomeCam

Turn an old Android phone into a **home monitoring / security camera**, and
watch it from a second phone with a companion viewer app. The camera phone
captures video (and optional audio) and serves it over your local Wi‑Fi — no
cloud account, no third‑party server, nothing leaves your network.

This repository contains **two apps**:

| Module | App | Role |
|--------|-----|------|
| `:app` | **HomeCam** | Runs on the phone that acts as the camera. |
| `:viewer` | **HomeCam Viewer** | Runs on the phone you watch from. |

You don't strictly need the viewer — any web browser on the same network works
too — but the viewer app adds a saved camera list, a full‑screen view, and
live audio playback.

> **Use it responsibly.** Point it at your own space (a room, a doorway, a pet,
> a garage). Recording other people without their knowledge or consent may be
> illegal where you live. Tell anyone who might be filmed. The camera app always
> shows a persistent "camera is running" notification so it is never hidden.

---

## Features

### Camera app (`:app`)
- 📹 Live video from the phone's front or back camera (CameraX).
- 🏃 **Motion detection** with a push notification when movement is seen
  (adjustable sensitivity, 30 s notification cooldown).
- 💾 **Recording to the phone**: *off*, *on motion* (a clip while something
  moves, plus a trailing buffer), or *continuous* (back‑to‑back segments).
  Clips are saved as MP4 in the app's own storage and listed at `/recordings`.
- 🔊 **Microphone audio** exposed on the live stream (`/audio.pcm`).
- 🔒 Optional username + password (HTTP Basic auth) on every endpoint.
- 🔔 Persistent foreground notification with a **Stop** action.
- 🔋 Keeps running with the screen off (wake lock + Wi‑Fi lock).
- 🚫 No internet upload, no analytics, no accounts.

### Viewer app (`:viewer`)
- 📝 Save multiple cameras (name, address, optional credentials).
- 🖥️ Full‑screen live view; screen stays awake while watching.
- 🔊 One‑tap live audio.
- 📂 Opens the camera's recordings list in a browser.

### HTTP endpoints (served by the camera)
| Path | What |
|------|------|
| `/` | HTML viewer page |
| `/video` | MJPEG stream (`multipart/x-mixed-replace`) |
| `/snapshot` | single current JPEG |
| `/audio.pcm` | raw 16 kHz mono s16le PCM (used by the viewer app) |
| `/recordings` | HTML index of recorded clips |
| `/recordings/<file>` | download a clip |
| `/healthz` | health check |

## Requirements

- Android 7.0 (API 24) or newer, on both phones.
- Both devices on the **same Wi‑Fi / local network**.

## Build & install

You need Android Studio (or the Android SDK command‑line tools).

```bash
# camera app
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug

# viewer app
./gradlew :viewer:assembleDebug     # viewer/build/outputs/apk/debug/viewer-debug.apk
./gradlew :viewer:installDebug
```

Or open the project in **Android Studio**, pick the `app` or `viewer` run
configuration, and press Run.

> The Gradle wrapper is included, so you don't need a local Gradle install — but
> you do need the Android SDK. Android Studio sets `sdk.dir` in `local.properties`
> automatically; from the CLI, set `ANDROID_HOME` / `ANDROID_SDK_ROOT` or create
> `local.properties` with `sdk.dir=/path/to/Android/sdk`.

## How to use

**On the camera phone (HomeCam):**
1. Open the app, choose camera / port, optionally set a username + password.
2. Turn on *Stream microphone audio*, *Detect motion*, and a *Recording* mode
   if you want them.
3. Tap **Start camera** and grant the camera (and microphone / notification)
   permissions. The screen shows the address, e.g. `http://192.168.1.20:8080`.
   If the phone is connected to Tailscale, it also shows the Tailscale address
   (e.g. `http://100.101.102.103:8080`) for watching from anywhere.

**On the viewing phone (HomeCam Viewer):**
1. Tap **Add camera**, enter the address shown by the camera phone (and the
   same credentials, if you set any).
2. Tap the camera to watch it full‑screen; tap **Audio** to listen.

Or, from any browser on the same Wi‑Fi, just open the address.

### Tips
- Keep the camera phone **plugged into power** — streaming drains the battery.
- For 24/7 use, see [Always on](#always-on-247) below.
- To reach it from outside your home, don't expose the port to the internet —
  use a VPN into your home network (e.g. WireGuard / Tailscale).

## Always on (24/7)

Turn on **Always on** in the camera app. Then HomeCam:

- starts the camera again by itself after a reboot (e.g. after a power cut)
  and after an app update;
- asks Android to exempt it from battery optimization (the status line under
  the switch shows whether that is done — tap it to fix);
- if Android still blocks an automatic start, shows a **"HomeCam did not
  start"** notification — tap it to start the camera.

For a phone that stays on as a camera, also:

1. **Screen lock: None or Swipe.** With a PIN/pattern, Android does not run
   apps after a reboot until someone unlocks the phone. (The trade‑off: anyone
   holding the phone can use it.)
2. **Tailscale Always‑on VPN** (for viewing from outside): tap *Open VPN
   settings* in HomeCam → the gear next to Tailscale → turn on *Always‑on VPN*.
   Only you can switch this on; apps are not allowed to.
3. **Manufacturer battery savers**: some phones stop background apps on their
   own (Xiaomi *Autostart*, Samsung *Never sleeping apps*, Huawei *App launch*).
   Allow HomeCam (and Tailscale) there — see <https://dontkillmyapp.com>.
4. **Battery health**: if the phone has a charging limit (e.g. Samsung
   *Protect battery*, 85 %), turn it on — a phone charging 24/7 ages faster.

The screen can stay **off**: the camera keeps running in the background.

Android limits: after an automatic start, **audio stays off until the app is
opened once** on the camera phone (Android does not let apps start the
microphone in the background). Video, motion detection and recording work right
away. Keep `targetSdk` at 34 — from 35 on, Android no longer lets the camera
start at boot.

## Home Assistant integration

The MJPEG stream works directly with Home Assistant's built‑in
[`mjpeg` camera](https://www.home-assistant.io/integrations/mjpeg/). Add to
`configuration.yaml`:

```yaml
camera:
  - platform: mjpeg
    name: HomeCam Hallway
    mjpeg_url: http://192.168.1.20:8080/video
    still_image_url: http://192.168.1.20:8080/snapshot
    # only if you set credentials in the app:
    username: youruser
    password: yourpass
    authentication: basic
```

That gives you the live view, snapshots, and HA's own motion/recording
automations on top of the feed.

### What about RTSP?

HomeCam does **not** implement a native RTSP server. A correct RTSP/RTP + H.264
server is a large, hardware‑specific piece of work, and MJPEG already covers the
common integrations (Home Assistant, browsers, `ffmpeg`, most NVR software that
accepts an MJPEG URL). If you specifically need RTSP later, the cleanest path is
to add a maintained streaming library (e.g. pedroSG94's *RootEncoder*) as a
separate capture pipeline — this is noted as a possible future enhancement.

## How it works

```
CameraX ImageAnalysis (YUV_420_888)          CameraX VideoCapture
   │  ~15 fps, background thread                 │  (bound only when recording)
   ├─► MotionDetector (luma diff) ─► notification / trigger recording
   ├─► YuvToJpeg ─► FrameBus ─► MjpegServer ─► /video, /snapshot, browser, viewer
   └─────────────────────────────┘             └─► MP4 clips ─► /recordings

AudioRecord (mic) ─► AudioBus ─► MjpegServer ─► /audio.pcm ─► viewer AudioTrack
```

## Project layout

```
app/    (camera)                         viewer/
  MainActivity.kt   UI + preview            MainActivity.kt    camera list
  CameraService.kt  camera/stream/record    AddCameraActivity  add / edit
  MjpegServer.kt    HTTP + endpoints        ViewerActivity     full-screen view
  MotionDetector.kt luma frame differencing MjpegReader.kt     JPEG frame parser
  ClipRecorder.kt   MP4 clip recording      AudioPlayer.kt     PCM playback
  AudioStreamer.kt  mic capture             CameraStore.kt     saved cameras
  AudioBus.kt       PCM fan-out
  YuvToJpeg.kt      YUV -> JPEG
  FrameBus.kt       latest-frame hand-off
  NetworkUtils.kt   local IP
  Settings.kt       preferences
```

## Privacy

HomeCam makes **no outbound internet connections**. It only listens on the LAN
port you choose and serves to clients that connect to it. There is no telemetry
and no cloud component. Recordings stay in the camera app's private storage on
the phone. The only settings stored are your preferences and the optional
credentials you set.

## Notes & limitations

- Recorded clips are **video only** — the microphone is reserved for the live
  audio stream to avoid capture contention. (Audio‑in‑clips can be added later.)
- Recording needs the device to support the camera + record stream combination;
  on devices that don't, the app automatically keeps the live stream and
  disables recording.
- Audio in the browser depends on the browser; the viewer app plays it reliably.

## License

MIT — see `LICENSE`.
