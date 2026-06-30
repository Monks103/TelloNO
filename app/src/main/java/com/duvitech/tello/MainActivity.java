package com.duvitech.tello;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.duvitech.network.udp.UDPClient;
import com.duvitech.network.udp.UDPStatusServer;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "TelloDemo";

    private static final int[] SPEEDS   = {30,    60,    100};
    private static final float[] SCALES = {0.25f, 0.50f, 1.0f};
    private static final String[] SPEED_LABELS = {"SPD: LOW", "SPD: MED", "SPD: HIGH"};
    private int speedIndex = 1;

    private Button btnVideo, btnSpeed, btnRecord, btnPhoto;
    private TextView tvStatus, tvConnection;
    private SurfaceView surfaceView;
    private boolean videoRunning = false;

    private TelloVideoDecoder videoDecoder;
    private UDPClient client;
    private UDPStatusServer statusServer;
    private SurfaceHolder surfaceHolder;

    private volatile int touchLX, touchLY, touchRX, touchRY;
    private volatile int padLX, padLY, padRX, padRY;

    private boolean lowBatteryWarned = false;
    private boolean isArmed = false;

    private final Handler rcHandler = new Handler(Looper.getMainLooper());
    private final Runnable rcRunnable = new Runnable() {
        @Override public void run() { sendRc(); rcHandler.postDelayed(this, 50); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        enterImmersive();

        btnVideo    = findViewById(R.id.btnVideo);
        btnSpeed    = findViewById(R.id.btnSpeed);
        btnRecord   = findViewById(R.id.btnRecord);
        btnPhoto    = findViewById(R.id.btnPhoto);
        tvStatus    = findViewById(R.id.textview_message);
        tvConnection = findViewById(R.id.tvConnection);
        surfaceView = findViewById(R.id.surface_video);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        JoystickView leftStick  = findViewById(R.id.joystickLeft);
        JoystickView rightStick = findViewById(R.id.joystickRight);
        leftStick.setListener((x, y)  -> { touchLX = x; touchLY = y; });
        rightStick.setListener((x, y) -> { touchRX = x; touchRY = y; });

        findViewById(R.id.btnTakeoff).setOnClickListener(v -> { sendCommand("takeoff"); setStatus("● TAKEOFF"); });
        findViewById(R.id.btnLand).setOnClickListener(v -> { sendCommand("land"); setStatus("● LANDING"); isArmed = false; });
        findViewById(R.id.btnEmergency).setOnClickListener(v -> { sendCommand("emergency"); setStatus("⚠ EMERGENCY"); isArmed = false; });

        btnSpeed.setOnClickListener(v -> cycleSpeed(1));
        findViewById(R.id.btnSpeedUp).setOnClickListener(v -> cycleSpeed(1));
        findViewById(R.id.btnSpeedDown).setOnClickListener(v -> cycleSpeed(-1));

        btnVideo.setOnClickListener(v -> toggleVideo());
        btnRecord.setOnClickListener(v -> toggleRecording());
        btnPhoto.setOnClickListener(v -> capturePhoto());

        findViewById(R.id.btnFlipF).setOnClickListener(v -> sendCommand("flip f"));
        findViewById(R.id.btnFlipB).setOnClickListener(v -> sendCommand("flip b"));
        findViewById(R.id.btnFlipL).setOnClickListener(v -> sendCommand("flip l"));
        findViewById(R.id.btnFlipR).setOnClickListener(v -> sendCommand("flip r"));

        try {
            client = new UDPClient();
            sendCommand("command");
            sendCommand("speed " + SPEEDS[speedIndex]);
            setStatus("● CONNECTED");
        } catch (Exception ex) {
            Log.e(TAG, "Connection error: " + ex.getMessage());
            setStatus("JOIN TELLO WIFI");
        }

        try {
            statusServer = new UDPStatusServer((display, battery, connected) ->
                runOnUiThread(() -> updateStatus(display, battery, connected)));
            statusServer.start();
        } catch (Exception e) {
            Log.e(TAG, "Status server: " + e.getMessage());
        }

        rcHandler.postDelayed(rcRunnable, 500);
    }

    private void updateStatus(String display, int battery, boolean connected) {
        tvStatus.setText(display);
        if (connected) {
            tvConnection.setText("●");
            tvConnection.setTextColor(battery > 20 ? 0xFF2ECC71 : 0xFFE74C3C);
            // Low battery warning
            if (battery <= 20 && battery > 0 && !lowBatteryWarned) {
                lowBatteryWarned = true;
                setStatus("⚠ LOW BATTERY: " + battery + "%");
                vibrate(new long[]{0, 300, 100, 300, 100, 300});
                Toast.makeText(this, "⚠ Low battery! Land soon.", Toast.LENGTH_LONG).show();
            }
            if (battery > 25) lowBatteryWarned = false;
        } else {
            tvConnection.setText("○");
            tvConnection.setTextColor(0xFFE74C3C);
        }
    }

    private void vibrate(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        }
    }

    private void sendRc() {
        float s = SCALES[speedIndex];
        int roll     = (int)((padRX != 0 ? padRX : touchRX) * s);
        int pitch    = (int)((padRY != 0 ? -padRY : -touchRY) * s);
        int throttle = (int)((padLY != 0 ? -padLY : -touchLY) * s);
        int yaw      = (int)((padLX != 0 ? padLX : touchLX) * s);
        if (roll == 0 && pitch == 0 && throttle == 0 && yaw == 0) return;
        sendCommand("rc " + roll + " " + pitch + " " + throttle + " " + yaw);
    }

    private void armSafety() {
        if (!isArmed) {
            sendCommand("command");
            isArmed = true;
            setStatus("✓ ARMED — ready for takeoff");
            vibrate(new long[]{0, 100, 50, 100});
        } else {
            setStatus("✓ ARMED — props ready");
        }
    }

    private void cycleSpeed(int dir) {
        speedIndex = Math.max(0, Math.min(SPEEDS.length - 1, speedIndex + dir));
        btnSpeed.setText(SPEED_LABELS[speedIndex]);
        sendCommand("speed " + SPEEDS[speedIndex]);
        setStatus("Speed: " + SPEED_LABELS[speedIndex]);
    }

    private void toggleVideo() {
        if (!videoRunning) {
            sendCommand("streamon");
            if (surfaceHolder != null && surfaceHolder.getSurface().isValid()) {
                videoDecoder = new TelloVideoDecoder(surfaceHolder.getSurface());
                videoDecoder.startDecoding();
                videoRunning = true;
                btnVideo.setText(R.string.stop_video);
            }
        } else {
            sendCommand("streamoff");
            if (videoDecoder != null) { videoDecoder.stopDecoding(); videoDecoder = null; }
            videoRunning = false;
            btnVideo.setText(R.string.start_video);
            btnRecord.setText("⏺ REC");
        }
    }

    private void toggleRecording() {
        if (videoDecoder == null) { Toast.makeText(this, "Start video first", Toast.LENGTH_SHORT).show(); return; }
        if (videoDecoder.isRecording()) {
            videoDecoder.stopRecording();
            btnRecord.setText("⏺ REC");
            Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show();
        } else {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                File file = new File(dir, "tello_" + ts + ".mp4");
                videoDecoder.startRecording(file.getAbsolutePath());
                btnRecord.setText("⏹ STOP");
                Toast.makeText(this, "Recording...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Record failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void capturePhoto() {
        if (!videoRunning || surfaceView == null) {
            Toast.makeText(this, "Start video first", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(surfaceView.getWidth(), surfaceView.getHeight(), Bitmap.Config.ARGB_8888);
        HandlerThread ht = new HandlerThread("PixelCopy");
        ht.start();
        PixelCopy.request(surfaceView, bitmap, result -> {
            if (result == PixelCopy.SUCCESS) saveBitmapToGallery(bitmap);
            else runOnUiThread(() -> Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show());
            ht.quitSafely();
        }, new Handler(ht.getLooper()));
    }

    private void saveBitmapToGallery(Bitmap bitmap) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "tello_" + ts + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TelloNO");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                runOnUiThread(() -> Toast.makeText(this, "📷 Photo saved!", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void sendCommand(String cmd) {
        if (client != null) {
            try { client.sendBytes(cmd.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { Log.e(TAG, "CMD: " + e.getMessage()); }
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
            for (int i = 0; i < event.getHistorySize(); i++) processJoystickInput(event, i);
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
                case KeyEvent.KEYCODE_BUTTON_L2:     sendCommand("takeoff");   setStatus("● TAKEOFF"); return true;
                case KeyEvent.KEYCODE_BUTTON_L1:     sendCommand("land");      setStatus("● LANDING"); isArmed = false; return true;
                case KeyEvent.KEYCODE_BUTTON_R1:     toggleRecording();        return true;
                case KeyEvent.KEYCODE_BUTTON_R2:     capturePhoto();           return true;
                case KeyEvent.KEYCODE_BUTTON_START:  armSafety();              return true;
                case KeyEvent.KEYCODE_BUTTON_A:      toggleVideo();            return true;
                case KeyEvent.KEYCODE_BUTTON_Y:      sendCommand("flip f");    return true;
                case KeyEvent.KEYCODE_BUTTON_X:      sendCommand("flip l");    return true;
                case KeyEvent.KEYCODE_BUTTON_B:      sendCommand("flip r");    return true;
                case KeyEvent.KEYCODE_BUTTON_SELECT: sendCommand("emergency"); isArmed = false; return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.systemBars());
                ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        rcHandler.postDelayed(() -> { if (!videoRunning) toggleVideo(); }, 1000);
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) { surfaceHolder = holder; }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (videoDecoder != null) { videoDecoder.stopDecoding(); videoDecoder = null; videoRunning = false; }
    }
}
