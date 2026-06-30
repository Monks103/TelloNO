package com.duvitech.tello;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

public class TelloVideoDecoder extends Thread {
    private static final String TAG = "TelloVideoDecoder";
    private static final int VIDEO_PORT = 11111;
    private static final int BUFFER_SIZE = 2048;
    private static final int MAX_FRAME_SIZE = 65536;

    private MediaCodec decoder;
    private DatagramSocket socket;
    private Surface surface;
    private volatile boolean running = false;

    // Recording
    private volatile boolean recording = false;
    private FileOutputStream recordingStream;

    // Snapshot callback
    public interface FrameCallback { void onFrame(byte[] data, int length); }
    private volatile FrameCallback snapshotCallback;

    // H.264 SPS and PPS for Tello 960x720
    static final byte[] SPS = {0, 0, 0, 1, 103, 66, 0, 42, (byte)149, (byte)168, 30, 0, (byte)137, (byte)249, 102, (byte)224, 32, 32, 32, 64};
    static final byte[] PPS = {0, 0, 0, 1, 104, (byte)206, 60, (byte)128};

    public TelloVideoDecoder(Surface surface) { this.surface = surface; }

    public void startDecoding() { running = true; start(); }

    public void stopDecoding() {
        running = false;
        stopRecording();
        if (socket != null && !socket.isClosed()) socket.close();
        if (decoder != null) {
            try { decoder.stop(); decoder.release(); } catch (Exception e) { Log.e(TAG, e.getMessage()); }
        }
    }

    public void startRecording(String path) throws IOException {
        recordingStream = new FileOutputStream(path);
        recordingStream.write(SPS);
        recordingStream.write(PPS);
        recording = true;
        Log.d(TAG, "Recording started: " + path);
    }

    public void stopRecording() {
        recording = false;
        if (recordingStream != null) {
            try { recordingStream.flush(); recordingStream.close(); } catch (Exception e) { Log.e(TAG, e.getMessage()); }
            recordingStream = null;
            Log.d(TAG, "Recording stopped");
        }
    }

    public boolean isRecording() { return recording; }

    public void requestSnapshot(FrameCallback cb) { snapshotCallback = cb; }

    private void initDecoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 960, 720);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_SIZE);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(SPS));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(PPS));
        decoder = MediaCodec.createDecoderByType("video/avc");
        decoder.configure(format, surface, null, 0);
        decoder.start();
        Log.d(TAG, "MediaCodec decoder started");
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(VIDEO_PORT);
            initDecoder();

            byte[] recvBuf  = new byte[BUFFER_SIZE];
            byte[] frameBuf = new byte[MAX_FRAME_SIZE];
            int frameLen = 0;

            socket.setSoTimeout(3000);
            Log.d(TAG, "Listening on UDP port " + VIDEO_PORT);
            int packetCount = 0;

            while (running) {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException ste) {
                    continue;
                }
                int len = packet.getLength();
                packetCount++;
                if (packetCount <= 5 || packetCount % 500 == 0)
                    Log.d(TAG, "Packet #" + packetCount + " len=" + len);

                if (frameLen + len > MAX_FRAME_SIZE) frameLen = 0;
                System.arraycopy(packet.getData(), 0, frameBuf, frameLen, len);
                frameLen += len;

                if (len < 1460) {
                    // write to recording file
                    if (recording && recordingStream != null) {
                        try { recordingStream.write(frameBuf, 0, frameLen); } catch (Exception e) { Log.e(TAG, e.getMessage()); }
                    }
                    // snapshot callback (one frame, then clear)
                    FrameCallback cb = snapshotCallback;
                    if (cb != null) {
                        byte[] copy = new byte[frameLen];
                        System.arraycopy(frameBuf, 0, copy, 0, frameLen);
                        cb.onFrame(copy, frameLen);
                        snapshotCallback = null;
                    }
                    feedDecoder(frameBuf, frameLen);
                    frameLen = 0;
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Video error: " + e.getMessage());
        }
    }

    private void feedDecoder(byte[] data, int length) {
        try {
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                inputBuffer.clear();
                inputBuffer.put(data, 0, length);
                decoder.queueInputBuffer(inputIndex, 0, length, System.nanoTime() / 1000, 0);
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
            if (outputIndex >= 0) decoder.releaseOutputBuffer(outputIndex, true);
        } catch (Exception e) {
            Log.e(TAG, "Decoder feed error: " + e.getMessage());
        }
    }
}
