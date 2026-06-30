package com.duvitech.network.udp;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

public class UDPStatusServer extends Thread {
    private static final String TAG = "UDPStatusServer";

    public interface StatusCallback {
        void onStatus(String status);
    }

    private DatagramSocket socket;
    private volatile boolean running;
    private final StatusCallback callback;
    private final byte[] buf = new byte[256];

    public UDPStatusServer(StatusCallback callback) throws SocketException {
        this.callback = callback;
        socket = new DatagramSocket(8890);
    }

    public void stopServer() {
        running = false;
        socket.close();
    }

    @Override
    public void run() {
        running = true;
        while (running) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
                String data = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                // Parse battery from status string: "bat:XX;"
                String display = parseSummary(data);
                if (callback != null) callback.onStatus(display);
                Log.d(TAG, data);
            } catch (IOException e) {
                if (running) Log.e(TAG, e.getMessage());
            }
        }
    }

    private String parseSummary(String data) {
        String bat = extract(data, "bat:");
        String h   = extract(data, "h:");
        String spd = extract(data, "vgx:");
        return "Bat:" + bat + "%  Alt:" + h + "cm";
    }

    private String extract(String data, String key) {
        int i = data.indexOf(key);
        if (i < 0) return "?";
        int start = i + key.length();
        int end = data.indexOf(';', start);
        return end < 0 ? data.substring(start) : data.substring(start, end);
    }
}
