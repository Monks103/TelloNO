package com.duvitech.network.udp;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class CommandThread extends Thread {
    private static final String TAG = "CommandThread";
    private InetAddress address;
    private DatagramSocket socket;
    private byte[] msgBytes;

    public CommandThread(InetAddress address, DatagramSocket socket, String msg)  {
        this.address = address;
        this.msgBytes = msg.getBytes(StandardCharsets.UTF_8);
        this.socket = socket;
    }

    public CommandThread(InetAddress address, DatagramSocket socket, byte[] msg)  {
        this.address = address;
        this.msgBytes = msg;
        this.socket = socket;
    }

    @Override
    public void run() {
        String msgStr = new String(msgBytes, StandardCharsets.UTF_8);
        Log.d(TAG, "Sending: " + msgStr + " -> " + address + ":" + UDPClient.PORT);
        DatagramPacket packet = new DatagramPacket(msgBytes, msgBytes.length, address, UDPClient.PORT);
        try {
            socket.send(packet);
            Log.d(TAG, "Sent OK: " + msgStr);

            byte[] buf = new byte[500];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.setSoTimeout(1000);
            try {
                socket.receive(resp);
                String response = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                Log.d(TAG, "Response to '" + msgStr + "': " + response);
            } catch (SocketTimeoutException ste) {
                Log.e(TAG, "No response to '" + msgStr + "' (1s timeout — Tello may not be in range or not in SDK mode)");
            }
        } catch (SocketException se) {
            Log.e(TAG, "Socket error: " + se.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "Command Error: " + e.getMessage());
        }
    }
}
