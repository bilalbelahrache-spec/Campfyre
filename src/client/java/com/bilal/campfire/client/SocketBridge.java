package com.bilal.campfire.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

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

    // A dead consumer/CGNAT/mobile-hotspot NAT mapping (routine on real home
    // networks - see this repo's own coordinator-keepalive comment) drops
    // silently, with no RST. Without a read timeout, copy()'s blocking
    // read() below waited forever with nothing to detect or log it, leaving
    // an unexplained freeze whose only recovery path was vanilla
    // Minecraft's own much slower protocol-level keepalive eventually
    // tearing down the local side. The timeout just wakes the thread
    // periodically to re-check liveness - it isn't itself treated as an
    // error (see the SocketTimeoutException handling below).
    private static final int IDLE_TIMEOUT_MS = 30_000;

    static void pump(Socket a, Socket b, String label) {
        try {
            a.setKeepAlive(true);
            b.setKeepAlive(true);
            a.setSoTimeout(IDLE_TIMEOUT_MS);
            b.setSoTimeout(IDLE_TIMEOUT_MS);
        } catch (IOException ignored) {
            // Best-effort hygiene - a socket that already rejects these
            // calls will fail on its first real read/write anyway.
        }
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
            while (true) {
                int len;
                try {
                    len = in.read(buffer);
                } catch (SocketTimeoutException e) {
                    // Just an idle window, not necessarily a dead link - but
                    // if the OTHER pump direction already tore this pair
                    // down (its own read failed first), there's no point
                    // looping forever doing nothing.
                    if (a.isClosed() || b.isClosed()) return;
                    continue;
                }
                if (len == -1) break;
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
