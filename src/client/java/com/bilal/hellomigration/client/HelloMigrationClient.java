package com.bilal.hellomigration.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Session;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class HelloMigrationClient implements ClientModInitializer {

    private static final String COORDINATOR_HOST = "http://localhost:8080";
    private static final String COORDINATOR_WS = "ws://localhost:8080/ws";
    private static final String GROUP_ID = "dev-test-group"; // still hardcoded, not yet dynamic

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private WebSocket webSocket;
    private String playerId;
    private String playerName;

    // Tracks who the coordinator says is currently hosting, so when a
    // `migrate` message arrives we still know who the *previous* host was
    // (needed for the level.dat/playerdata swap) before overwriting it.
    private volatile String currentHostId;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> connectToCoordinator());
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
    }

    private void connectToCoordinator() {
        Session session = MinecraftClient.getInstance().getSession();
        this.playerName = session.getUsername();
        UUID uuid = session.getUuidOrNull();
        this.playerId = uuid != null ? uuid.toString() : UUID.randomUUID().toString();

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(COORDINATOR_WS), new CoordinatorListener())
                .thenAccept(ws -> System.out.println("[HelloMigration] Connected to coordinator"));
    }

    private void onServerStopping(MinecraftServer server) {
        if (!isManagedWorld(server)) return;
        if (webSocket != null) {
            webSocket.sendText("{\"type\":\"leaving\"}", true);
            System.out.println("[HelloMigration] Sent 'leaving' to coordinator");
        }
    }

    private void onServerStopped(MinecraftServer server) {
        if (!isManagedWorld(server)) return;
        try {
            Path worldSaveDir = server.getSavePath(WorldSavePath.ROOT);
            uploadSave(worldSaveDir);
        } catch (Exception e) {
            System.out.println("[HelloMigration] Failed during save upload: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Only treat this as a migration-relevant world if its folder name matches
    // the group we're coordinating. Stops the mod from uploading/leaving-triggering
    // on some unrelated singleplayer world you happen to have open.
    //
    // NOTE: WorldSavePath.ROOT resolves to "<world-dir>/." (a trailing "."
    // segment), so calling getFileName() on the raw path returns "." instead
    // of the real folder name. Normalizing the absolute path first collapses
    // that trailing "." away and gives the actual world folder name.
    private boolean isManagedWorld(MinecraftServer server) {
        Path worldSaveDir = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        String folderName = worldSaveDir.getFileName().toString();
        boolean managed = folderName.equals(GROUP_ID);
        if (!managed) {
            System.out.println("[HelloMigration] '" + folderName + "' isn't the managed group world (" + GROUP_ID + ") - ignoring.");
        }
        return managed;
    }

    // ---------- Upload path ----------

    private void uploadSave(Path worldSaveDir) {
        try {
            System.out.println("[HelloMigration] Zipping world save at " + worldSaveDir);
            byte[] zipBytes = zipDirectory(worldSaveDir);
            System.out.println("[HelloMigration] Zipped save, " + zipBytes.length + " bytes");

            String boundary = "----HelloMigrationBoundary" + System.currentTimeMillis();
            byte[] body = buildMultipartBody(boundary, "save", "save.zip", zipBytes);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COORDINATOR_HOST + "/groups/" + GROUP_ID + "/save"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[HelloMigration] Upload response (" + response.statusCode() + "): " + response.body());
        } catch (IOException | InterruptedException e) {
            System.out.println("[HelloMigration] Upload failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private byte[] zipDirectory(Path sourceDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Files.walk(sourceDir)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String entryName = sourceDir.relativize(path).toString().replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return baos.toByteArray();
    }

    private byte[] buildMultipartBody(String boundary, String fieldName, String fileName, byte[] fileBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: application/zip\r\n\r\n";
        baos.write(header.getBytes(StandardCharsets.UTF_8));
        baos.write(fileBytes);
        baos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return baos.toByteArray();
    }

    // ---------- Download path ----------

    private void downloadCurrentSave(String groupId, int saveVersion, String outgoingHostId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COORDINATOR_HOST + "/groups/" + groupId + "/save"))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            System.out.println("[HelloMigration] Download response: " + response.statusCode());

            if (response.statusCode() == 200) {
                Path gameDir = FabricLoader.getInstance().getGameDir();

                Path migrationDir = gameDir.resolve("migration");
                Files.createDirectories(migrationDir);
                Path backupZip = migrationDir.resolve("save-v" + saveVersion + ".zip");
                Files.write(backupZip, response.body());
                System.out.println("[HelloMigration] Saved downloaded world to " + backupZip);

                Path savesDir = gameDir.resolve("saves").resolve(groupId);
                extractZipToDirectory(response.body(), savesDir);
                System.out.println("[HelloMigration] Unpacked save into " + savesDir);

                swapPlayerData(savesDir, outgoingHostId, this.playerId);
            } else if (response.statusCode() == 404) {
                System.out.println("[HelloMigration] No save uploaded yet for group " + groupId);
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("[HelloMigration] Download failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void extractZipToDirectory(byte[] zipBytes, Path targetDir) throws IOException {
        if (Files.exists(targetDir)) {
            try (var walk = Files.walk(targetDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        }
        Files.createDirectories(targetDir);

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir)) {
                    continue; // zip-slip protection
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    // ---------- level.dat / playerdata swap (Milestone 6) ----------

    private void swapPlayerData(Path worldDir, String outgoingHostId, String incomingHostId) {
        try {
            File levelDatFile = worldDir.resolve("level.dat").toFile();
            if (!levelDatFile.exists()) {
                System.out.println("[HelloMigration] No level.dat found yet, skipping player-data swap.");
                return;
            }

            NbtCompound root = NbtIo.readCompressed(levelDatFile);
            NbtCompound data = root.getCompound("Data");

            Path playerDataDir = worldDir.resolve("playerdata");
            Files.createDirectories(playerDataDir);

            if (outgoingHostId != null && data.contains("Player")) {
                NbtCompound outgoingPlayerData = data.getCompound("Player");
                File outgoingFile = playerDataDir.resolve(outgoingHostId + ".dat").toFile();
                NbtIo.writeCompressed(outgoingPlayerData, outgoingFile);
                System.out.println("[HelloMigration] Preserved outgoing host's player data -> " + outgoingFile.getName());
            } else {
                System.out.println("[HelloMigration] No outgoing host data to preserve (first host of this save).");
            }

            File incomingFile = playerDataDir.resolve(incomingHostId + ".dat").toFile();
            if (incomingFile.exists()) {
                NbtCompound incomingPlayerData = NbtIo.readCompressed(incomingFile);
                data.put("Player", incomingPlayerData);
                System.out.println("[HelloMigration] Restored incoming host's own player data into level.dat.");
            } else {
                data.remove("Player");
                System.out.println("[HelloMigration] No prior data for incoming host - they'll spawn fresh.");
            }

            root.put("Data", data);
            NbtIo.writeCompressed(root, levelDatFile);
            System.out.println("[HelloMigration] Player-data swap complete.");
        } catch (IOException e) {
            System.out.println("[HelloMigration] Player-data swap failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---------- WebSocket listener ----------

    private class CoordinatorListener implements WebSocket.Listener {
        private final Gson gson = new Gson();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("groupId", GROUP_ID);
            hello.addProperty("playerId", playerId);
            hello.addProperty("playerName", playerName);
            ws.sendText(gson.toJson(hello), true);
            WebSocket.Listener.super.onOpen(ws);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(message);
            }
            ws.request(1);
            return null;
        }

        private void handleMessage(String message) {
            System.out.println("[HelloMigration] Received: " + message);
            JsonObject json;
            try {
                json = JsonParser.parseString(message).getAsJsonObject();
            } catch (Exception e) {
                System.out.println("[HelloMigration] Failed to parse message: " + message);
                return;
            }

            String type = json.has("type") ? json.get("type").getAsString() : null;
            if (type == null) return;

            switch (type) {
                case "state" -> currentHostId = (json.has("hostId") && !json.get("hostId").isJsonNull())
                        ? json.get("hostId").getAsString() : null;
                case "migrate" -> {
                    String previousHostId = currentHostId;
                    String newHostId = json.get("newHostId").getAsString();
                    int saveVersion = json.get("saveVersion").getAsInt();
                    currentHostId = newHostId;

                    if (newHostId.equals(playerId)) {
                        System.out.println("[HelloMigration] We are the new host, downloading save v" + saveVersion);
                        downloadCurrentSave(GROUP_ID, saveVersion, previousHostId);
                    }
                }
                case "error" -> System.out.println("[HelloMigration] Server error: " + json);
                default -> {
                    // host_confirmed / save_ready: not yet used by the mod
                }
            }
        }
    }
}