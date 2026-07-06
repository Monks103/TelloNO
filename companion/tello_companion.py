#!/usr/bin/env python3
"""
TelloNO companion — fly Tello from laptop with an 8BitDo (or any pygame joystick).

Usage:
    pip install djitellopy pygame opencv-python
    Connect laptop to Tello WiFi, then:
    python3 tello_companion.py

    If your gamepad's buttons don't match the actions below, run:
    python3 tello_companion.py --discover
    to print raw button/axis indices as you press things, then add a
    profile for it in CONTROLLER_PROFILES.

Gamepad mapping (matches TelloNO Android app):
    Left stick     → throttle (Y) + yaw (X)
    Right stick    → roll (X) + pitch (Y)
    L2 trigger     → takeoff
    L1 bumper      → land
    R1 bumper      → toggle recording (.mp4 saved to ./recordings/)
    R2 trigger     → photo (screenshot saved to ./photos/)
    Start          → arm  (sends 'command' to enter SDK mode)
    Select         → emergency stop
    A              → toggle live video display window
    Y / X / B      → flip forward / left / right
"""

import sys
import os
import time
import threading
from datetime import datetime

try:
    import pygame
except ImportError:
    sys.exit("Install pygame:  pip install pygame")

try:
    from djitellopy import Tello
except ImportError:
    sys.exit("Install djitellopy:  pip install djitellopy")

try:
    import cv2
except ImportError:
    sys.exit("Install opencv-python:  pip install opencv-python")

# ── constants ────────────────────────────────────────────────────────────────

DEADZONE       = 0.12    # ignore stick/trigger-axis values below this
TRIGGER_THRESH = 0.5     # axis value that counts as "trigger pulled" for axis-based L2/R2
RC_HZ          = 20      # RC command rate
SPEED_STEPS    = [30, 60, 100]
SPEED_LABELS   = ["LOW", "MED", "HIGH"]

PHOTOS_DIR     = "photos"
RECORDINGS_DIR = "recordings"
VIDEO_WINDOW   = "TelloNO"

# Per-controller button/axis layout. Match is by substring of joy.get_name()
# (lowercased). Add a profile here after running `--discover` on a new pad.
#
# "buttons"      — action -> raw button index (JOYBUTTONDOWN.button)
# "trigger_axes" — action -> raw axis index, for pads that report L2/R2 as
#                  analog axes instead of digital buttons (common on XInput
#                  pads with analog triggers; not all pads do this)
CONTROLLER_PROFILES = {
    "8bitdo": {
        "buttons": {
            "start": 9, "select": 8,
            "l1": 4, "r1": 5, "l2": 6, "r2": 7,
            "a": 0, "b": 1, "x": 2, "y": 3,
        },
        "trigger_axes": {},
    },
}
DEFAULT_PROFILE_NAME = "8bitdo"

# ── helpers ──────────────────────────────────────────────────────────────────

def dead(val):
    return 0.0 if abs(val) < DEADZONE else val

def ts():
    return datetime.now().strftime("%Y%m%d_%H%M%S")

def clamp(v, lo=-100, hi=100):
    return max(lo, min(hi, int(v)))

def pick_profile(pad_name):
    name = pad_name.lower()
    for key, profile in CONTROLLER_PROFILES.items():
        if key in name:
            return key, profile
    return None, CONTROLLER_PROFILES[DEFAULT_PROFILE_NAME]

def discover_buttons():
    """Standalone mode: print raw button/axis indices as you press things.
    No Tello connection needed — use this to work out a new CONTROLLER_PROFILES entry."""
    pygame.init()
    pygame.joystick.init()
    if pygame.joystick.get_count() == 0:
        sys.exit("No gamepad found — plug it in and retry.")

    joy = pygame.joystick.Joystick(0)
    joy.init()
    print(f"Gamepad: {joy.get_name()}")
    print("Press buttons / move sticks & triggers. Ctrl+C to quit.\n")

    try:
        while True:
            for event in pygame.event.get():
                if event.type == pygame.JOYBUTTONDOWN:
                    print(f"button down: {event.button}")
                elif event.type == pygame.JOYHATMOTION:
                    print(f"hat: {event.value}")
                elif event.type == pygame.JOYAXISMOTION:
                    if abs(event.value) > 0.5:
                        print(f"axis {event.axis}: {event.value:+.2f}")
            time.sleep(1 / 60)
    except KeyboardInterrupt:
        print("\nDone.")
    finally:
        pygame.quit()

# ── main class ───────────────────────────────────────────────────────────────

class TelloCompanion:
    def __init__(self):
        self.tello         = Tello()
        self.armed         = False
        self.flying        = False
        self.recording     = False
        self.video_display = False
        self.speed_idx     = 1            # MED by default
        self.rc            = [0, 0, 0, 0] # roll pitch throttle yaw
        self._rc_stop      = threading.Event()
        self._rec_thread   = None
        self._frame_reader = None
        self._trigger_prev = {}           # action -> was-pulled bool, for axis-based triggers

        os.makedirs(PHOTOS_DIR, exist_ok=True)
        os.makedirs(RECORDINGS_DIR, exist_ok=True)

    # ── drone connection ─────────────────────────────────────────────────────

    def connect(self):
        print("Connecting to Tello …")
        self.tello.connect()
        bat = self.tello.get_battery()
        print(f"Connected  |  Battery: {bat}%")
        if bat <= 20:
            print("⚠  Low battery — land soon")

    def arm(self):
        if not self.armed:
            # djitellopy sends 'command' in connect(); this re-arms SDK mode
            self.tello.send_command_with_return("command")
            self.armed = True
            print("✓ ARMED — ready for takeoff")
        else:
            print("✓ ARMED (already)")

    def takeoff(self):
        if not self.armed:
            print("Arm first (Start button)")
            return
        if self.flying:
            return
        print("● TAKEOFF")
        self.tello.takeoff()
        self.flying = True

    def land(self):
        if not self.flying:
            return
        print("● LANDING")
        self.tello.land()
        self.flying = False
        self.armed  = False

    def emergency(self):
        print("⚠ EMERGENCY STOP")
        self.tello.emergency()
        self.flying = False
        self.armed  = False

    def flip(self, direction):
        if self.flying:
            print(f"↩ flip {direction}")
            self.tello.flip(direction)

    def cycle_speed(self, direction):
        self.speed_idx = max(0, min(len(SPEED_STEPS) - 1, self.speed_idx + direction))
        spd = SPEED_STEPS[self.speed_idx]
        self.tello.set_speed(spd)
        print(f"Speed: {SPEED_LABELS[self.speed_idx]} ({spd})")

    # ── photo / recording / live view ────────────────────────────────────────

    def take_photo(self):
        frame = self._frame_reader.frame
        if frame is None:
            print("No frame available")
            return
        path = os.path.join(PHOTOS_DIR, f"tello_{ts()}.jpg")
        cv2.imwrite(path, frame)
        print(f"📷 Saved: {path}")

    def toggle_recording(self):
        if not self.recording:
            self._start_recording()
        else:
            self._stop_recording()

    def _start_recording(self):
        path = os.path.join(RECORDINGS_DIR, f"tello_{ts()}.mp4")

        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        self._video_writer = cv2.VideoWriter(path, fourcc, 30, (960, 720))
        self.recording = True
        self._rec_path = path

        def _record():
            while self.recording:
                frame = self._frame_reader.frame
                if frame is not None:
                    self._video_writer.write(frame)
                time.sleep(1 / 30)

        self._rec_thread = threading.Thread(target=_record, daemon=True)
        self._rec_thread.start()
        print(f"⏺ Recording: {path}")

    def _stop_recording(self):
        self.recording = False
        if self._rec_thread:
            self._rec_thread.join(timeout=2)
        if hasattr(self, "_video_writer"):
            self._video_writer.release()
        print(f"⏹ Saved: {self._rec_path}")

    def toggle_video_display(self):
        self.video_display = not self.video_display
        if not self.video_display:
            cv2.destroyWindow(VIDEO_WINDOW)
        print(f"{'▶' if self.video_display else '⏸'} Video display {'on' if self.video_display else 'off'}")

    # ── RC control loop ──────────────────────────────────────────────────────

    def _rc_loop(self):
        while not self._rc_stop.is_set():
            r, p, t, y = self.rc
            if self.flying and any((r, p, t, y)):
                self.tello.send_rc_control(r, p, t, y)
            time.sleep(1 / RC_HZ)

    def start_rc(self):
        self._rc_thread = threading.Thread(target=self._rc_loop, daemon=True)
        self._rc_thread.start()

    def stop_rc(self):
        self._rc_stop.set()

    # ── input handling ───────────────────────────────────────────────────────

    def _axis(self, joy, idx):
        try:
            return dead(joy.get_axis(idx))
        except Exception:
            return 0.0

    def fire(self, action):
        if action == "start":    self.arm()
        elif action == "select": self.emergency()
        elif action == "l2":     self.takeoff()
        elif action == "l1":     self.land()
        elif action == "r1":     self.toggle_recording()
        elif action == "r2":     self.take_photo()
        elif action == "a":      self.toggle_video_display()
        elif action == "y":      self.flip("f")
        elif action == "x":      self.flip("l")
        elif action == "b":      self.flip("r")

    def run(self):
        pygame.init()
        pygame.joystick.init()

        if pygame.joystick.get_count() == 0:
            print("No gamepad found — keyboard fallback not implemented")
            print("Plug in your gamepad and restart")
            self.tello.end()
            return

        joy = pygame.joystick.Joystick(0)
        joy.init()
        profile_name, profile = pick_profile(joy.get_name())
        idx_to_action = {v: k for k, v in profile["buttons"].items()}
        axis_to_action = {v: k for k, v in profile["trigger_axes"].items()}

        print(f"Gamepad: {joy.get_name()}")
        if profile_name:
            print(f"Control profile: {profile_name}")
        else:
            print("⚠ No matching control profile — using default (8bitdo) mapping.")
            print("  If buttons feel wrong, run `python3 tello_companion.py --discover`")
            print("  to find this pad's real indices and add a profile for it.")
        print("Controls: Start=Arm  L2=Takeoff  L1=Land  Select=Emergency")
        print("          R2=Photo   R1=Record   A=Video  SPD: DpadUp/Down")

        # Enable Tello video
        self.tello.streamon()
        self._frame_reader = self.tello.get_frame_read()
        self.start_rc()

        clock = pygame.time.Clock()

        try:
            while True:
                for event in pygame.event.get():
                    if event.type == pygame.QUIT:
                        return

                    if event.type == pygame.JOYBUTTONDOWN:
                        action = idx_to_action.get(event.button)
                        if action:
                            self.fire(action)

                    if event.type == pygame.JOYHATMOTION:
                        hx, hy = event.value
                        if hy == 1:    self.cycle_speed(1)    # D-pad up
                        elif hy == -1: self.cycle_speed(-1)   # D-pad down

                # Trigger axes (pads that report L2/R2 as analog triggers, not buttons)
                for axis_idx, action in axis_to_action.items():
                    pulled = self._axis(joy, axis_idx) > TRIGGER_THRESH
                    if pulled and not self._trigger_prev.get(action):
                        self.fire(action)
                    self._trigger_prev[action] = pulled

                # Read sticks
                # Axis layout (XInput): 0=LX 1=LY 2=RX 3=RY 4=LT 5=RT
                lx = self._axis(joy, 0)
                ly = self._axis(joy, 1)
                rx = self._axis(joy, 2)
                ry = self._axis(joy, 3)

                spd = SPEED_STEPS[self.speed_idx]
                self.rc = [
                    clamp(rx  * spd),   # roll
                    clamp(-ry * spd),   # pitch (invert Y)
                    clamp(-ly * spd),   # throttle (invert Y)
                    clamp(lx  * spd),   # yaw
                ]

                if self.video_display:
                    frame = self._frame_reader.frame
                    if frame is not None:
                        cv2.imshow(VIDEO_WINDOW, frame)
                    cv2.waitKey(1)

                clock.tick(RC_HZ)

        except KeyboardInterrupt:
            print("\nExiting …")
        finally:
            if self.flying:
                print("Auto-landing …")
                self.tello.land()
            self.stop_rc()
            if self.recording:
                self._stop_recording()
            cv2.destroyAllWindows()
            self.tello.streamoff()
            self.tello.end()
            pygame.quit()


# ── entry point ──────────────────────────────────────────────────────────────

if __name__ == "__main__":
    if "--discover" in sys.argv:
        discover_buttons()
    else:
        companion = TelloCompanion()
        companion.connect()
        companion.run()
