package com.bilal.campfire.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TCP hole-punching helper. Uses the coordinator's own GET /reflect route
 * (a self-hosted STUN-equivalent, see server.js) instead of public STUN
 * infra, since that's almost always UDP-only and a NAT's UDP port mapping
 * says nothing about how it'll map a TCP connection - the two are tracked
 * completely independently. Speaking plain HTTP to it (by hand, over a raw
 * Socket we control) means it rides the same port/forward the coordinator's
 * WebSocket already uses - no second port needs forwarding on top of it.
 *
 * This only works if BOTH ends' NAT/firewall allows an unsolicited inbound
 * SYN to a port it just saw an outbound SYN leave from - true of most home
 * routers, never true behind carrier-grade NAT (no per-subscriber port
 * table exists there to punch a hole in at all). That's a real ceiling on
 * success rate, not a bug - callers must have their own further fallback
 * for when this fails.
 */
final class HolePuncher {

    private HolePuncher() {
    }

    record ReflectedAddress(String ip, int port) {
    }

    // Binds a local socket to localPort and speaks a hand-rolled HTTP GET
    // /reflect to the coordinator from it - the address the coordinator
    // reports back is the external mapping for THIS SPECIFIC local port,
    // which is exactly what matters, since the actual punch attempt below
    // reuses this same port. Can't use the JDK's high-level HttpClient here
    // since it doesn't expose binding the request to a specific local port.
    static ReflectedAddress reflect(String coordinatorHostname, int coordinatorPort, int localPort) {
        try (Socket probe = new Socket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(localPort));
            probe.connect(new InetSocketAddress(coordinatorHostname, coordinatorPort), 5000);

            String request = "GET /reflect HTTP/1.1\r\nHost: " + coordinatorHostname + "\r\nConnection: close\r\n\r\n";
            probe.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            probe.getOutputStream().flush();

            ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int len;
            InputStream in = probe.getInputStream();
            while ((len = in.read(buf)) != -1) {
                responseBytes.write(buf, 0, len);
            }
            String response = responseBytes.toString(StandardCharsets.UTF_8);
            int bodyStart = response.indexOf("\r\n\r\n");
            if (bodyStart < 0) return null;
            String body = response.substring(bodyStart + 4);
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            return new ReflectedAddress(obj.get("ip").getAsString(), obj.get("port").getAsInt());
        } catch (Exception e) {
            System.out.println("[Campfire] Hole-punch reflect (local port " + localPort + ") failed: " + e.getMessage());
            return null;
        }
    }

    // Repeatedly attempts an outbound connect from localPort toward the
    // peer's reflected address until one succeeds or timeoutMs passes - the
    // standard "simultaneous open" TCP hole-punch technique: no accept()
    // involved on either side, just both peers hammering connect() at each
    // other until one attempt lands in the brief window after both routers
    // have punched their outbound pinhole. Each attempt needs a brand new
    // Socket - once a connect() attempt fails, the old one can't be reused -
    // rebound to the same local port every time so the NAT keeps mapping it
    // the same way throughout.
    static Socket punch(String remoteIp, int remotePort, int localPort, long timeoutMs, int attemptTimeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Socket socket = new Socket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(localPort));
                socket.connect(new InetSocketAddress(remoteIp, remotePort), attemptTimeoutMs);
                return socket;
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }
}
