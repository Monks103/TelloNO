#!/usr/bin/env python3
"""
TelloNO companion — fly Tello from laptop with an 8BitDo (or any pygame joystick).

Usage:
    pip install djitellopy pygame
    Connect laptop to Tello WiFi, then:
    python3 tello_companion.py

Gamepad mapping (matches TelloNO Android app):
    Left stick     → throttle (Y) + yaw (X)
    Right stick    → roll (X) + pitch (Y)
    L2 trigger     → takeoff
    L1 bumper      → land
    R1 bumper      → photo (screenshot saved to ./photos/)
    R2 trigger     → toggle recording (.mp4 saved to ./recordings/)
    Start          → arm  (sends 'command' to enter SDK mode)
    Select         → emergency stop
    A              → toggle video stream display window
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

# ── constants ────────────────────────────────────────────────────────────────

DEADZONE     = 0.12    # ignore stick values below this
RC_HZ        = 20      # RC command rate
SPEED_STEPS  = [30, 60, 100]
SPEED_LABELS = ["LOW", "MED", "HIGH"]

PHOTOS_DIR     = "photos"
RECORDINGS_DIR = "recordings"

# ── helpers ──────────────────────────────────────────────────────────────────

def dead(val):
    return 0.0 if abs(val) < DEADZONE else val

def ts():
    return datetime.now().strftime("%Y%m%d_%H%M%S")

def clamp(v, lo=-100, hi=100):
    return max(lo, min(hi, int(v)))

# ── main class ───────────────────────────────────────────────────────────────

class TelloCompanion:
    def __init__(self):
        self.tello        = Tello()
        self.armed        = False
        self.flying       = False
        self.recording    = False
        self.speed_idx    = 1            # MED by default
        self.rc           = [0, 0, 0, 0] # roll pitch throttle yaw
        self._rc_stop     = threading.Event()
        self._rec_thread  = None

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

    # ── photo / recording ────────────────────────────────────────────────────

    def take_photo(self):
        frame = self.tello.get_frame_read().frame
        if frame is None:
            print("No frame available")
            return
        import cv2
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
        frame_reader = self.tello.get_frame_read()

        import cv2
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        self._video_writer = cv2.VideoWriter(path, fourcc, 30, (960, 720))
        self.recording = True
        self._rec_path = path

        def _record():
            while self.recording:
                frame = frame_reader.frame
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

    def run(self):
        pygame.init()
        pygame.joystick.init()

        if pygame.joystick.get_count() == 0:
            print("No gamepad found — keyboard fallback not implemented")
            print("Plug in your 8BitDo and restart")
            self.tello.end()
            return

        joy = pygame.joystick.Joystick(0)
        joy.init()
        print(f"Gamepad: {joy.get_name()}")
        print("Controls: Start=Arm  L2=Takeoff  L1=Land  Select=Emergency")
        print("          R2=Photo   R1=Record   A=Video  SPD: DpadUp/Down")

        # Track button press edges (avoid repeat)
        prev_buttons = {}

        # Enable Tello video
        self.tello.streamon()
        self.start_rc()

        clock = pygame.time.Clock()
        scale = lambda v: clamp(v * SPEED_STEPS[self.speed_idx])

        try:
            while True:
                for event in pygame.event.get():
                    if event.type == pygame.QUIT:
                        return

                    if event.type == pygame.JOYBUTTONDOWN:
                        btn = event.button
                        # 8BitDo Zero 2 / Ultimate / Pro 2 button indices (XInput mode)
                        # A=0 B=1 X=2 Y=3 L1=4 R1=5 L2=6 R2=7 Select=8 Start=9
                        if btn == 9:   self.arm()
                        elif btn == 6: self.takeoff()          # L2
                        elif btn == 4: self.land()             # L1
                        elif btn == 5: self.toggle_recording() # R1
                        elif btn == 7: self.take_photo()       # R2
                        elif btn == 8: self.emergency()        # Select
                        elif btn == 0: self.flip("f")          # A → flip forward
                        elif btn == 3: self.flip("f")          # Y
                        elif btn == 2: self.flip("l")          # X
                        elif btn == 1: self.flip("r")          # B

                    if event.type == pygame.JOYHATMOTION:
                        hx, hy = event.value
                        if hy == 1:  self.cycle_speed(1)   # D-pad up
                        elif hy == -1: self.cycle_speed(-1) # D-pad down

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
            self.tello.streamoff()
            self.tello.end()
            pygame.quit()


# ── entry point ──────────────────────────────────────────────────────────────

if __name__ == "__main__":
    companion = TelloCompanion()
    companion.connect()
    companion.run()
