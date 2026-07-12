package com.bilal.campfire.client;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs only on the current host's mod.
 *
 * For every relay_open a friend sends, opens a real local socket to the
 * actual Minecraft server (the one just opened to LAN on HOST_LAN_PORT)
 * and pumps bytes both ways: friend -> coordinator -> here -> real server,
 * and real server -> here -> coordinator -> friend. Streams are keyed by
 * streamId so several friends can be relayed at once without their traffic
 * crossing.
 */
public class RelayHostMultiplexer {

    private static final class StreamEntry {
        final String fromPlayerId;
        final Socket socket;
        StreamEntry(String fromPlayerId, Socket socket) {
            this.fromPlayerId = fromPlayerId;
            this.socket = socket;
        }
    }

    // Generous headroom above any real campfire (the coordinator's own member
    // cap - see MAX_CONCURRENT_MEMBERS in server.js/group.js - tops out at 40,
    // and a legitimate player opens at most a handful of streams), not a
    // meaningful limit on real play. Without it, nothing stopped a
    // misbehaving/compromised coordinator (or a bug replaying relay_open)
    // from driving unbounded thread + socket creation on the host.
    private static final int MAX_CONCURRENT_STREAMS = 128;

    private final int lanPort;
    private final Consumer<JsonObject> sender;
    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();

    public RelayHostMultiplexer(int lanPort, Consumer<JsonObject> sender) {
        this.lanPort = lanPort;
        this.sender = sender;
    }

    /**
     * A friend wants to open a stream toward us. Called directly from
     * CampfireClient's handleMessage on the "relay_open" case, which runs
     * on the JDK WebSocket listener's onText - so this method itself must
     * never block. socket.connect(...) below can take up to its own 5s
     * timeout (routine, not just theoretical: it fires every time a friend
     * relay-connects before the host's own LAN server has finished opening
     * - see the comment in the catch branch), and a burst of several
     * friends relay-joining at once would otherwise wedge coordinator
     * message delivery - including heartbeat replies - for N x 5s, exactly
     * the failure mode "never block the coordinator websocket thread" (see
     * src/CLAUDE.md) warns causes the coordinator to reap the client as
     * dead mid-operation. The actual connect - and everything after it -
     * now runs on its own spawned thread instead.
     */
    public void openStream(String streamId, String fromPlayerId) {
        // Checked here, before spawning anything - a misbehaving/compromised
        // coordinator flooding far more than MAX_CONCURRENT_STREAMS worth of
        // relay_open would otherwise still cause unbounded thread creation
        // (the old check only ran inside the spawned thread's body), since
        // each one lives just long enough to hit the cap and exit. That's a
        // real, if transient, resource-exhaustion vector on the host's own
        // machine.
        if (streams.size() >= MAX_CONCURRENT_STREAMS) {
            System.out.println("[Campfire] Refusing relay stream " + streamId + " - already at the " + MAX_CONCURRENT_STREAMS + "-stream cap.");
            sendClose(streamId, fromPlayerId);
            return;
        }
        Thread openThread = new Thread(() -> openStreamBlocking(streamId, fromPlayerId), "relay-host-open-" + streamId);
        openThread.setDaemon(true);
        openThread.start();
    }

    private void openStreamBlocking(String streamId, String fromPlayerId) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", lanPort), 5000);
        } catch (IOException e) {
            System.out.println("[Campfire] Could not connect relay stream " + streamId
                    + " to local server on port " + lanPort + " (is it open to LAN yet?): " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            sendClose(streamId, fromPlayerId);
            return;
        }

        // putIfAbsent, not put: a second relay_open for a streamId that's still
        // in use (duplicate/replayed message) used to silently overwrite the
        // existing StreamEntry - leaking the original socket (nothing ever
        // closes it) and orphaning its pump thread, permanently blocked in its
        // own read() until the real LAN server side happens to error out.
        StreamEntry existing = streams.putIfAbsent(streamId, new StreamEntry(fromPlayerId, socket));
        if (existing != null) {
            System.out.println("[Campfire] Relay stream " + streamId + " already open - closing duplicate connection.");
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        System.out.println("[Campfire] Opened relay stream " + streamId + " for " + fromPlayerId);

        pumpServerToRelay(streamId, fromPlayerId, socket);
    }

    private void pumpServerToRelay(String streamId, String toPlayerId, Socket socket) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = socket.getInputStream();
            int len;
            while ((len = in.read(buffer)) != -1) {
                JsonObject data = new JsonObject();
                data.addProperty("type", "relay_data");
                data.addProperty("streamId", streamId);
                data.addProperty("toPlayerId", toPlayerId);
                data.addProperty("data", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, len)));
                sender.accept(data);
            }
        } catch (IOException e) {
            // real server closed the connection or errored - fall through to close
        } finally {
            // Only the thread that actually wins the removal race sends the
            // close - shutdownAll() closing this same socket concurrently
            // (routine on host quit: it closes every stream's socket right
            // as the real server going down makes every one of these reads
            // fail at once) used to race a second, independent close attempt
            // here. closeLocal() now reports whether IT did the removal, so
            // exactly one relay_close goes out per stream instead of a
            // duplicate (or, when the loser's lookup found nothing left to
            // report a fromPlayerId for, a malformed one with no toPlayerId
            // that the coordinator silently drops).
            if (closeLocal(streamId)) {
                sendClose(streamId, toPlayerId);
            }
        }
    }

    /** Bytes arrived from a friend over the WebSocket - write them into the real local server's socket. */
    public void feedData(String streamId, byte[] payload) {
        StreamEntry entry = streams.get(streamId);
        if (entry == null) return;
        try {
            OutputStream out = entry.socket.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            System.out.println("[Campfire] Failed writing relay data to host stream " + streamId + ": " + e.getMessage());
            if (closeLocal(streamId)) {
                sendClose(streamId, entry.fromPlayerId);
            }
        }
    }

    /** Friend closed their end - close our socket to the real server too. */
    public void closeStream(String streamId) {
        closeLocal(streamId);
    }

    private void sendClose(String streamId, String toPlayerId) {
        JsonObject close = new JsonObject();
        close.addProperty("type", "relay_close");
        close.addProperty("streamId", streamId);
        if (toPlayerId != null) {
            close.addProperty("toPlayerId", toPlayerId);
        }
        sender.accept(close);
    }

    // Returns true only for whichever caller actually removed the entry -
    // callers use that to decide whether THEY'RE the one responsible for
    // announcing the close, so two threads racing to tear down the same
    // stream (shutdownAll() vs. this stream's own pump thread noticing its
    // socket just got closed) never both send, and never both skip.
    private boolean closeLocal(String streamId) {
        StreamEntry entry = streams.remove(streamId);
        if (entry == null) return false;
        try {
            entry.socket.close();
        } catch (IOException ignored) {
        }
        return true;
    }

    /** We stopped being host (or the server stopped) - tear every stream down. */
    public void shutdownAll() {
        for (String streamId : streams.keySet()) {
            StreamEntry entry = streams.get(streamId);
            String owner = entry != null ? entry.fromPlayerId : null;
            if (closeLocal(streamId)) {
                sendClose(streamId, owner);
            }
        }
    }
}