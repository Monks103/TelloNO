package com.duvitech.tello;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

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

    // H.264 SPS and PPS headers for Tello 960x720
    private static final byte[] SPS = {0, 0, 0, 1, 103, 66, 0, 42, (byte)149, (byte)168, 30, 0, (byte)137, (byte)249, 102, (byte)224, 32, 32, 32, 64};
    private static final byte[] PPS = {0, 0, 0, 1, 104, (byte)206, 60, (byte)128};

    public TelloVideoDecoder(Surface surface) {
        this.surface = surface;
    }

    public void startDecoding() {
        running = true;
        start();
    }

    public void stopDecoding() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing decoder: " + e.getMessage());
            }
        }
    }

    private void initDecoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 960, 720);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_SIZE);

        ByteBuffer spsBuffer = ByteBuffer.wrap(SPS);
        ByteBuffer ppsBuffer = ByteBuffer.wrap(PPS);
        format.setByteBuffer("csd-0", spsBuffer);
        format.setByteBuffer("csd-1", ppsBuffer);

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

            byte[] recvBuf = new byte[BUFFER_SIZE];
            byte[] frameBuf = new byte[MAX_FRAME_SIZE];
            int frameLen = 0;

            socket.setSoTimeout(3000);
            Log.d(TAG, "Listening on UDP port " + VIDEO_PORT);
            int packetCount = 0;
            int timeoutCount = 0;
            while (running) {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException ste) {
                    timeoutCount++;
                    Log.d(TAG, "Still waiting... timeout #" + timeoutCount + " (no data on port 11111)");
                    continue;
                }
                int len = packet.getLength();
                packetCount++;
                if (packetCount <= 5 || packetCount % 100 == 0)
                    Log.d(TAG, "Packet #" + packetCount + " len=" + len + " from=" + packet.getAddress());

                if (frameLen + len > MAX_FRAME_SIZE) {
                    frameLen = 0;
                }

                System.arraycopy(packet.getData(), 0, frameBuf, frameLen, len);
                frameLen += len;

                // Tello sends frames in chunks — last chunk is < 1460 bytes
                if (len < 1460) {
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
                decoder.queueInputBuffer(inputIndex, 0, length,
                        System.nanoTime() / 1000, 0);
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Decoder feed error: " + e.getMessage());
        }
    }
}
