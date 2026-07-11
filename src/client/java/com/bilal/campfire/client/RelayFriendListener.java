package com.bilal.campfire.client;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs on every player's mod EXCEPT while they're the current host.
 *
 * Binds a local TCP listener (127.0.0.1:<port>) that Minecraft's own
 * "Direct Connect" screen points at. Whatever raw bytes vanilla Minecraft
 * writes to that local socket get wrapped as relay_open/relay_data
 * messages and shipped over the existing coordinator WebSocket; whatever
 * comes back over the WebSocket gets written back into the local socket,
 * so to the Minecraft client this looks exactly like talking to a normal
 * local server.
 */
public class RelayFriendListener {

    private final int port;
    private final Consumer<JsonObject> sender;
    private final ConcurrentHashMap<String, Socket> streams = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    // Distinct from `running`, which only ever flips true once a bind
    // succeeds. Without this, a stop() landing while runAcceptLoop() is
    // mid-retry (plausible on a rapid host/friend flip - see the loop's own
    // comment below) went unnoticed: the loop kept retrying, could still
    // bind successfully afterward, and set running = true again - an
    // orphaned listener nothing holds a reference to anymore, competing for
    // the port against whatever replacement listener stop()'s caller starts
    // next.
    private volatile boolean stopRequested = false;

    public RelayFriendListener(int port, Consumer<JsonObject> sender) {
        this.port = port;
        this.sender = sender;
    }

    public void start() {
        Thread acceptThread = new Thread(this::runAcceptLoop, "relay-friend-listener");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void runAcceptLoop() {
        // The previous listener on this same port (from the last time we were
        // a "friend") may have been stop()'d only moments ago, in the same
        // synchronous updateHostingMode() call that started this one. Closing
        // a ServerSocket does not guarantee the OS releases the port
        // instantly - on Windows especially, there can be a short delay
        // before a fresh bind to the same port succeeds. Without a retry
        // here, that race loses silently: this catch block logs one line and
        // returns, `running` never becomes true, and Direct Connect to
        // 127.0.0.1:<port> then fails with a plain "Connection refused",
        // because nothing is listening and nothing ever retries.
        final int maxAttempts = 10;
        final long retryDelayMs = 250;
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (stopRequested) {
                System.out.println("[Campfire] Relay listener bind on port " + port + " abandoned - stop() was called mid-retry.");
                return;
            }
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
                if (stopRequested) {
                    // Lost the race the other way: stop() fired between the check
                    // above and this bind succeeding. Don't resurrect a listener.
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                    return;
                }
                serverSocket = socket;
                running = true;
                System.out.println("[Campfire] Relay listener ready on 127.0.0.1:" + port + " - point Direct Connect here.");
                break;
            } catch (IOException e) {
                lastFailure = e;
                System.out.println("[Campfire] Relay listener bind attempt " + attempt + "/" + maxAttempts
                        + " on port " + port + " failed (" + e.getMessage() + ") - retrying in " + retryDelayMs + "ms.");
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!running) {
            System.out.println("[Campfire] Giving up binding relay listener on port " + port
                    + " after " + maxAttempts + " attempts: "
                    + (lastFailure != null ? lastFailure.getMessage() : "unknown error")
                    + ". Direct Connect to 127.0.0.1:" + port + " will show Connection refused until this is resolved"
                    + " (e.g. a full game/coordinator relaunch, or another process holding the port).");
            return;
        }

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                onAccepted(clientSocket);
            } catch (IOException e) {
                if (running) {
                    System.out.println("[Campfire] Relay listener accept() failed: " + e.getMessage());
                }
                // if !running, this is just the socket closing on shutdown - expected
            }
        }
    }

    private void onAccepted(Socket clientSocket) {
        String streamId = UUID.randomUUID().toString();
        streams.put(streamId, clientSocket);
        System.out.println("[Campfire] Local Minecraft client connected - opened relay stream " + streamId);

        JsonObject open = new JsonObject();
        open.addProperty("type", "relay_open");
        open.addProperty("streamId", streamId);
        sender.accept(open);

        Thread readerThread = new Thread(() -> pumpSocketToRelay(streamId, clientSocket), "relay-stream-" + streamId);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void pumpSocketToRelay(String streamId, Socket clientSocket) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = clientSocket.getInputStream();
            int len;
            while ((len = in.read(buffer)) != -1) {
                JsonObject data = new JsonObject();
                data.addProperty("type", "relay_data");
                data.addProperty("streamId", streamId);
                data.addProperty("data", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, len)));
                sender.accept(data);
            }
        } catch (IOException e) {
            // local Minecraft client disconnected or errored - fall through to close
        } finally {
            // Only send relay_close if THIS call actually performed the removal -
            // closeStream()/abortStream()/closeAllStreams() racing in concurrently
            // (e.g. feedData's write failing right as the local socket also stops
            // producing reads) used to make both sides call closeLocal() and this
            // finally block send relay_close unconditionally regardless, producing
            // a duplicate send for the same stream. Same bug class already fixed
            // in the sibling RelayHostMultiplexer.
            if (closeLocal(streamId)) {
                JsonObject close = new JsonObject();
                close.addProperty("type", "relay_close");
                close.addProperty("streamId", streamId);
                sender.accept(close);
            }
        }
    }

    /** Bytes arrived from the host over the WebSocket - write them into the local Minecraft client's socket. */
    public void feedData(String streamId, byte[] payload) {
        Socket socket = streams.get(streamId);
        if (socket == null) return;
        try {
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            System.out.println("[Campfire] Failed writing relay data to local stream " + streamId + ": " + e.getMessage());
            closeStream(streamId);
        }
    }

    /** Host closed this stream (e.g. the player disconnected server-side) - close our end too. */
    public void closeStream(String streamId) {
        closeLocal(streamId);
    }

    /** Coordinator says there's no host to talk to - abort before we ever get going. */
    public void abortStream(String streamId) {
        closeLocal(streamId);
    }

    /** Host disconnected entirely (relay_reset) - tear down every open stream. */
    public void closeAllStreams() {
        for (String streamId : streams.keySet()) {
            closeLocal(streamId);
        }
    }

    /** Returns whether THIS call performed the actual removal (see pumpSocketToRelay's finally). */
    private boolean closeLocal(String streamId) {
        Socket socket = streams.remove(streamId);
        if (socket == null) return false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        return true;
    }

    public void stop() {
        stopRequested = true;
        running = false;
        closeAllStreams();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }
}