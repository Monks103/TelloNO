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
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.duvitech.network.udp.UDPClient;
import com.duvitech.network.udp.UDPStatusServer;

import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "TelloDemo";

    private Button btnVideo;
    private TextView tvStatus;
    private boolean videoRunning = false;

    private TelloVideoDecoder videoDecoder;
    private UDPClient client;
    private UDPStatusServer statusServer;
    private SurfaceHolder surfaceHolder;

    // Joystick values from touch controls (-100 to 100)
    private int touchLX = 0, touchLY = 0; // left stick: yaw, throttle
    private int touchRX = 0, touchRY = 0; // right stick: roll, pitch

    // Gamepad values (-100 to 100)
    private int padLX = 0, padLY = 0;
    private int padRX = 0, padRY = 0;

    private final Handler rcHandler = new Handler(Looper.getMainLooper());
    private final Runnable rcRunnable = new Runnable() {
        @Override
        public void run() {
            sendRc();
            rcHandler.postDelayed(this, 50); // 20 Hz
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnVideo = findViewById(R.id.btnVideo);
        tvStatus = findViewById(R.id.textview_message);

        SurfaceView sv = findViewById(R.id.surface_video);
        surfaceHolder = sv.getHolder();
        surfaceHolder.addCallback(this);

        // Joysticks
        JoystickView leftStick = findViewById(R.id.joystickLeft);
        JoystickView rightStick = findViewById(R.id.joystickRight);

        leftStick.setListener((x, y) -> {
            touchLX = x;   // yaw
            touchLY = y;   // throttle (inverted below)
        });
        rightStick.setListener((x, y) -> {
            touchRX = x;   // roll
            touchRY = y;   // pitch (inverted below)
        });

        // Takeoff / Land buttons
        findViewById(R.id.btnTakeoff).setOnClickListener(v -> {
            sendCommand("takeoff");
            setStatus("Taking off...");
        });
        findViewById(R.id.btnLand).setOnClickListener(v -> {
            sendCommand("land");
            setStatus("Landing...");
        });

        btnVideo.setOnClickListener(v -> toggleVideo());

        try {
            client = new UDPClient();
            sendCommand("command");
            setStatus("Connected to Tello");
        } catch (Exception ex) {
            Log.e(TAG, "Connection error: " + ex.getMessage());
            setStatus("Not connected — join Tello WiFi first");
        }

        try {
            statusServer = new UDPStatusServer(msg -> runOnUiThread(() -> tvStatus.setText(msg)));
            statusServer.start();
        } catch (Exception e) {
            Log.e(TAG, "Status server error: " + e.getMessage());
        }

        rcHandler.postDelayed(rcRunnable, 500);
    }

    private void sendRc() {
        // Gamepad takes priority over touch if non-zero
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
            if (videoDecoder != null) {
                videoDecoder.stopDecoding();
                videoDecoder = null;
            }
            videoRunning = false;
            btnVideo.setText(R.string.start_video);
        }
    }

    private void sendCommand(String cmd) {
        if (client != null) {
            try {
                Log.d(TAG, "CMD: " + cmd);
                client.sendBytes(cmd.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "Command error: " + e.getMessage());
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

    // --- Gamepad input ---

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
        padLX = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_X, historyPos) * 100);
        padLY = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_Y, historyPos) * 100);
        padRX = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_Z, historyPos) * 100);
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
                case KeyEvent.KEYCODE_BUTTON_L1:
                    sendCommand("takeoff");
                    setStatus("Taking off...");
                    return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    sendCommand("land");
                    setStatus("Landing...");
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                    toggleVideo();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    sendCommand("flip f");
                    return true;
                case KeyEvent.KEYCODE_BUTTON_X:
                    sendCommand("flip l");
                    return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                    sendCommand("flip r");
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // --- SurfaceHolder callbacks ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Surface created");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed: " + width + "x" + height);
        surfaceHolder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (videoDecoder != null) {
            videoDecoder.stopDecoding();
            videoDecoder = null;
            videoRunning = false;
        }
    }
}
