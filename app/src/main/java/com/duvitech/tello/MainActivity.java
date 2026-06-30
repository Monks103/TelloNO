package com.duvitech.tello;

import android.app.Activity;
import android.os.Bundle;
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

import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "TelloDemo";

    private Button btnVideo;
    private TextView tvStatus;
    private boolean videoRunning = false;

    private TelloVideoDecoder videoDecoder;
    private UDPClient client;
    private SurfaceHolder surfaceHolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnVideo = findViewById(R.id.btnVideo);
        tvStatus = findViewById(R.id.textview_message);

        SurfaceView sv = findViewById(R.id.surface_video);
        surfaceHolder = sv.getHolder();
        surfaceHolder.addCallback(this);

        try {
            client = new UDPClient();
            sendCommand("command");
            setStatus("Connected to Tello");
        } catch (Exception ex) {
            Log.e(TAG, "Connection error: " + ex.getMessage());
            setStatus("Connection failed — connect to Tello WiFi first");
        }

        btnVideo.setOnClickListener(v -> toggleVideo());
    }

    private void toggleVideo() {
        if (!videoRunning) {
            sendCommand("streamon");
            if (surfaceHolder != null && surfaceHolder.getSurface().isValid()) {
                videoDecoder = new TelloVideoDecoder(surfaceHolder.getSurface());
                videoDecoder.startDecoding();
                videoRunning = true;
                btnVideo.setText(R.string.stop_video);
                setStatus("Video streaming");
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
            setStatus("Video stopped");
        }
    }

    private void sendCommand(String cmd) {
        if (client != null) {
            try {
                client.sendBytes(cmd.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "Command error: " + e.getMessage());
            }
        }
    }

    private void setStatus(final String msg) {
        runOnUiThread(() -> {
            if (tvStatus != null) tvStatus.setText(msg);
        });
    }

    @Override
    protected void onDestroy() {
        if (videoDecoder != null) videoDecoder.stopDecoding();
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
        InputDevice inputDevice = event.getDevice();
        float lx = getCenteredAxis(event, inputDevice, MotionEvent.AXIS_X, historyPos);
        float ly = getCenteredAxis(event, inputDevice, MotionEvent.AXIS_Y, historyPos);
        float rx = getCenteredAxis(event, inputDevice, MotionEvent.AXIS_Z, historyPos);
        float ry = getCenteredAxis(event, inputDevice, MotionEvent.AXIS_RZ, historyPos);
        float hx = getCenteredAxis(event, inputDevice, MotionEvent.AXIS_HAT_X, historyPos);
        float hy = getCenteredAxis(event, inputDevice, MotionEvent.AXIS_HAT_Y, historyPos);

        if (client != null) {
            if (hx != 0 || hy != 0) {
                if (hx > 0) sendCommand("flip r");
                else if (hx < 0) sendCommand("flip l");
                if (hy < 0) sendCommand("flip f");
                else if (hy > 0) sendCommand("flip b");
            } else {
                int yaw   = (int)(lx * 100.0);
                int throttle = (int)(ly * -100.0);
                int roll  = (int)(rx * 100.0);
                int pitch = (int)(ry * -100.0);
                sendCommand("rc " + yaw + " " + pitch + " " + throttle + " " + roll);
            }
        }
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
                    setStatus("Taking off");
                    return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    sendCommand("land");
                    setStatus("Landing");
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
