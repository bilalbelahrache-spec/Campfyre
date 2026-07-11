package com.bilal.campfire.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * TCP hole-punching helper. Learning our own externally-visible address
 * (the "reflect" step) is tried two ways, in order:
 *
 * 1. The coordinator's own GET /reflect route (a self-hosted STUN-equivalent,
 *    see server.js) - correct and protocol-matched (TCP-to-TCP) when the
 *    coordinator is a bare self-hosted process with nothing in front of it,
 *    since the address it sees IS the real external mapping for this exact
 *    local port. Speaking plain HTTP to it by hand (over a raw Socket we
 *    control) means it rides the same port/forward the coordinator's
 *    WebSocket already uses - no second port needs forwarding on top of it.
 * 2. A public STUN server (RFC 5389), if (1) came back empty. Every
 *    coordinator this mod ships against by default now sits behind a proxy
 *    (Cloudflare Workers, and Render before it) that terminates the client's
 *    TCP connection itself - the port such a proxy sees is its own, never
 *    the original caller's - so GET /reflect behind either one can only ever
 *    honestly answer with a null port. STUN is UDP-only, and a NAT's UDP
 *    mapping for a given local port is, strictly, a separate table entry
 *    from that same port's TCP mapping - but most consumer/home routers
 *    allocate mappings with simple port preservation independent of
 *    protocol, so the UDP-learned port is usually also the TCP one; where
 *    that's not true, the NAT is almost always "symmetric" anyway, which
 *    breaks hole-punching regardless of how the address was learned. Either
 *    way this can only improve on a coordinator whose own reflect is a flat,
 *    unconditional null.
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

    private static final String[] STUN_SERVERS = {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun.cloudflare.com:3478",
    };
    private static final int STUN_TIMEOUT_MS = 3000;
    private static final int STUN_MAGIC_COOKIE = 0x2112A442;

    static ReflectedAddress reflect(String coordinatorHostname, int coordinatorPort, int localPort) {
        ReflectedAddress viaCoordinator = reflectViaCoordinator(coordinatorHostname, coordinatorPort, localPort);
        if (viaCoordinator != null) return viaCoordinator;

        System.out.println("[Campfire] Coordinator reflect unavailable (proxied coordinator, or unreachable) - trying public STUN servers instead.");
        for (String server : STUN_SERVERS) {
            ReflectedAddress viaStun = reflectViaStun(server, localPort);
            if (viaStun != null) return viaStun;
        }
        System.out.println("[Campfire] Hole-punch reflect (local port " + localPort + ") failed against the coordinator and every public STUN server.");
        return null;
    }

    // Binds a local socket to localPort and speaks a hand-rolled HTTP GET
    // /reflect to the coordinator from it - the address the coordinator
    // reports back is the external mapping for THIS SPECIFIC local port,
    // which is exactly what matters, since the actual punch attempt below
    // reuses this same port. Can't use the JDK's high-level HttpClient here
    // since it doesn't expose binding the request to a specific local port.
    // Returns null (rather than throwing) both on any I/O failure AND when
    // the coordinator honestly reports {port: null} (any proxied deployment)
    // - either way the caller falls through to the STUN tier.
    private static ReflectedAddress reflectViaCoordinator(String coordinatorHostname, int coordinatorPort, int localPort) {
        try (Socket probe = new Socket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(localPort));
            probe.connect(new InetSocketAddress(coordinatorHostname, coordinatorPort), 5000);
            // connect() only bounds the handshake - nothing previously bounded the
            // response read below. A coordinator that accepts but never finishes
            // replying (hung proxy, half-open connection) left this blocked in
            // read() forever, holding localPort hostage - every future hole-punch
            // attempt on this client (host and friend side share the same fixed
            // PUNCH_LOCAL_PORT) would then fail to bind and silently skip straight
            // past this whole connectivity tier.
            probe.setSoTimeout(5000);

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
            if (obj.get("ip") == null || obj.get("port") == null
                    || obj.get("ip").isJsonNull() || obj.get("port").isJsonNull()) {
                return null;
            }
            return new ReflectedAddress(obj.get("ip").getAsString(), obj.get("port").getAsInt());
        } catch (Exception e) {
            System.out.println("[Campfire] Coordinator reflect (local port " + localPort + ") failed: " + e.getMessage());
            return null;
        }
    }

    // RFC 5389 STUN Binding Request over UDP, bound to the same localPort
    // the caller is about to reuse for the actual TCP punch attempt (see the
    // protocol-mismatch caveat in the class doc above).
    private static ReflectedAddress reflectViaStun(String hostPort, int localPort) {
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        byte[] transactionId = new byte[12];
        new SecureRandom().nextBytes(transactionId);

        byte[] request = new byte[20];
        request[0] = 0x00;
        request[1] = 0x01; // Binding Request
        request[2] = 0x00;
        request[3] = 0x00; // message length: no attributes
        request[4] = (byte) 0x21;
        request[5] = 0x12;
        request[6] = (byte) 0xA4;
        request[7] = 0x42; // magic cookie
        System.arraycopy(transactionId, 0, request, 8, 12);

        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(localPort));
            socket.setSoTimeout(STUN_TIMEOUT_MS);

            socket.send(new DatagramPacket(request, request.length, new InetSocketAddress(host, port)));

            byte[] responseBuf = new byte[512];
            DatagramPacket responsePacket = new DatagramPacket(responseBuf, responseBuf.length);
            socket.receive(responsePacket);

            return parseStunBindingResponse(responseBuf, responsePacket.getLength(), transactionId);
        } catch (IOException e) {
            System.out.println("[Campfire] STUN reflect via " + hostPort + " (local port " + localPort + ") failed: " + e.getMessage());
            return null;
        }
    }

    // Parses just enough of a Binding Success Response (0x0101) to pull out
    // XOR-MAPPED-ADDRESS (RFC 5389, preferred) or, failing that,
    // MAPPED-ADDRESS (RFC 3489, some older servers only send this). IPv6
    // responses are skipped - the rest of the hole-punch path (address
    // strings split on ':') is IPv4-only anyway.
    private static ReflectedAddress parseStunBindingResponse(byte[] buf, int length, byte[] expectedTransactionId) {
        if (length < 20) return null;
        int messageType = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
        if (messageType != 0x0101) return null;
        for (int i = 0; i < 12; i++) {
            if (buf[8 + i] != expectedTransactionId[i]) return null;
        }

        int messageLength = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        int offset = 20;
        int end = Math.min(length, 20 + messageLength);
        ReflectedAddress mappedFallback = null;

        while (offset + 4 <= end) {
            int attrType = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
            int attrLength = ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
            int valueStart = offset + 4;
            if (valueStart + attrLength > end) break;

            if (attrType == 0x0020 && attrLength >= 8 && (buf[valueStart + 1] & 0xFF) == 0x01) { // XOR-MAPPED-ADDRESS, IPv4
                int xPort = (((buf[valueStart + 2] & 0xFF) << 8) | (buf[valueStart + 3] & 0xFF)) ^ (STUN_MAGIC_COOKIE >>> 16);
                int[] addr = new int[4];
                for (int i = 0; i < 4; i++) {
                    int cookieByte = (STUN_MAGIC_COOKIE >>> (24 - 8 * i)) & 0xFF;
                    addr[i] = (buf[valueStart + 4 + i] & 0xFF) ^ cookieByte;
                }
                return new ReflectedAddress(addr[0] + "." + addr[1] + "." + addr[2] + "." + addr[3], xPort);
            } else if (attrType == 0x0001 && attrLength >= 8 && mappedFallback == null
                    && (buf[valueStart + 1] & 0xFF) == 0x01) { // MAPPED-ADDRESS, IPv4
                int mPort = ((buf[valueStart + 2] & 0xFF) << 8) | (buf[valueStart + 3] & 0xFF);
                String ip = (buf[valueStart + 4] & 0xFF) + "." + (buf[valueStart + 5] & 0xFF) + "."
                        + (buf[valueStart + 6] & 0xFF) + "." + (buf[valueStart + 7] & 0xFF);
                mappedFallback = new ReflectedAddress(ip, mPort);
            }

            offset = valueStart + attrLength;
            if (attrLength % 4 != 0) offset += 4 - (attrLength % 4); // attributes are padded to a 4-byte boundary
        }
        return mappedFallback;
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
