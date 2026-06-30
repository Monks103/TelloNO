package com.duvitech.tello;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;

import com.duvitech.network.udp.UDPClient;
import com.duvitech.network.udp.UDPStatusServer;

import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "TelloDemo";

    private static final int[] SPEEDS = {30, 60, 100};
    private static final String[] SPEED_LABELS = {"SPD: LOW", "SPD: MED", "SPD: HIGH"};
    private int speedIndex = 1; // default medium

    private Button btnVideo, btnSpeed;
    private TextView tvStatus;
    private boolean videoRunning = false;

    private TelloVideoDecoder videoDecoder;
    private UDPClient client;
    private UDPStatusServer statusServer;
    private SurfaceHolder surfaceHolder;

    // Touch joystick values (-100 to 100)
    private volatile int touchLX, touchLY, touchRX, touchRY;
    // Gamepad values
    private volatile int padLX, padLY, padRX, padRY;

    private final Handler rcHandler = new Handler(Looper.getMainLooper());
    private final Runnable rcRunnable = new Runnable() {
        @Override
        public void run() {
            sendRc();
            rcHandler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnVideo = findViewById(R.id.btnVideo);
        btnSpeed = findViewById(R.id.btnSpeed);
        tvStatus = findViewById(R.id.textview_message);

        SurfaceView sv = findViewById(R.id.surface_video);
        surfaceHolder = sv.getHolder();
        surfaceHolder.addCallback(this);

        // Joysticks
        JoystickView leftStick  = findViewById(R.id.joystickLeft);
        JoystickView rightStick = findViewById(R.id.joystickRight);
        leftStick.setListener((x, y)  -> { touchLX = x; touchLY = y; });
        rightStick.setListener((x, y) -> { touchRX = x; touchRY = y; });

        // Flight buttons
        findViewById(R.id.btnTakeoff).setOnClickListener(v -> {
            sendCommand("takeoff");
            setStatus("● TAKEOFF");
        });
        findViewById(R.id.btnLand).setOnClickListener(v -> {
            sendCommand("land");
            setStatus("● LANDING");
        });
        findViewById(R.id.btnEmergency).setOnClickListener(v -> {
            sendCommand("emergency");
            setStatus("⚠ EMERGENCY STOP");
        });

        // Flip buttons
        findViewById(R.id.btnFlipF).setOnClickListener(v -> sendCommand("flip f"));
        findViewById(R.id.btnFlipB).setOnClickListener(v -> sendCommand("flip b"));
        findViewById(R.id.btnFlipL).setOnClickListener(v -> sendCommand("flip l"));
        findViewById(R.id.btnFlipR).setOnClickListener(v -> sendCommand("flip r"));

        // Speed cycle
        btnSpeed.setOnClickListener(v -> {
            speedIndex = (speedIndex + 1) % SPEEDS.length;
            btnSpeed.setText(SPEED_LABELS[speedIndex]);
            sendCommand("speed " + SPEEDS[speedIndex]);
        });

        btnVideo.setOnClickListener(v -> toggleVideo());

        // Connect
        try {
            client = new UDPClient();
            sendCommand("command");
            sendCommand("speed " + SPEEDS[speedIndex]);
            setStatus("● CONNECTED");
        } catch (Exception ex) {
            Log.e(TAG, "Connection error: " + ex.getMessage());
            setStatus("● JOIN TELLO WIFI");
        }

        // Status telemetry
        try {
            statusServer = new UDPStatusServer(msg -> runOnUiThread(() -> tvStatus.setText(msg)));
            statusServer.start();
        } catch (Exception e) {
            Log.e(TAG, "Status server: " + e.getMessage());
        }

        rcHandler.postDelayed(rcRunnable, 500);
    }

    private void sendRc() {
        int roll     = padRX != 0 ? padRX : touchRX;
        int pitch    = padRY != 0 ? -padRY : -touchRY;
        int throttle = padLY != 0 ? -padLY : -touchLY;
        int yaw      = padLX != 0 ? padLX : touchLX;
        if (roll == 0 && pitch == 0 && throttle == 0 && yaw == 0) return;
        sendCommand("rc " + roll + " " + pitch + " " + throttle + " " + yaw);
    }

    private void toggleVideo() {
        if (!videoRunning) {
            sendCommand("streamon");
            if (surfaceHolder != null && surfaceHolder.getSurface().isValid()) {
                videoDecoder = new TelloVideoDecoder(surfaceHolder.getSurface());
                videoDecoder.startDecoding();
                videoRunning = true;
                btnVideo.setText(R.string.stop_video);
            } else {
                setStatus("Surface not ready");
            }
        } else {
            sendCommand("streamoff");
            if (videoDecoder != null) { videoDecoder.stopDecoding(); videoDecoder = null; }
            videoRunning = false;
            btnVideo.setText(R.string.start_video);
        }
    }

    private void sendCommand(String cmd) {
        if (client != null) {
            try {
                client.sendBytes(cmd.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "CMD error: " + e.getMessage());
            }
        }
    }

    private void setStatus(final String msg) {
        runOnUiThread(() -> { if (tvStatus != null) tvStatus.setText(msg); });
    }

    @Override
    protected void onDestroy() {
        rcHandler.removeCallbacks(rcRunnable);
        if (videoDecoder != null) videoDecoder.stopDecoding();
        if (statusServer != null) statusServer.stopServer();
        if (client != null) client.close();
        super.onDestroy();
    }

    // --- Gamepad ---

    private static float getCenteredAxis(MotionEvent event, InputDevice device, int axis, int historyPos) {
        final InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range != null) {
            final float flat = range.getFlat();
            final float value = historyPos < 0 ? event.getAxisValue(axis) : event.getHistoricalAxisValue(axis, historyPos);
            if (Math.abs(value) > flat) return value;
        }
        return 0;
    }

    private void processJoystickInput(MotionEvent event, int historyPos) {
        InputDevice dev = event.getDevice();
        padLX = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_X,  historyPos) * 100);
        padLY = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_Y,  historyPos) * 100);
        padRX = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_Z,  historyPos) * 100);
        padRY = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_RZ, historyPos) * 100);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            final int historySize = event.getHistorySize();
            for (int i = 0; i < historySize; i++) processJoystickInput(event, i);
            processJoystickInput(event, -1);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                && event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_L1: sendCommand("takeoff");   setStatus("● TAKEOFF"); return true;
                case KeyEvent.KEYCODE_BUTTON_R1: sendCommand("land");      setStatus("● LANDING"); return true;
                case KeyEvent.KEYCODE_BUTTON_A:  toggleVideo();            return true;
                case KeyEvent.KEYCODE_BUTTON_Y:  sendCommand("flip f");    return true;
                case KeyEvent.KEYCODE_BUTTON_X:  sendCommand("flip l");    return true;
                case KeyEvent.KEYCODE_BUTTON_B:  sendCommand("flip r");    return true;
                case KeyEvent.KEYCODE_BUTTON_SELECT: sendCommand("emergency"); return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // --- Surface callbacks ---

    @Override public void surfaceCreated(SurfaceHolder holder) { Log.d(TAG, "Surface created"); }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.d(TAG, "Surface: " + w + "x" + h);
        surfaceHolder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (videoDecoder != null) { videoDecoder.stopDecoding(); videoDecoder = null; videoRunning = false; }
    }
}
