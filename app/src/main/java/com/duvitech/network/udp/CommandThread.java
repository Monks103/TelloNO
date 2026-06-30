package com.duvitech.network.udp;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

public class CommandThread extends Thread {
    private static final String TAG = "CommandThread";
    private InetAddress address;
    private DatagramSocket socket;
    private byte[] msgBytes;

    public CommandThread(InetAddress address, DatagramSocket socket, String msg) {
        this.address = address;
        this.msgBytes = msg.getBytes(StandardCharsets.UTF_8);
        this.socket = socket;
    }

    public CommandThread(InetAddress address, DatagramSocket socket, byte[] msg) {
        this.address = address;
        this.msgBytes = msg;
        this.socket = socket;
    }

    @Override
    public void run() {
        String msgStr = new String(msgBytes, StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(msgBytes, msgBytes.length, address, UDPClient.PORT);
        try {
            socket.send(packet);
            if (!msgStr.startsWith("rc "))
                Log.d(TAG, "Sent: " + msgStr);
        } catch (SocketException se) {
            Log.e(TAG, "Socket error sending '" + msgStr + "': " + se.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "IO error sending '" + msgStr + "': " + e.getMessage());
        }
    }
}
