package com.bilal.campfire.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Pumps raw bytes both ways between two already-connected sockets until
 * either side closes, then closes both. Used once a hole-punched P2P socket
 * exists, to splice it to the local Minecraft client/server socket on the
 * other end - no framing needed (unlike the coordinator relay's
 * relay_open/relay_data/relay_close), since both ends are already talking
 * raw Minecraft protocol bytes directly over a real TCP connection.
 */
final class SocketBridge {

    private SocketBridge() {
    }

    static void pump(Socket a, Socket b, String label) {
        Thread ab = new Thread(() -> copy(a, b, a, b), "punch-bridge-" + label + "-ab");
        Thread ba = new Thread(() -> copy(b, a, a, b), "punch-bridge-" + label + "-ba");
        ab.setDaemon(true);
        ba.setDaemon(true);
        ab.start();
        ba.start();
    }

    private static void copy(Socket from, Socket to, Socket a, Socket b) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                out.flush();
            }
        } catch (IOException ignored) {
            // either side closed/errored - fall through and close both
        } finally {
            closeQuietly(a);
            closeQuietly(b);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
