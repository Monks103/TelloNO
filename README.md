# TelloNO

**Open-source Tello drone FPV controller for Android — built by MusanaINC**

TelloNO is a clean, dark-themed FPV controller for the Ryze Tello drone. Built from scratch using Android MediaCodec for low-latency H.264 video — no GStreamer, no closed SDKs. Built and tested on GrapheneOS, Pixel 8a, Android 16.

---

## Features

- **Full-screen FPV video** — H.264 via Android MediaCodec, auto-starts on connect
- **Dark transparent UI** — overlays stay out of the way of the feed
- **Dual joystick touch controls** — cyan glow, -100 to 100 output, 20Hz RC loop
- **Bluetooth gamepad support** — 8BitDo and standard XInput controllers
- **Photo capture** — saves to gallery (Pictures/TelloNO)
- **MP4 video recording** — proper .mp4 via MediaMuxer, shareable anywhere
- **Speed modes** — LOW / MED / HIGH (30 / 60 / 100)
- **Safety arm** — Start button arms before takeoff, never kills props mid-flight
- **Low battery warning** — vibration + HUD alert at ≤20%
- **Flip buttons** — on-screen (↑F ←L →R ↓B) and gamepad (Y/X/B)
- **Immersive mode** — system bars hidden, screen always on while flying

---

## Gamepad Mapping

| Button | Action |
|--------|--------|
| Start | Arm (enable SDK mode) |
| L2 | Takeoff |
| L1 | Land |
| R1 | Toggle recording |
| R2 | Photo |
| Select | Emergency stop |
| A | Toggle video |
| Y / X / B | Flip forward / left / right |
| D-pad Up/Down | Speed up / down |
| Left stick | Throttle + yaw |
| Right stick | Roll + pitch |

---

## Requirements

- Tested on GrapheneOS, Pixel 8a, Android 16 (min API 26; untested on other devices/OS)
- Ryze Tello drone
- Connect phone to Tello WiFi before launching

> **GrapheneOS users:** grant Network permission in  
> Settings → Apps → TelloNO → Permissions → Local network

---

## Build

```bash
git clone https://github.com/monks103/TelloNO.git
cd TelloNO
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK 34 and Java 17+.

---

## Laptop Companion

Fly from your laptop with a gamepad using the included Python companion:

```bash
cd companion
pip install -r requirements.txt
# connect to Tello WiFi, then:
python3 tello_companion.py
```

Same gamepad mapping as the app. Photos and recordings saved locally.

---

## Technical Details

- **Video:** UDP port 11111, Annex B H.264, SPS/PPS hardcoded for Tello 960×720
- **Commands:** UDP port 8889 → 192.168.10.1 (Tello text SDK)
- **Telemetry:** UDP port 8890 (battery, altitude)
- **No native libraries** — pure Java + Android SDK

---

## Credits

Originally inspired by [duvitech-llc/Tello-SDK-Android](https://github.com/duvitech-llc/Tello-SDK-Android).  
Rewritten with MediaCodec video, new UI, and all features above by MusanaINC.

---

## License

MIT — see [LICENSE](LICENSE)  
© 2026 MusanaINC
