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

    private final int lanPort;
    private final Consumer<JsonObject> sender;
    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();

    public RelayHostMultiplexer(int lanPort, Consumer<JsonObject> sender) {
        this.lanPort = lanPort;
        this.sender = sender;
    }

    /** A friend wants to open a stream toward us. */
    public void openStream(String streamId, String fromPlayerId) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", lanPort), 5000);
        } catch (IOException e) {
            System.out.println("[Campfire] Could not connect relay stream " + streamId
                    + " to local server on port " + lanPort + " (is it open to LAN yet?): " + e.getMessage());
            sendClose(streamId, fromPlayerId);
            return;
        }

        streams.put(streamId, new StreamEntry(fromPlayerId, socket));
        System.out.println("[Campfire] Opened relay stream " + streamId + " for " + fromPlayerId);

        Thread readerThread = new Thread(() -> pumpServerToRelay(streamId, fromPlayerId, socket), "relay-host-" + streamId);
        readerThread.setDaemon(true);
        readerThread.start();
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
            closeLocal(streamId);
            sendClose(streamId, toPlayerId);
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
            String owner = entry.fromPlayerId;
            closeLocal(streamId);
            sendClose(streamId, owner);
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

    private void closeLocal(String streamId) {
        StreamEntry entry = streams.remove(streamId);
        if (entry != null) {
            try {
                entry.socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** We stopped being host (or the server stopped) - tear every stream down. */
    public void shutdownAll() {
        for (String streamId : streams.keySet()) {
            StreamEntry entry = streams.get(streamId);
            String owner = entry != null ? entry.fromPlayerId : null;
            closeLocal(streamId);
            sendClose(streamId, owner);
        }
    }
}