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
        void onStatus(String display, int battery, boolean connected);
    }

    private DatagramSocket socket;
    private volatile boolean running;
    private final StatusCallback callback;
    private final byte[] buf = new byte[256];

    public UDPStatusServer(StatusCallback callback) throws SocketException {
        this.callback = callback;
        socket = new DatagramSocket(8890);
        socket.setSoTimeout(3000);
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
                int bat  = parseInt(extract(data, "bat:"));
                int h    = parseInt(extract(data, "h:"));
                String display = "BAT:" + bat + "%  ALT:" + h + "cm";
                if (callback != null) callback.onStatus(display, bat, true);
            } catch (java.net.SocketTimeoutException ste) {
                if (callback != null) callback.onStatus("NOT CONNECTED", 0, false);
            } catch (IOException e) {
                if (running) Log.e(TAG, e.getMessage());
            }
        }
    }

    private String extract(String data, String key) {
        int i = data.indexOf(key);
        if (i < 0) return "0";
        int start = i + key.length();
        int end = data.indexOf(';', start);
        return end < 0 ? data.substring(start) : data.substring(start, end);
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
