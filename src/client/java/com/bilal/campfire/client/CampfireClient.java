package com.bilal.campfire.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.Session;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CampfireClient implements ClientModInitializer {

    // Set from the (Fabric-invoked, no-arg) constructor so TitleScreenMixin -
    // which lives in a different package and has no other way to reach this
    // mod instance - can look up the current group/status when the docked
    // title-screen button is clicked.
    private static CampfireClient instance;

    public static CampfireClient getInstance() {
        return instance;
    }

    public CampfireClient() {
        instance = this;
    }

    private static final String CONFIG_FILE_NAME = "campfire.json";

    // The community coordinator every fresh install points at until/unless a
    // player repoints it (self-hosted instance, or an invite carrying a
    // different address). Ships hardcoded rather than as a build property so
    // a plain downloaded jar always has a working default with zero setup.
    private static final String DEFAULT_COORDINATOR_HOST = "https://campfire-coordinator.bilal-belahrache.workers.dev";

    private static final int RELAY_LISTEN_PORT = 25565;
    private static final int HOST_LAN_PORT = 25566;

    // Hole-punch tier (tried when UPnP doesn't give us a direct address).
    // PUNCH_LOCAL_PORT is the fixed local port both host and friend bind for
    // their reflector probe AND their punch attempt - it has to be the same
    // port for both steps, since the NAT mapping being punched is tied to
    // that specific local port. PUNCH_BRIDGE_PORT is friend-only: once a
    // punch succeeds, Minecraft's Direct Connect needs somewhere local to
    // point at (RELAY_LISTEN_PORT is already taken by RelayFriendListener),
    // so this is a fresh one-shot loopback listener that just splices
    // straight into the punched socket.
    private static final int PUNCH_LOCAL_PORT = 25567;
    private static final int PUNCH_BRIDGE_PORT = 25568;
    private static final long HOLE_PUNCH_TIMEOUT_MS = 8000;
    private static final int HOLE_PUNCH_ATTEMPT_TIMEOUT_MS = 300;
    // Just a coordinator round trip (friend -> host -> friend), not another
    // network-discovery step - the host replies as soon as its own quick
    // reflect() call resolves, so this only needs to cover that plus normal
    // WebSocket latency, not a full punch attempt's worth of time.
    private static final long PUNCH_CANDIDATE_REPLY_TIMEOUT_MS = 5000;

    static {
        // The JDK HttpClient pools keep-alive connections for 20 minutes by
        // default - far longer than a home router/NAT keeps an idle mapping
        // alive. A request that reuses a silently-dead pooled connection
        // either fails instantly ("HTTP/1.1 header parser received no
        // bytes") or, worse, hangs with no error until the request timeout
        // (a save download sat like that for 4+ minutes in a real test).
        // Capping idle reuse at 10s makes the pool useful within a burst of
        // requests and harmless across the quiet minutes between them.
        if (System.getProperty("jdk.httpclient.keepalive.timeout") == null) {
            System.setProperty("jdk.httpclient.keepalive.timeout", "10");
        }
    }

    // connectTimeout only bounds the TCP handshake - it does nothing for a
    // connection that opens fine but then goes silent (a half-open socket
    // after the coordinator hiccups mid-session, or a network path that
    // drops packets without ever sending a RST/FIN). That silent-hang case
    // is real: it's what left a player staring at a disabled "Getting the
    // world ready..." button for minutes with no error, ever, because
    // nothing here had any upper bound at all. Every individual request
    // below now also carries its own HttpRequest.timeout() for exactly that
    // reason - this connectTimeout alone is not enough on its own.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // Save transfers get a brand-new client (= a brand-new connection) per
    // attempt instead of the shared pooled one above: a stale pooled socket
    // costs a small signaling call one quick retry, but it costs a
    // multi-hundred-MB transfer either a silent multi-minute hang or a full
    // restart - not worth the pooling win. See the static block above for
    // the observed failure modes.
    private static HttpClient newTransferClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }
    private final Gson gson = new Gson();

    // Loaded from config at startup (see loadConfig()) - defaults match the
    // values that used to be hardcoded here, so an untouched config file
    // behaves exactly like before. These flat fields always describe the
    // ACTIVE campfire; the full remembered list lives in `campfires`.
    private String coordinatorHost;
    private String coordinatorWs;
    private String groupId;

    // Every campfire this player belongs to, not just the active one - a
    // player can be in several friend groups, each with its own world and
    // possibly its own coordinator. CopyOnWrite because the list screen
    // iterates it on the render thread while joins/mints/leaves mutate it.
    private final java.util.List<CampfireEntry> campfires = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Display name of the active campfire's world ("Our Campfire"), as best
    // we know it: set when this player creates the world, refreshed from
    // level.dat whenever a download lands. Purely cosmetic - the list screen
    // labels campfires with it instead of bare invite codes.
    private volatile String activeWorldName;

    // One remembered campfire in campfire.json. groupId doubles as the world
    // folder name (isManagedWorld keys on it); coordinatorHost rides along
    // because different groups can live on different coordinators.
    static class CampfireEntry {
        String groupId = "";
        String coordinatorHost = "";
        String worldName = "";
        int localSaveVersion = 0;
    }

    private WebSocket webSocket;
    private String playerId;
    private String playerName;

    // The JDK WebSocket client allows only one sendText() in flight at a time -
    // a second call before the first's CompletableFuture completes fails that
    // future with IllegalStateException, and sendJson() historically discarded
    // that future, so the message was silently dropped. Relay traffic is the
    // one thing that calls sendJson() in a tight loop (once per ~8KB read off
    // a real socket), fast enough to violate that one-at-a-time rule over a
    // real network round trip - dropping a mid-stream chunk desyncs the
    // receiver's reassembled byte stream, which surfaces as a zlib "incorrect
    // header check" the moment Minecraft's post-login compressed packets start
    // flowing. Queueing and draining one send at a time fixes it for every
    // caller, not just relay.
    private final ConcurrentLinkedQueue<String> sendQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean sendInFlight = new AtomicBoolean(false);

    // Inbox for the WebSocket-transport save transfer (see
    // uploadSaveViaWebSocket/downloadSaveViaWebSocket): a transfer in
    // progress waits on this synchronously from its own background thread,
    // since it's a real request/response conversation (begin -> parts ->
    // commit), not fire-and-forget. Cleared at the start of every new
    // attempt so a stray late message from an abandoned/timed-out previous
    // attempt can't be mistaken for part of the current one.
    private final BlockingQueue<JsonObject> saveTransferInbox = new LinkedBlockingQueue<>();

    private volatile String currentHostId;

    // ---------- Live status the GUI renders (status screen, HUD, docked button dot) ----------

    // Public because TitleScreenMixin (mixin subpackage - package-private
    // doesn't reach it) colors the docked button's status dot from this.
    public enum CoordinatorStatus { NOT_CONFIGURED, DISCONNECTED, CONNECTING, CONNECTED }

    // How this client's live game traffic actually reaches the host - set by
    // whichever connect path wins, shown on the in-game HUD badge so players
    // can tell a fast direct link from the relay of last resort at a glance.
    enum ConnectionTier { DIRECT, PUNCHED, RELAY }

    // One member of the group, straight from the coordinator's live 'state'
    // roster. Order follows the hosting queue: members.get(0) is the current
    // host (or next in line), so screens can show "next up" naturally.
    // modMismatch is true when this member's mod list hash differs from our
    // own (never true for "you" - comparing yourself to yourself is
    // meaningless); modCount is -1 when the coordinator/that client is too
    // old to report one at all, so screens can tell "known to differ" from
    // "nothing to compare".
    record GroupMember(String id, String name, boolean host, boolean you, boolean modMismatch, int modCount) {
    }

    // A group member the coordinator remembers by name but who isn't online
    // right now - shown greyed out on the status screen so the group feels
    // like a persistent circle of friends, not just whoever's connected this
    // second. lastSeenMs is 0 when the coordinator doesn't know (it only
    // tracks departures since its own start).
    record AwayMember(String name, long lastSeenMs) {
    }

    // A fingerprint of every mod actually loaded (id@version, sorted so load
    // order can't change it), hashed once at class-init and sent with every
    // 'hello'. Campfire's host role rotates between EVERY player, unlike a
    // normal modded server where one machine's mod list is the only one that
    // matters forever - here, any mismatch will eventually land on someone's
    // machine as host, so catching it before that (in the roster UI) instead
    // of via a failed connection or a silently corrupted save is the whole
    // point. Computed once since a running client's mod set can't change
    // mid-session; MOD_LIST_HASH is null if hashing failed for any reason
    // (mismatch detection just becomes unavailable that session, never fatal).
    private static final String MOD_LIST_HASH;
    private static final int MOD_COUNT;
    static {
        String hash;
        int count;
        try {
            java.util.List<String> entries = new java.util.ArrayList<>();
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                entries.add(mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString());
            }
            count = entries.size();
            java.util.Collections.sort(entries);
            String joined = String.join("\n", entries);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digestBytes.length * 2);
            for (byte b : digestBytes) hex.append(String.format("%02x", b));
            hash = hex.toString();
        } catch (Exception e) {
            System.out.println("[Campfire] Failed computing mod list hash - mismatch detection unavailable this session: " + e.getMessage());
            hash = null;
            count = -1;
        }
        MOD_LIST_HASH = hash;
        MOD_COUNT = count;
    }

    private volatile CoordinatorStatus coordinatorStatus = CoordinatorStatus.NOT_CONFIGURED;
    private volatile java.util.List<GroupMember> members = java.util.List.of();
    private volatile java.util.List<AwayMember> awayMembers = java.util.List.of();
    private volatile int knownSaveVersion = 0;
    private volatile ConnectionTier connectionTier;

    // Which save generation our LOCAL copy of the world corresponds to -
    // persisted in campfire.json (unlike knownSaveVersion, which is whatever
    // the coordinator last broadcast). Updated after every successful
    // download and upload. "Open the World" compares the two: equal means
    // our copy IS the group's latest and opens instantly with no network
    // round trip at all; anything else means download-before-open, so a host
    // can never serve friends a stale world just because their own machine
    // happened to have an old copy lying around.
    private volatile int localSaveVersion = 0;

    // True while "Open the World" is fetching the latest save in the
    // background - the status screen shows a disabled "Getting the world
    // ready..." primary so the button can't be double-clicked.
    private volatile boolean preparingWorld = false;

    // Whether the player is currently inside the shared world (hosting or as
    // a guest), plus when they last left it. A mid-session host handoff
    // ('migrate' while we were just playing) should download + reopen the
    // world automatically - the player is actively trying to keep playing.
    // The same message arriving while we're idling in menus must NOT hijack
    // the game; it just makes the status screen offer "Open the World".
    private volatile boolean inSharedSession = false;
    private volatile long lastSharedSessionEndMs = 0;
    private static final long ACTIVE_HANDOFF_WINDOW_MS = 90_000;

    // When an automatic (mid-session) handoff last started on this machine.
    // The coordinator truthfully reports "no host" between our 'migrate' and
    // our world actually opening (im_hosting), and without this the state
    // broadcast in that gap toasts "You're next up to host" at a player
    // whose world is already opening itself.
    private volatile long lastActiveHandoffMs = 0;

    // Set (friend side) when a mid-session handoff kicks us out of the world
    // through no choice of our own - it's what entitles the eventual
    // host_confirmed to reconnect us automatically even if the new host's
    // download takes minutes. Absent this, host_confirmed never yanks anyone
    // anywhere. Cleared once consumed, once we're in a world again, or after
    // AWAITING_HANDOFF_MAX_MS (the new host may just never open the world).
    private volatile long awaitingHandoffSinceMs = 0;
    private static final long AWAITING_HANDOFF_MAX_MS = 10 * 60_000;

    // False until the first full roster snapshot after (re)connecting has
    // been absorbed - that snapshot lists everyone already there, and
    // toasting "X pulled up a seat" five times for people who were sitting
    // around the fire before we even arrived would be pure noise. Only
    // CHANGES after that first snapshot get announced.
    private volatile boolean rosterPrimed = false;
    // Tracks the "you're next in line to host" state across roster updates
    // so the toast fires exactly once per transition into it, not on every
    // rebroadcast while it stays true.
    private volatile boolean wasNextUp = false;

    // Set by leaveGroup() right before it aborts the socket, so the
    // disconnect handler knows not to schedule a reconnect for a socket we
    // closed on purpose.
    private volatile boolean intentionalDisconnect = false;
    private final java.util.concurrent.atomic.AtomicBoolean reconnectPending = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long RECONNECT_DELAY_MS = 5000;

    // The current host's UPnP-mapped address (ip:port), as reported by the
    // coordinator - null until UPnP resolves (if it ever does). Only
    // meaningful on a friend's client; the host doesn't consult its own
    // address. Reset to null any time the host changes, so a stale address
    // from a PREVIOUS host is never mistaken for the current one.
    private volatile String hostDirectAddress;

    // True while connectToNewHost() is waiting out DIRECT_ADDRESS_WAIT_MS
    // for a host_direct_address broadcast before giving up and falling back
    // to the relay. Lets a direct address that arrives just barely in time
    // preempt that fallback instead of racing it.
    private volatile boolean waitingForDirectAddress = false;

    // Bare hostname/port of the coordinator, derived from coordinatorHost in
    // loadConfig(). Used only for hole-punch reflect() calls (see
    // attemptHolePunch()/handleIncomingPunchCandidate()) - HolePuncher needs
    // to bind its probe socket to a specific local port before connecting,
    // which the JDK's high-level HttpClient (used everywhere else in this
    // file) doesn't support, so this one path speaks HTTP by hand instead.
    private String coordinatorBareHost;
    private int coordinatorPort;

    // Completed by the "punch_candidate" message handler when we're a friend
    // waiting on the host's reflected address - lets attemptHolePunch() block
    // (on its own background thread) until that reply arrives instead of
    // polling for it.
    private volatile CompletableFuture<String> pendingPunchCandidate;
    // UPnP discovery (GatewayDiscover's SSDP search + SOAP calls) genuinely
    // takes several seconds even on the negative/no-gateway path - measured
    // ~9s end-to-end in testing. A shorter wait here would make the friend
    // give up and fall back to the relay before the host's mapping attempt
    // could ever finish, defeating the whole point of trying direct first.
    private static final long DIRECT_ADDRESS_WAIT_MS = 12000;

    // Set right before a manual "Join a Campfire" connect, consumed by the
    // first 'state' message that comes back - lets us react once to that
    // specific join (auto-connect through the relay if someone's already
    // hosting) without re-triggering on every later 'state' broadcast
    // (which fires again any time the group's membership changes).
    private volatile boolean autoConnectOnNextState = false;

    // True from the moment we're told we're the new host until the
    // download+swap actually finishes. Used to hold off showing the world
    // list until it's genuinely safe to click Play.
    private volatile boolean migrationInProgress = false;

    // "A live handoff owes us a world open that hasn't happened yet." Set at
    // the start of an active-handoff migrate, cleared the moment ANY world
    // opens (or the handoff's download fails). onDisconnectedFromServer used
    // to gate its reopen-fallback on isHost(), which only worked because the
    // migrate handler pre-set currentHostId - now that a migrate is a pure
    // designation (currentHostId stays null until im_hosting is accepted),
    // this flag carries that intent instead. Being a one-shot, it also can't
    // misfire the way a time-window check could (e.g. quitting your own world
    // 30 seconds after a handoff must not slingshot you back in).
    private volatile boolean handoffOpenPending = false;

    // Guards downloadCurrentSave against running twice at once for the same
    // world (a manual "Open the World" click racing an automatic migrate
    // download) - see the long comment on that method.
    private final Object worldTransferLock = new Object();

    // The quit-time save upload runs here now (see onServerStopped) instead
    // of blocking the server-shutdown path. Non-daemon, and the shutdown
    // hook registered in onInitializeClient joins it - so quitting straight
    // to desktop still can't kill an upload mid-flight and leave the group
    // migrating from a stale save.
    private volatile Thread activeUploadThread;

    // Client-side liveness for the coordinator link. The coordinator pings
    // us and we ping it (which doubles as NAT-mapping keepalive - idle
    // mappings on home routers die quietly, and a dead-but-open socket
    // looked exactly like "connected" for 77 seconds in a real test).
    // Any inbound traffic stamps lastCoordinatorInboundMs; silence past the
    // limit forces a reconnect instead of trusting a zombie socket.
    private volatile long lastCoordinatorInboundMs = 0;
    private static final long KEEPALIVE_INTERVAL_MS = 20_000;
    private static final long COORDINATOR_SILENCE_LIMIT_MS = 75_000;
    private final java.util.concurrent.ScheduledExecutorService keepaliveScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "campfire-keepalive");
                t.setDaemon(true);
                return t;
            });

    // True only while we're closing our OWN already-open local copy of the
    // managed world as part of becoming host (see stopLocalServerIfRunningManagedWorld).
    // Without this, onServerStopping/onServerStopped would treat that internal
    // close as us actually leaving the group - sending 'leaving' to the
    // coordinator and re-uploading a save we're about to replace anyway.
    private volatile boolean internalRestartInProgress = false;
    private volatile CountDownLatch internalRestartLatch;

    private RelayFriendListener relayFriendListener;
    private RelayHostMultiplexer relayHostMultiplexer;

    @Override
    public void onInitializeClient() {
        loadConfig();
        CampfireHud.register(this);
        CampfireKeybinds.register(this);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> onClientStarted());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onClientJoinedWorld());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnectedFromServer());
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);

        // Quitting the game to desktop right after quitting the world would
        // otherwise kill the (now-background) save upload mid-flight - the
        // one failure that migrates the whole group onto a stale save. Hold
        // the JVM open until it finishes (bounded - a wedged upload can't
        // trap the process forever; its own request timeouts fire first).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Thread uploader = activeUploadThread;
            if (uploader != null && uploader.isAlive()) {
                System.out.println("[Campfire] Finishing the save upload before exiting...");
                try {
                    uploader.join(4 * 60_000);
                } catch (InterruptedException ignored) {
                }
            }
        }, "campfire-upload-guard"));

        keepaliveScheduler.scheduleAtFixedRate(this::keepaliveTick, KEEPALIVE_INTERVAL_MS,
                KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // Runs every KEEPALIVE_INTERVAL_MS for the life of the game. Pings the
    // coordinator (keeps the NAT mapping warm + gives the coordinator's own
    // heartbeat something to see), and force-reconnects a link that's gone
    // silent - a socket a router quietly dropped never errors on its own,
    // it just stops delivering, and this client once sat "connected" to
    // nothing for over a minute that way.
    private void keepaliveTick() {
        WebSocket ws = webSocket;
        if (ws == null || coordinatorStatus != CoordinatorStatus.CONNECTED) return;
        try {
            ws.sendPing(java.nio.ByteBuffer.allocate(0));
        } catch (Exception ignored) {
            // a dying socket - the silence check below (or the listener's
            // own onError) reaps it
        }
        long last = lastCoordinatorInboundMs;
        if (last > 0 && System.currentTimeMillis() - last > COORDINATOR_SILENCE_LIMIT_MS) {
            System.out.println("[Campfire] Coordinator link silent for over " + (COORDINATOR_SILENCE_LIMIT_MS / 1000)
                    + "s - treating it as dead and reconnecting.");
            try {
                ws.abort();
            } catch (Exception ignored) {
            }
            onCoordinatorConnectionLost();
        }
    }

    // No group configured yet (fresh install, or the config was manually
    // blanked out) - do NOT force CampfireSetupScreen on top of the title
    // screen. The docked "Campfire" button TitleScreenMixin adds is the only
    // entry point now, so the player decides if/when to create or join a
    // group instead of being gated on first launch. Already-configured
    // players - including our runClient/runClient2 dev setups, via the
    // sharedDevGroupId property - still auto-connect immediately, exactly as
    // before, since that part isn't something the player needs to opt into
    // every session.
    private void onClientStarted() {
        if (groupId != null && !groupId.isBlank()) {
            connectToCoordinator();
        }
    }

    // groupId/coordinatorHost used to be hardcoded constants. Now they live in
    // a small JSON file in the mod's config directory, so different players
    // (or a dev vs a real deployment) can point at different coordinators/
    // groups without editing code. A missing file is treated as "first run" -
    // a blank groupId is written out (meaning "no group yet"), which
    // connectToCoordinator() notices and turns into a freshly-minted, private,
    // unguessable invite code (see mintNewGroupId()) instead of ever falling
    // back to some fixed/shared/guessable default - anyone who learns a
    // group's id can join it and touch its save, so the id itself has to be
    // the access control. The `-Dcampfire.sharedDevGroupId=...` system
    // property (set for the runClient/runClient2 Gradle tasks only, see
    // build.gradle) is the one exception: it makes our own two dev clients
    // land in the same group automatically, without that shortcut existing
    // for anyone running the real distributed jar.
    private void loadConfig() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        ModConfig config = new ModConfig();

        try {
            if (Files.exists(configFile)) {
                String json = Files.readString(configFile);
                ModConfig loaded = gson.fromJson(json, ModConfig.class);
                if (loaded != null) config = loaded;
            } else {
                String sharedDevGroupId = System.getProperty("campfire.sharedDevGroupId");
                if (sharedDevGroupId != null) {
                    config.groupId = sharedDevGroupId;
                    // Our own dev clients must never default to the public
                    // community coordinator - they're testing against
                    // whatever local `node server.js` the developer has
                    // running (see the top-level CLAUDE.md dev commands).
                    config.coordinatorHost = "http://localhost:8080";
                }
                Files.createDirectories(configFile.getParent());
                Files.writeString(configFile, new GsonBuilder().setPrettyPrinting().create().toJson(config));
                System.out.println("[Campfire] No config found - wrote defaults to " + configFile);
            }
        } catch (IOException e) {
            System.out.println("[Campfire] Failed to load config (" + e.getMessage() + ") - using defaults.");
        }

        // The campfires list is the real record now; the flat fields
        // (groupId/coordinatorHost/localSaveVersion) mirror whichever entry
        // is active. A pre-list config's flat fields get folded into the
        // list on first load, so nobody's existing group is ever lost by
        // updating the mod.
        campfires.clear();
        if (config.campfires != null) {
            for (CampfireEntry e : config.campfires) {
                if (e != null && e.groupId != null && !e.groupId.isBlank() && findCampfire(e.groupId) == null) {
                    campfires.add(e);
                }
            }
        }
        if (campfires.isEmpty() && config.groupId != null && !config.groupId.isBlank()) {
            CampfireEntry legacy = new CampfireEntry();
            legacy.groupId = config.groupId;
            legacy.coordinatorHost = config.coordinatorHost;
            legacy.localSaveVersion = config.localSaveVersion;
            campfires.add(legacy);
        }

        CampfireEntry active = findCampfire(config.groupId);
        if (active != null) {
            this.groupId = active.groupId;
            this.localSaveVersion = active.localSaveVersion;
            this.activeWorldName = active.worldName == null || active.worldName.isBlank() ? null : active.worldName;
            applyCoordinatorHost(active.coordinatorHost == null || active.coordinatorHost.isBlank()
                    ? config.coordinatorHost : active.coordinatorHost);
        } else {
            this.groupId = config.groupId == null ? "" : config.groupId;
            this.localSaveVersion = config.localSaveVersion;
            applyCoordinatorHost(config.coordinatorHost);
        }

        System.out.println("[Campfire] Config: groupId=" + (groupId.isBlank() ? "(none yet)" : groupId)
                + ", coordinatorHost=" + coordinatorHost
                + (campfires.size() > 1 ? ", " + campfires.size() + " campfires remembered" : ""));
    }

    private CampfireEntry findCampfire(String gid) {
        if (gid == null || gid.isBlank()) return null;
        for (CampfireEntry e : campfires) {
            if (gid.equals(e.groupId)) return e;
        }
        return null;
    }

    // Normalizes whatever the config file or the setup screen's text field
    // holds into every derived form the rest of the class needs. Real users
    // type addresses like "105.158.203.205:8080" (no scheme) or paste them
    // with a trailing slash - both of which the old direct-derivation code
    // choked on (a scheme-less host:port isn't a parseable URI, and a
    // trailing slash produced a ws://..//ws path the coordinator's
    // exact-path WebSocket routing rejects). Healing them here, at every
    // entry point, beats validating them in each screen.
    private void applyCoordinatorHost(String raw) {
        String cleaned = normalizeCoordinatorHost(raw);

        this.coordinatorHost = cleaned;
        this.coordinatorWs = cleaned.replaceFirst("^http", "ws") + "/ws";

        String bareHost = "localhost";
        int port = 80;
        try {
            URI coordinatorUri = URI.create(cleaned);
            if (coordinatorUri.getHost() != null) bareHost = coordinatorUri.getHost();
            int parsedPort = coordinatorUri.getPort();
            port = parsedPort > 0 ? parsedPort : ("https".equals(coordinatorUri.getScheme()) ? 443 : 80);
        } catch (IllegalArgumentException e) {
            System.out.println("[Campfire] Couldn't parse coordinator address '" + cleaned + "' - falling back to localhost.");
        }
        this.coordinatorBareHost = bareHost;
        this.coordinatorPort = port;
    }

    // The healing half of applyCoordinatorHost, extracted so per-entry
    // status polls (fetchCampfireStatus) can normalize a NON-active
    // campfire's address without touching the active connection's state.
    private static String normalizeCoordinatorHost(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.isEmpty()) cleaned = DEFAULT_COORDINATOR_HOST;
        if (!cleaned.contains("://")) cleaned = "http://" + cleaned;
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    // Called from CampfireSetupScreen's coordinator-address field so a group
    // creator can point at their coordinator without hand-editing
    // campfire.json (the exact failure mode a real-world test hit: a fresh
    // install silently pointing at localhost). Joiners never need this -
    // the composite invite code (see getInviteCode) carries the address.
    void setCoordinatorHost(String raw) {
        applyCoordinatorHost(raw);
        persistConfig();
    }

    String getCoordinatorHost() {
        return coordinatorHost;
    }

    // The full shareable invite: "<groupId>@<host:port>". A bare group code
    // only works if the friend's config already points at the same
    // coordinator - which a fresh install's never does - so what players
    // actually copy/paste always carries the coordinator address with it,
    // and CampfireJoinScreen configures both sides of that in one paste.
    // The scheme is dropped for readability when it's plain http (the parse
    // side re-assumes http); an https coordinator keeps its scheme explicit.
    String getInviteCode() {
        String address = coordinatorHost.startsWith("http://")
                ? coordinatorHost.substring("http://".length())
                : coordinatorHost;
        return groupId + "@" + address;
    }

    private static class ModConfig {
        // Flat fields = the ACTIVE campfire (also what pre-list versions of
        // the mod read, so downgrading loses the list but not the group).
        // coordinatorHost defaults to the community coordinator we host, so a
        // totally fresh install (CurseForge/Modrinth, no setup) just works -
        // players who want to self-host their own instead can still repoint
        // it via CampfireSetupScreen/an invite carrying a different address.
        String groupId = "";
        String coordinatorHost = DEFAULT_COORDINATOR_HOST;
        int localSaveVersion = 0;
        java.util.List<CampfireEntry> campfires = new java.util.ArrayList<>();
    }

    // synchronized: now called from several threads (render, upload,
    // handoff) - interleaved writes would corrupt the file.
    private synchronized void persistConfig() {
        try {
            // Fold the live active-campfire fields back into its list entry
            // before writing, so the list is always current.
            if (groupId != null && !groupId.isBlank()) {
                CampfireEntry active = findCampfire(groupId);
                if (active == null) {
                    active = new CampfireEntry();
                    active.groupId = groupId;
                    campfires.add(active);
                }
                active.coordinatorHost = coordinatorHost;
                active.localSaveVersion = localSaveVersion;
                if (activeWorldName != null && !activeWorldName.isBlank()) {
                    active.worldName = activeWorldName;
                }
            }
            Path configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
            ModConfig config = new ModConfig();
            config.groupId = this.groupId;
            config.coordinatorHost = this.coordinatorHost;
            config.localSaveVersion = this.localSaveVersion;
            config.campfires = new java.util.ArrayList<>(campfires);
            Files.writeString(configFile, new GsonBuilder().setPrettyPrinting().create().toJson(config));
        } catch (IOException e) {
            System.out.println("[Campfire] Failed to save new group id to config: " + e.getMessage());
        }
    }

    // Asks the coordinator to mint a brand-new, unguessable group id - this
    // is what turns "no group configured yet" into an actual private group,
    // instead of ever silently joining some shared/guessable default. The
    // code is written back into campfire.json (so it survives restarts).
    // Package-private: called from CampfireSetupScreen's "Light a New
    // Campfire" button, which is responsible for showing the code on-screen
    // (CampfireCodeScreen) afterward - this method just does the network call.
    boolean mintNewGroupId() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(coordinatorHost + "/groups/new"))
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("[Campfire] Failed to create a new group (HTTP " + response.statusCode() + ")");
                return false;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            this.groupId = json.get("groupId").getAsString();
            this.localSaveVersion = 0; // brand-new group - no save generation exists yet
            this.activeWorldName = null; // no world yet either - named at Create the Shared World
            persistConfig();
            System.out.println("[Campfire] Created a new group! Share this invite code with friends: " + groupId);
            return true;
        } catch (IOException | InterruptedException e) {
            System.out.println("[Campfire] Failed to create a new group (is the coordinator reachable?): " + e.getMessage());
            return false;
        }
    }

    // Public (not package-private like most of this class' internals)
    // because TitleScreenMixin, in the mixin subpackage, needs it to decide
    // whether the docked button should open the create/join chooser or the
    // status screen.
    public String getGroupId() {
        return groupId;
    }

    // Called from CampfireStatusScreen's "Leave Campfire" button. Only
    // reachable from the title screen (no world open), so there's no save to
    // upload here - just forget the group and disconnect, so the docked
    // title-screen button goes back to offering "create or join" next time.
    void leaveGroup() {
        intentionalDisconnect = true;
        if (webSocket != null) {
            webSocket.abort();
            webSocket = null;
            sendQueue.clear();
        }
        if (relayFriendListener != null) {
            relayFriendListener.stop();
            relayFriendListener = null;
        }
        currentHostId = null;
        members = java.util.List.of();
        awayMembers = java.util.List.of();
        rosterPrimed = false;
        coordinatorStatus = CoordinatorStatus.NOT_CONFIGURED;
        // Forget this campfire's entry too - "Leave" means it stops
        // appearing in the list (the world folder and the group itself are
        // untouched; rejoining with the same invite brings it right back).
        String leavingId = this.groupId;
        campfires.removeIf(e -> e.groupId != null && e.groupId.equals(leavingId));
        this.groupId = "";
        this.localSaveVersion = 0; // version bookkeeping belongs to the group we just left
        this.activeWorldName = null;
        awaitingHandoffSinceMs = 0;
        persistConfig();
    }

    enum GroupLookupResult { EXISTS, NOT_FOUND, UNREACHABLE }

    // Checked by CampfireJoinScreen before it commits to a typed code.
    // getGroup() on the coordinator lazily creates a group on the first
    // 'hello' no matter what the code is, so without this a typo'd or
    // made-up code would silently spin up a brand-new empty group instead
    // of surfacing as wrong - this asks the coordinator's /exists endpoint,
    // which only says yes for a code that's actually had someone say hello
    // or has an uploaded save, before we ever open a websocket for it.
    // Runs off the render thread; the callback is bounced back onto it.
    void checkGroupExists(String code, java.util.function.Consumer<GroupLookupResult> callback) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(coordinatorHost + "/groups/" + code + "/exists"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    GroupLookupResult result;
                    if (response.statusCode() == 200) {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        result = json.get("exists").getAsBoolean() ? GroupLookupResult.EXISTS : GroupLookupResult.NOT_FOUND;
                    } else {
                        result = GroupLookupResult.UNREACHABLE;
                    }
                    GroupLookupResult finalResult = result;
                    MinecraftClient.getInstance().execute(() -> callback.accept(finalResult));
                })
                .exceptionally(e -> {
                    System.out.println("[Campfire] Failed to check group code (is the coordinator reachable?): " + e.getMessage());
                    MinecraftClient.getInstance().execute(() -> callback.accept(GroupLookupResult.UNREACHABLE));
                    return null;
                });
    }

    // Called from CampfireJoinScreen once checkGroupExists has confirmed the
    // code is real. Codes are always minted in uppercase, so typed input is
    // normalized the same way for typo-tolerance (a manually hand-edited
    // config groupId, a power-user path this screen doesn't touch, is
    // unaffected).
    void joinGroup(String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        CampfireEntry existing = findCampfire(normalized);
        this.groupId = normalized;
        this.activeWorldName = existing != null && existing.worldName != null && !existing.worldName.isBlank()
                ? existing.worldName : null;
        // Rejoining a campfire this machine already knows keeps its version
        // bookkeeping (the world folder is likely still on disk, and a
        // version mismatch re-downloads anyway); a genuinely new code starts
        // at 0 so the first open always downloads before hosting.
        this.localSaveVersion = existing != null && managedWorldExistsLocally() ? existing.localSaveVersion : 0;
        persistConfig();
        autoConnectOnNextState = true;
        connectToCoordinator();
    }

    // Swaps which campfire is active: drop the old group's websocket, adopt
    // the entry's group/coordinator/version bookkeeping, reconnect. Refused
    // while inside the shared world - the websocket signing into group B
    // while the local server still hosts group A would cross-wire relay
    // routing and the quit-time upload.
    void switchToCampfire(String targetGroupId) {
        CampfireEntry target = findCampfire(targetGroupId);
        if (target == null) return;
        if (targetGroupId.equals(this.groupId)) {
            if (coordinatorStatus != CoordinatorStatus.CONNECTED) reconnectNow();
            return;
        }
        if (describeHudStatus() != null) {
            showMigrationToast("Leave the world first", "Can't switch campfires mid-game");
            return;
        }

        intentionalDisconnect = true;
        if (webSocket != null) {
            webSocket.abort();
            webSocket = null;
            sendQueue.clear();
        }
        currentHostId = null;
        members = java.util.List.of();
        awayMembers = java.util.List.of();
        rosterPrimed = false;
        wasNextUp = false;
        awaitingHandoffSinceMs = 0;
        handoffOpenPending = false;
        hostDirectAddress = null;
        connectionTier = null;

        this.groupId = target.groupId;
        this.localSaveVersion = target.localSaveVersion;
        this.activeWorldName = target.worldName == null || target.worldName.isBlank() ? null : target.worldName;
        applyCoordinatorHost(target.coordinatorHost == null || target.coordinatorHost.isBlank()
                ? coordinatorHost : target.coordinatorHost);
        persistConfig();
        connectToCoordinator();
    }

    // Snapshot for the list screen (render thread) - never the live list.
    java.util.List<CampfireEntry> getCampfires() {
        return java.util.List.copyOf(campfires);
    }

    // Public (both): TitleScreenMixin, in the mixin subpackage, routes the
    // docked button off these - the list screen when there's actually a
    // choice to make, and the list (not setup) when campfires are remembered
    // but none is active.
    public boolean hasMultipleCampfires() {
        return campfires.size() > 1;
    }

    public boolean hasAnyCampfires() {
        return !campfires.isEmpty();
    }

    String getActiveWorldName() {
        return activeWorldName;
    }

    // One row's worth of live presence for the campfire list. `reachable`
    // false = that campfire's coordinator didn't answer; `exists` false =
    // it answered and has never heard of the code (e.g. its state was
    // wiped and no save was ever uploaded).
    record RemoteCampfireStatus(boolean reachable, boolean exists, int online,
                                String hostName, java.util.List<String> memberNames) {
    }

    // Polls a campfire's own coordinator over plain HTTP - the one live
    // websocket belongs to the ACTIVE campfire only, but the list screen
    // shows presence for all of them. Callback fires on an HTTP worker
    // thread; callers just stash the result and read it next frame.
    void fetchCampfireStatus(CampfireEntry entry, java.util.function.Consumer<RemoteCampfireStatus> callback) {
        RemoteCampfireStatus unreachable = new RemoteCampfireStatus(false, false, 0, null, java.util.List.of());
        HttpRequest request;
        try {
            String host = normalizeCoordinatorHost(entry.coordinatorHost == null || entry.coordinatorHost.isBlank()
                    ? coordinatorHost : entry.coordinatorHost);
            request = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/groups/" + entry.groupId + "/status"))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
        } catch (Exception e) {
            callback.accept(unreachable);
            return;
        }
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    RemoteCampfireStatus status;
                    try {
                        if (response.statusCode() == 200) {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            java.util.List<String> names = new java.util.ArrayList<>();
                            if (json.has("memberNames") && json.get("memberNames").isJsonArray()) {
                                json.getAsJsonArray("memberNames").forEach(el -> names.add(el.getAsString()));
                            }
                            status = new RemoteCampfireStatus(true, true,
                                    json.has("online") && !json.get("online").isJsonNull() ? json.get("online").getAsInt() : 0,
                                    json.has("hostName") && !json.get("hostName").isJsonNull() ? json.get("hostName").getAsString() : null,
                                    java.util.List.copyOf(names));
                        } else if (response.statusCode() == 404) {
                            status = new RemoteCampfireStatus(true, false, 0, null, java.util.List.of());
                        } else {
                            status = unreachable;
                        }
                    } catch (Exception e) {
                        status = unreachable;
                    }
                    callback.accept(status);
                })
                .exceptionally(e -> {
                    callback.accept(unreachable);
                    return null;
                });
    }

    void connectToCoordinator() {
        if (groupId == null || groupId.isBlank()) {
            System.out.println("[Campfire] Not connecting - no group id set.");
            coordinatorStatus = CoordinatorStatus.NOT_CONFIGURED;
            return;
        }

        Session session = MinecraftClient.getInstance().getSession();
        this.playerName = session.getUsername();
        UUID uuid = session.getUuidOrNull();
        this.playerId = uuid != null ? uuid.toString() : UUID.randomUUID().toString();

        startFriendListenerIfNeeded();

        intentionalDisconnect = false;
        coordinatorStatus = CoordinatorStatus.CONNECTING;
        // The group id rides on the URL as well as in the 'hello' message.
        // The Workers coordinator needs it at upgrade time to route the
        // socket to the right group; the Node coordinator ignores unknown
        // query params, so this works against either.
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(coordinatorWs + "?group=" + groupId), new CoordinatorListener())
                .thenAccept(ws -> System.out.println("[Campfire] Connected to coordinator"))
                .exceptionally(e -> {
                    System.out.println("[Campfire] Couldn't reach the coordinator: " + e.getMessage());
                    onCoordinatorConnectionLost();
                    return null;
                });
    }

    // Shared aftermath of every way the coordinator link can die: the socket
    // failing to open, closing normally, or erroring mid-session. Flips the
    // GUI status, wipes the (now unknowable) roster, and quietly retries in
    // the background - a coordinator restarting or a Wi-Fi blip shouldn't
    // require the player to relaunch their game to get back in the group.
    private void onCoordinatorConnectionLost() {
        webSocket = null;
        sendQueue.clear();
        members = java.util.List.of();
        awayMembers = java.util.List.of();
        rosterPrimed = false; // next successful connect's first snapshot is context, not news
        if (intentionalDisconnect || groupId == null || groupId.isBlank()) {
            coordinatorStatus = groupId == null || groupId.isBlank()
                    ? CoordinatorStatus.NOT_CONFIGURED : CoordinatorStatus.DISCONNECTED;
            return;
        }
        coordinatorStatus = CoordinatorStatus.DISCONNECTED;
        if (reconnectPending.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    reconnectPending.set(false);
                    return;
                }
                reconnectPending.set(false);
                if (!intentionalDisconnect && coordinatorStatus == CoordinatorStatus.DISCONNECTED
                        && groupId != null && !groupId.isBlank()) {
                    System.out.println("[Campfire] Retrying coordinator connection...");
                    connectToCoordinator();
                }
            }, "campfire-reconnect").start();
        }
    }

    private void onClientJoinedWorld() {
        // Tracks whether this join put us inside the shared world (as host
        // or as a guest at a friend's camp) - a later mid-session 'migrate'
        // uses this to tell "actively playing, hand off seamlessly" apart
        // from "idling in menus, don't hijack the game".
        inSharedSession = describeHudStatus() != null;
        awaitingHandoffSinceMs = 0; // whatever we were owed, we're in a world now
        handoffOpenPending = false; // ditto

        // Quitting a hosted world sends 'leaving', which wipes us from the
        // coordinator's member list entirely. The coordinator only hears
        // from us again via a fresh 'hello' - and previously that only ever
        // got sent once, at game launch. So re-announce ourselves here too,
        // any time we open the managed world locally, or we'd be invisible
        // to the coordinator until the whole game restarted.
        MinecraftServer localServer = MinecraftClient.getInstance().getServer();
        if (localServer == null || !isManagedWorld(localServer)) return;
        sendHello();

        if (isHost()) {
            tryOpenToLan(localServer);
            return;
        }

        // Hosting is claim-based now: the coordinator no longer names anyone
        // host just for connecting, so the player who opens the managed world
        // while NO ONE is hosting is volunteering - open to LAN and claim it
        // (im_hosting). Guard: if the coordinator holds a newer save than the
        // copy we just opened, claiming hostship would serve friends a stale
        // world - warn instead, and leave the group's canonical save alone.
        // (Someone else already hosting keeps the old behavior: we opened a
        // local copy on the side, and we don't touch the group's state.)
        if (currentHostId == null) {
            if (knownSaveVersion > localSaveVersion) {
                System.out.println("[Campfire] Local copy is save v" + localSaveVersion + " but the group is at v" + knownSaveVersion + " - not claiming host from a stale copy.");
                showMigrationToast("This copy may be outdated", "Open the world from the Campfire screen to host the latest version");
                return;
            }
            tryOpenToLan(localServer);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        if (!isManagedWorld(server)) return;
        if (internalRestartInProgress) return; // closing our own stale copy before reloading the migrated save - not actually leaving
        if (webSocket != null) {
            sendQueue.add("{\"type\":\"leaving\"}");
            pumpSendQueue();
            System.out.println("[Campfire] Sent 'leaving' to coordinator");
        }
    }

    private void onServerStopped(MinecraftServer server) {
        if (!isManagedWorld(server)) return;

        if (internalRestartInProgress) {
            System.out.println("[Campfire] Closed our own stale local copy - continuing migration.");
            CountDownLatch latch = internalRestartLatch;
            if (latch != null) latch.countDown();
            return;
        }

        if (relayHostMultiplexer != null) {
            relayHostMultiplexer.shutdownAll();
        }

        // Mirror of the stale-copy guard in onClientJoinedWorld: if we KNOW
        // the coordinator holds a newer generation than the copy we just
        // played (we never claimed host - the claim was refused or skipped
        // for exactly this reason), uploading would replace the group's real
        // save with our fork. Skip it. Only this provably-stale case is
        // gated; a confirmed host always uploads.
        if (!isHost() && knownSaveVersion > localSaveVersion) {
            System.out.println("[Campfire] Not uploading: local copy is v" + localSaveVersion + " but the group is at v" + knownSaveVersion + " and we're not the host.");
            showMigrationToast("Not uploading this copy", "The group's world moved on while this copy sat still");
            currentHostId = null;
            updateHostingMode();
            sendHello(); // see the comment at the bottom of this method
            return;
        }

        // Our own managed-world server just stopped, which only ever happens
        // on the host's client. The coordinator already deleted us from the
        // group's members the moment it got our 'leaving' message (sent from
        // onServerStopping, just before this), so it has no one left to send
        // a 'state'/'migrate'/'host_confirmed' update to - we will NEVER hear
        // from it that we've stopped being host. Left alone, isHost() would
        // keep returning true forever and our friend listener would never
        // come back on, which is exactly the "Connection refused" bug seen
        // after quitting a hosted world. So: clear our own hosting status
        // locally right here, and let updateHostingMode() flip us back into
        // friend mode immediately.
        currentHostId = null;
        updateHostingMode();

        // The zip+upload itself runs on its own thread: this handler is on
        // the server's shutdown path, and a big world on a slow link (plus
        // retries) held a real player hostage on the frozen "Saving world"
        // screen for 35+ seconds. The upload doesn't need the server - the
        // world files are fully written by SERVER_STOPPED - it just needs to
        // finish eventually, which the shutdown hook registered in
        // onInitializeClient guarantees even if the player closes the whole
        // game while it's still running.
        Path worldSaveDir = server.getSavePath(WorldSavePath.ROOT);
        showMigrationToast("Saving world...", "Uploading so your friends can pick up where you left off");
        Thread uploader = new Thread(() -> {
            try {
                boolean uploaded = uploadSave(worldSaveDir);
                if (uploaded) {
                    showMigrationToast("World saved!", "Your friends can now take over hosting");
                } else {
                    showMigrationToast("Save upload failed", "Friends may see an older version of the world");
                }
            } catch (Exception e) {
                System.out.println("[Campfire] Failed during save upload: " + e.getMessage());
                e.printStackTrace();
                showMigrationToast("Save upload failed", "Friends may see an older version of the world");
            } finally {
                activeUploadThread = null;
                // ...and then REJOIN the group. 'leaving' removed us from the
                // coordinator's member roster, and broadcasts only go to
                // members - so without a fresh hello this client goes BLIND
                // at the title screen: its status screen keeps showing the
                // last state it ever parsed (us as host, stale roster), it
                // never hears that a friend started hosting, and "Open the
                // World" gets offered when "Join X's World" is what reality
                // calls for. That exact staleness is what sent a player into
                // a solo forked copy of the world during the first real
                // two-player test. Quitting the WORLD is not leaving the
                // GROUP - the player is still sitting at the fire, just not
                // in the game. (Deliberately after the upload: our departure
                // already triggered the coordinator's pendingMigration wait,
                // and re-appearing at the back of the queue mustn't happen
                // before our own save lands.)
                sendHello();
            }
        }, "campfire-upload");
        uploader.setDaemon(false); // must outlive a quit-to-desktop
        activeUploadThread = uploader;
        uploader.start();
    }

    private boolean isManagedWorld(MinecraftServer server) {
        Path worldSaveDir = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        String folderName = worldSaveDir.getFileName().toString();
        boolean managed = folderName.equals(groupId);
        if (!managed) {
            System.out.println("[Campfire] '" + folderName + "' isn't the managed group world (" + groupId + ") - ignoring.");
        }
        return managed;
    }

    private boolean isHost() {
        return currentHostId != null && currentHostId.equals(playerId);
    }

    // ---------- Live-state plumbing for the GUI ----------

    // Rebuilds the roster from a 'state' message's members/queue arrays.
    // Sorted into queue order so index 0 is the host (or, with no host yet,
    // whoever's next in line) - screens lean on that ordering directly.
    private void updateMemberRoster(JsonObject stateJson) {
        if (!stateJson.has("members") || !stateJson.get("members").isJsonArray()) return;

        java.util.List<String> queueOrder = new java.util.ArrayList<>();
        if (stateJson.has("queue") && stateJson.get("queue").isJsonArray()) {
            stateJson.getAsJsonArray("queue").forEach(el -> queueOrder.add(el.getAsString()));
        }

        java.util.List<GroupMember> roster = new java.util.ArrayList<>();
        stateJson.getAsJsonArray("members").forEach(el -> {
            JsonObject m = el.getAsJsonObject();
            String id = m.get("id").getAsString();
            String name = m.has("name") && !m.get("name").isJsonNull() ? m.get("name").getAsString() : id;
            // modHash/modCount are optional - an older client or coordinator
            // simply never sends them, which must read as "nothing to
            // compare" rather than a false mismatch alarm.
            String modHash = m.has("modHash") && !m.get("modHash").isJsonNull() ? m.get("modHash").getAsString() : null;
            int modCount = m.has("modCount") && !m.get("modCount").isJsonNull() ? m.get("modCount").getAsInt() : -1;
            boolean you = id.equals(playerId);
            boolean modMismatch = !you && MOD_LIST_HASH != null && modHash != null && !modHash.equals(MOD_LIST_HASH);
            roster.add(new GroupMember(id, name, id.equals(currentHostId), you, modMismatch, modCount));
        });
        roster.sort(Comparator.comparingInt(gm -> {
            int i = queueOrder.indexOf(gm.id());
            return i < 0 ? Integer.MAX_VALUE : i;
        }));

        java.util.List<GroupMember> previous = this.members;
        this.members = java.util.List.copyOf(roster);

        // Coordinator-remembered names of everyone NOT currently online -
        // an optional field, so a client talking to an older coordinator
        // just keeps an empty away list.
        java.util.List<AwayMember> away = new java.util.ArrayList<>();
        if (stateJson.has("away") && stateJson.get("away").isJsonArray()) {
            stateJson.getAsJsonArray("away").forEach(el -> {
                JsonObject a = el.getAsJsonObject();
                String name = a.has("name") && !a.get("name").isJsonNull() ? a.get("name").getAsString() : "?";
                long lastSeen = a.has("lastSeenMs") && !a.get("lastSeenMs").isJsonNull() ? a.get("lastSeenMs").getAsLong() : 0L;
                away.add(new AwayMember(name, lastSeen));
            });
        }
        this.awayMembers = java.util.List.copyOf(away);

        boolean announce = rosterPrimed;
        rosterPrimed = true;
        if (announce) {
            announceRosterChanges(previous, this.members);
        }
        updateNextUpStatus(announce);
    }

    // Turns a roster change into the kind of awareness a Discord call gives
    // for free: a quiet toast (plus a campfire crackle) when a friend
    // arrives or leaves, whether you're staring at the title screen or busy
    // mining. Never announces ourselves - our own join/leave is never news.
    private void announceRosterChanges(java.util.List<GroupMember> before, java.util.List<GroupMember> after) {
        java.util.Set<String> beforeIds = new java.util.HashSet<>();
        before.forEach(m -> beforeIds.add(m.id()));
        java.util.Set<String> afterIds = new java.util.HashSet<>();
        after.forEach(m -> afterIds.add(m.id()));

        for (GroupMember m : after) {
            if (!beforeIds.contains(m.id()) && !m.you()) {
                showMigrationToast(m.name() + " pulled up a seat", "They're around the fire now");
                playCrackle();
            }
        }
        for (GroupMember m : before) {
            if (!afterIds.contains(m.id()) && !m.you()) {
                showMigrationToast(m.name() + " left the campfire", "They can rejoin any time");
            }
        }
    }

    // "No host + we're first in the queue" is the one roster state that's
    // actually about to demand something of THIS player - during a
    // migration's upload-wait window (up to ~15s of otherwise unexplained
    // quiet), this is what tells them why they're waiting and that they're
    // the one being waited for.
    private void updateNextUpStatus(boolean announce) {
        java.util.List<GroupMember> roster = this.members;
        boolean nextUp = currentHostId == null && !roster.isEmpty() && roster.get(0).you();
        // Suppress while a handoff is already downloading/opening on this
        // machine - "you're next up" mid-migration is redundant noise.
        boolean handoffBusy = migrationInProgress || preparingWorld
                || System.currentTimeMillis() - lastActiveHandoffMs < ACTIVE_HANDOFF_WINDOW_MS;
        if (announce && nextUp && !wasNextUp && !handoffBusy) {
            showMigrationToast("You're next up to host", "Open the world from the Campfire screen when ready");
        }
        wasNextUp = nextUp;
    }

    // The soft vanilla campfire crackle - one short, quiet pop that fits the
    // theme instead of a jarring UI ping. Bounced onto the client thread;
    // roster updates arrive on the WebSocket thread.
    private void playCrackle() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.getSoundManager().play(
                net.minecraft.client.sound.PositionedSoundInstance.master(
                        net.minecraft.sound.SoundEvents.BLOCK_CAMPFIRE_CRACKLE, 1.0f, 0.5f)));
    }

    public CoordinatorStatus getStatus() {
        return coordinatorStatus;
    }

    java.util.List<GroupMember> getMembers() {
        return members;
    }

    java.util.List<AwayMember> getAwayMembers() {
        return awayMembers;
    }

    int getKnownSaveVersion() {
        return knownSaveVersion;
    }

    boolean isSelfHost() {
        return isHost();
    }

    boolean isSomeoneHosting() {
        return currentHostId != null;
    }

    // The current host's display name, or null when no one's hosting (or the
    // roster hasn't arrived yet).
    String getHostName() {
        if (currentHostId == null) return null;
        for (GroupMember m : members) {
            if (m.id().equals(currentHostId)) return m.name();
        }
        return null;
    }

    // ---------- One-click world actions (status screen) ----------

    // Friend-side "Join the World" button: same tiered connect
    // (UPnP direct -> hole-punch -> relay) as an automatic migration
    // reconnect, just player-initiated.
    void connectToHostNow() {
        connectToNewHost();
    }

    void reconnectNow() {
        if (coordinatorStatus != CoordinatorStatus.CONNECTED) {
            connectToCoordinator();
        }
    }

    boolean managedWorldExistsLocally() {
        if (groupId == null || groupId.isBlank()) return false;
        try {
            return MinecraftClient.getInstance().getLevelStorage().levelExists(groupId);
        } catch (Exception e) {
            return false;
        }
    }

    // Host-side "Open the World" button - THE way to become host now that
    // the coordinator no longer auto-promotes anyone. If the group's save is
    // a generation we don't have locally (someone else hosted since we last
    // did, or this machine never hosted at all), the latest zip is pulled
    // first on a background thread and only then does the world open - so
    // "who hosted last?" never matters and nobody has to plan handoffs in a
    // group chat. When our local copy already IS the latest (the common
    // "same host as yesterday" case), this opens instantly with no network
    // round trip and no popups.
    void openManagedWorld(Screen parent) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean needsDownload = knownSaveVersion > 0
                && (knownSaveVersion != localSaveVersion || !managedWorldExistsLocally());
        if (!needsDownload) {
            startWorldGuarded(client, parent);
            return;
        }

        if (preparingWorld) return;
        preparingWorld = true;
        showMigrationToast("Getting the latest world...", "Downloading your group's newest save");
        new Thread(() -> {
            boolean downloaded = downloadCurrentSave(groupId, knownSaveVersion, null);
            preparingWorld = false;
            if (downloaded) {
                client.execute(() -> {
                    if (client.getServer() != null) return;
                    startWorldGuarded(client, parent);
                });
            } else {
                showMigrationToast("Download problem", "Couldn't fetch the world - check your connection and try again");
            }
        }, "campfire-fetch-world").start();
    }

    // Every world-open funnels through here because vanilla's own task
    // runner QUIETLY swallows exceptions from queued render-thread tasks
    // ("Error executing task on Client" in the log, nothing on screen) - a
    // real test had a post-download world-open die exactly that way, with
    // the player left staring at a screen that never changed and no clue
    // anything failed. Catch, log loudly, and say so on screen.
    private void startWorldGuarded(MinecraftClient client, Screen parent) {
        try {
            client.createIntegratedServerLoader().start(parent, groupId);
        } catch (Exception e) {
            System.out.println("[Campfire] Opening the world failed: " + e);
            e.printStackTrace();
            showMigrationToast("Couldn't open the world", "Something went wrong - try again from the Campfire screen");
        }
    }

    // First-ever host of a brand-new group: creates the shared world with
    // exactly the right FOLDER name (the group id - isManagedWorld() keys on
    // it) and opens it, in one click. Before this, the player had to type
    // the code by hand as a world name in vanilla's create-world screen, and
    // one typo silently produced an unmanaged world the mod ignores. The
    // DISPLAY name is free-form (it lives in level.dat and travels with the
    // save to every future host) - CampfireWorldNameScreen asks for it, so
    // the world list shows "Our Campfire" instead of a code like DK77JD6332.
    void createManagedWorld(String displayName) {
        String levelName = displayName == null || displayName.isBlank() ? groupId : displayName.trim();
        activeWorldName = levelName;
        persistConfig();
        MinecraftClient client = MinecraftClient.getInstance();
        LevelInfo levelInfo = new LevelInfo(levelName, GameMode.SURVIVAL, false,
                Difficulty.NORMAL, false, new GameRules(), DataConfiguration.SAFE_MODE);
        try {
            client.createIntegratedServerLoader().createAndStart(groupId, levelInfo, GeneratorOptions.createRandom(),
                    registryManager -> registryManager.get(RegistryKeys.WORLD_PRESET)
                            .entryOf(WorldPresets.DEFAULT).value().createDimensionsRegistryHolder());
        } catch (Exception e) {
            // Same reasoning as startWorldGuarded: don't let a failure here
            // vanish into vanilla's silent task runner.
            System.out.println("[Campfire] Creating the world failed: " + e);
            e.printStackTrace();
            showMigrationToast("Couldn't create the world", "Something went wrong - try again from the Campfire screen");
        }
    }

    boolean isPreparingWorld() {
        return preparingWorld;
    }

    // "No one is hosting and we're at the front of the queue" - the state in
    // which the status screen offers Open/Create even though the coordinator
    // hasn't (and won't, until im_hosting) named us host.
    boolean isNextUp() {
        if (currentHostId != null) return false;
        java.util.List<GroupMember> roster = members;
        return !roster.isEmpty() && roster.get(0).you();
    }

    // ---------- In-game HUD badge ----------

    // isManagedWorld() logs when the answer is no (useful once, at an event);
    // this silent twin is for the HUD, which asks every frame.
    private boolean isManagedWorldQuiet(MinecraftServer server) {
        if (groupId == null || groupId.isBlank()) return false;
        return server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize()
                .getFileName().toString().equals(groupId);
    }

    // One line for the corner-of-the-screen badge, or null for "draw
    // nothing" (not in the campfire world at all). Second line via
    // describeHudDetail() below.
    String describeHudStatus() {
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (server != null) {
            if (!isManagedWorldQuiet(server)) return null;
            int friendsOnline = Math.max(0, members.size() - 1);
            if (friendsOnline == 0) return "Hosting your camp";
            return "Hosting for " + friendsOnline + (friendsOnline == 1 ? " friend" : " friends");
        }

        ServerInfo entry = client.getCurrentServerEntry();
        if (client.world != null && entry != null && entry.name != null && entry.name.startsWith("Campfire")) {
            String host = getHostName();
            return host != null ? "At " + host + "'s camp" : "At your friend's camp";
        }
        return null;
    }

    // The connection-quality subline: how traffic is flowing right now.
    String describeHudDetail() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getServer() != null) {
            return coordinatorStatus == CoordinatorStatus.CONNECTED ? null : "coordinator offline";
        }
        ConnectionTier tier = connectionTier;
        if (tier == null) return null;
        return switch (tier) {
            case DIRECT -> "direct connection";
            case PUNCHED -> "direct connection (punched)";
            case RELAY -> "via relay";
        };
    }

    // ---------- Relay mode switching (Milestone 7a) ----------

    private void updateHostingMode() {
        boolean hostNow = isHost();

        if (hostNow && relayHostMultiplexer == null) {
            if (relayFriendListener != null) {
                relayFriendListener.stop();
                relayFriendListener = null;
            }
            relayHostMultiplexer = new RelayHostMultiplexer(HOST_LAN_PORT, this::sendJson);
            System.out.println("[Campfire] We are host - relay multiplexer ready (local server port " + HOST_LAN_PORT + ").");

            MinecraftServer server = MinecraftClient.getInstance().getServer();
            if (server != null) {
                tryOpenToLan(server);
            }
        } else if (!hostNow && relayHostMultiplexer != null) {
            relayHostMultiplexer.shutdownAll();
            relayHostMultiplexer = null;
            startFriendListenerIfNeeded();
        }
    }

    private void startFriendListenerIfNeeded() {
        if (relayFriendListener == null) {
            relayFriendListener = new RelayFriendListener(RELAY_LISTEN_PORT, this::sendJson);
            relayFriendListener.start();
        }
    }

    private void tryOpenToLan(MinecraftServer server) {
        if (!isManagedWorld(server)) {
            System.out.println("[Campfire] Server started, but it isn't the managed group world - not opening to LAN.");
            return;
        }

        GameMode gameMode = GameMode.SURVIVAL;
        if (MinecraftClient.getInstance().interactionManager != null) {
            gameMode = MinecraftClient.getInstance().interactionManager.getCurrentGameMode();
        }
        final GameMode finalGameMode = gameMode;

        server.execute(() -> {
            if (server instanceof IntegratedServer integratedServer) {
                boolean opened = integratedServer.openToLan(finalGameMode, false, HOST_LAN_PORT);
                System.out.println("[Campfire] openToLan(port=" + HOST_LAN_PORT + ", mode=" + finalGameMode + ") -> " + opened);
                if (opened) {
                    JsonObject imHosting = new JsonObject();
                    imHosting.addProperty("type", "im_hosting");
                    sendJson(imHosting);
                    System.out.println("[Campfire] Told coordinator we're open for business.");
                    attemptUpnpMapping();
                }
            } else {
                System.out.println("[Campfire] Server isn't an IntegratedServer - can't open to LAN this way.");
            }
        });
    }

    // UPnP discovery/mapping is blocking network I/O (SSDP multicast + SOAP
    // calls with their own timeouts) - runs on its own thread so it never
    // stalls the server or render thread. Whatever the outcome, the
    // coordinator gets told: a successful mapping lets friends skip the
    // relay and connect straight to us, and explicitly reporting failure
    // (rather than saying nothing) clears out any stale address a previous
    // host of this group might have left behind.
    private void attemptUpnpMapping() {
        new Thread(() -> {
            UpnpPortMapper.Result result = UpnpPortMapper.tryMapPort(HOST_LAN_PORT);
            JsonObject directAddress = new JsonObject();
            directAddress.addProperty("type", "direct_address");
            if (result != null) {
                directAddress.addProperty("address", result.externalIp() + ":" + result.externalPort());
                System.out.println("[Campfire] Friends can connect directly at " + result.externalIp() + ":" + result.externalPort());
            } else {
                directAddress.add("address", com.google.gson.JsonNull.INSTANCE);
                System.out.println("[Campfire] No direct connection available (UPnP unsupported/blocked) - friends will need another way to reach us.");
            }
            sendJson(directAddress);
        }, "campfire-upnp").start();
    }

    // Fires whenever we lose a connection to any server - including the old
    // host's, when they leave. If a live handoff named us host and the
    // download+swap already finished by the time this fires, reopen the world
    // as ours right away. If it's still in progress (migrationInProgress),
    // wait - the "migrate" handler below opens it once the download actually
    // completes. This is what stops someone from wandering into a
    // half-written save before it's actually ready.
    private void onDisconnectedFromServer() {
        if (inSharedSession) {
            inSharedSession = false;
            lastSharedSessionEndMs = System.currentTimeMillis();
        }
        connectionTier = null; // whatever link we had is gone with the connection
        if (!handoffOpenPending) return;
        if (MinecraftClient.getInstance().getServer() != null) return; // already running our own world

        if (migrationInProgress) {
            System.out.println("[Campfire] Disconnected, but still migrating - waiting before reopening the world.");
            return;
        }

        startManagedWorldIfIdle();
    }

    // Used to dump the new host at the vanilla Select World screen with a
    // "click Play" toast - one more menu, one more chance to click the wrong
    // world. The handoff was already automatic up to that point, so finish
    // the job: open the shared world directly.
    private void startManagedWorldIfIdle() {
        MinecraftClient.getInstance().execute(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getServer() != null) return; // somehow already in a world
            System.out.println("[Campfire] We're the new host - opening '" + groupId + "' directly.");
            startWorldGuarded(client, new TitleScreen());
        });
    }

    // Small on-screen popup so whoever's becoming the new host actually sees
    // something happen, instead of having to guess or check a terminal. Uses
    // our own CampfireToast (amber card + pixel flame) instead of vanilla's
    // SystemToast, so every Campfire notification is visually unmistakable.
    private void showMigrationToast(String title, String description) {
        MinecraftClient.getInstance().execute(() ->
                MinecraftClient.getInstance().getToastManager().add(new CampfireToast(title, description)));
    }

    // Entry point for "the host is ready, go connect" - called from both
    // host_confirmed and the join-flow auto-connect. Prefers a direct
    // connection to the host's UPnP-mapped address over the relay, since a
    // direct connection carries none of the coordinator's relay bandwidth
    // cost. UPnP mapping happens on the host's machine in parallel with
    // opening to LAN, so it may not have resolved yet by the time we get
    // here - in that case, wait briefly for host_direct_address rather than
    // assuming it'll never come.
    private void connectToNewHost() {
        if (hostDirectAddress != null) {
            tryDirectConnectThenFallback(hostDirectAddress);
            return;
        }

        waitingForDirectAddress = true;
        new Thread(() -> {
            try {
                Thread.sleep(DIRECT_ADDRESS_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (waitingForDirectAddress) {
                waitingForDirectAddress = false;
                System.out.println("[Campfire] No direct (UPnP) address from host after waiting - trying a hole-punched connection instead.");
                attemptHolePunch();
            }
        }, "campfire-direct-connect-wait").start();
    }

    // Confirms the address is actually reachable before committing to it -
    // UPnP can report success even when the mapping doesn't actually work
    // end-to-end (double NAT, a firewall blocking the port anyway, the
    // router lying). Falls back to the relay rather than leaving the
    // player staring at a failed connection with no explanation.
    private void tryDirectConnectThenFallback(String address) {
        new Thread(() -> {
            String host;
            int port;
            try {
                String[] parts = address.split(":");
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                System.out.println("[Campfire] Malformed direct address '" + address + "' - using the relay instead.");
                reconnectThroughRelay();
                return;
            }

            boolean reachable;
            try (java.net.Socket probe = new java.net.Socket()) {
                probe.connect(new java.net.InetSocketAddress(host, port), 3000);
                reachable = true;
            } catch (IOException e) {
                reachable = false;
            }

            if (reachable) {
                System.out.println("[Campfire] Host's direct address is reachable - connecting directly (no relay).");
                connectionTier = ConnectionTier.DIRECT;
                connectDirectly(host, port);
            } else {
                System.out.println("[Campfire] Host's direct address wasn't reachable - using the relay instead.");
                reconnectThroughRelay();
            }
        }, "campfire-direct-connect-probe").start();
    }

    private void connectDirectly(String host, int port) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            String addr = host + ":" + port;
            ServerAddress address = ServerAddress.parse(addr);
            ServerInfo info = new ServerInfo("Campfire", addr, false);
            ConnectScreen.connect(new TitleScreen(), client, address, info, false);
        });
    }

    // Second-tier fallback, tried only once UPnP has already been ruled out
    // (no host_direct_address arrived in time). Reflects our own address off
    // the coordinator's reflector, hands it to the host via punch_candidate,
    // waits for the host's own reflected address in return, then races
    // repeated connect() attempts against it (HolePuncher.punch) - the
    // standard TCP "simultaneous open" hole-punch technique. This only works
    // against NATs that allow an unsolicited inbound SYN to a port that just
    // sent an outbound one (most home routers; never true behind
    // carrier-grade NAT) - any failure here just falls back to the relay,
    // same as a failed UPnP attempt does.
    private void attemptHolePunch() {
        showMigrationToast("Optimizing connection...", "Trying to connect directly to your friend");
        new Thread(() -> {
            HolePuncher.ReflectedAddress own = HolePuncher.reflect(coordinatorBareHost, coordinatorPort, PUNCH_LOCAL_PORT);
            if (own == null) {
                System.out.println("[Campfire] Hole-punch unavailable (couldn't reach the coordinator's reflector) - using the relay instead.");
                reconnectThroughRelay();
                return;
            }

            CompletableFuture<String> future = new CompletableFuture<>();
            pendingPunchCandidate = future;

            JsonObject candidate = new JsonObject();
            candidate.addProperty("type", "punch_candidate");
            candidate.addProperty("address", own.ip() + ":" + own.port());
            sendJson(candidate);

            String hostAddress;
            try {
                hostAddress = future.get(PUNCH_CANDIDATE_REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                System.out.println("[Campfire] No reply from host for hole-punch (coordinator or host unreachable) - using the relay instead.");
                pendingPunchCandidate = null;
                reconnectThroughRelay();
                return;
            }
            pendingPunchCandidate = null;

            String ownAddress = own.ip() + ":" + own.port();
            if (ownAddress.equals(hostAddress)) {
                // Both of us reflected the exact same address - only possible
                // with no real NAT in between (e.g. both on one machine, or a
                // freak shared-NAT collision). Punching to our own address
                // would just be a meaningless loopback self-connect, not a
                // real link to the host - skip straight to the relay.
                System.out.println("[Campfire] Hole-punch candidate matched our own address (" + ownAddress + ") - no real NAT to punch through, using the relay instead.");
                reconnectThroughRelay();
                return;
            }

            String peerIp;
            int peerPort;
            try {
                String[] parts = hostAddress.split(":");
                peerIp = parts[0];
                peerPort = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                System.out.println("[Campfire] Malformed hole-punch address from host '" + hostAddress + "' - using the relay instead.");
                reconnectThroughRelay();
                return;
            }

            java.net.Socket punched = HolePuncher.punch(peerIp, peerPort, PUNCH_LOCAL_PORT, HOLE_PUNCH_TIMEOUT_MS, HOLE_PUNCH_ATTEMPT_TIMEOUT_MS);
            if (punched == null) {
                System.out.println("[Campfire] Hole-punch didn't connect (network likely blocks unsolicited inbound connections) - using the relay instead.");
                reconnectThroughRelay();
                return;
            }

            System.out.println("[Campfire] Hole-punched a direct connection to the host - no relay needed.");
            bridgePunchedSocketAndConnect(punched);
        }, "campfire-hole-punch").start();
    }

    // Only the current host reaches this: a friend proposed its own
    // reflected address and wants to punch a hole to us. We reflect our own
    // address the same way, hand it back so the friend can start punching
    // toward us, then start punching toward them ourselves - true
    // simultaneous open needs both sides attempting concurrently.
    private void handleIncomingPunchCandidate(String fromPlayerId, String friendAddress) {
        new Thread(() -> {
            HolePuncher.ReflectedAddress own = HolePuncher.reflect(coordinatorBareHost, coordinatorPort, PUNCH_LOCAL_PORT);
            if (own == null) {
                System.out.println("[Campfire] Couldn't reflect our own address for hole-punch with " + fromPlayerId + " - they'll fall back to the relay.");
                return;
            }

            JsonObject reply = new JsonObject();
            reply.addProperty("type", "punch_candidate");
            reply.addProperty("toPlayerId", fromPlayerId);
            reply.addProperty("address", own.ip() + ":" + own.port());
            sendJson(reply);

            String ownAddress = own.ip() + ":" + own.port();
            if (ownAddress.equals(friendAddress)) {
                // See the matching check in attemptHolePunch() - identical
                // reflected addresses means there's no real NAT in between
                // (both on one machine, or a freak shared-NAT collision), so
                // punching would just be a meaningless loopback self-connect.
                System.out.println("[Campfire] Hole-punch candidate from " + fromPlayerId + " matched our own address (" + ownAddress + ") - no real NAT to punch through, they'll fall back to the relay.");
                return;
            }

            String peerIp;
            int peerPort;
            try {
                String[] parts = friendAddress.split(":");
                peerIp = parts[0];
                peerPort = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                System.out.println("[Campfire] Malformed hole-punch address from " + fromPlayerId + " '" + friendAddress + "'.");
                return;
            }

            java.net.Socket punched = HolePuncher.punch(peerIp, peerPort, PUNCH_LOCAL_PORT, HOLE_PUNCH_TIMEOUT_MS, HOLE_PUNCH_ATTEMPT_TIMEOUT_MS);
            if (punched == null) {
                System.out.println("[Campfire] Hole-punch with " + fromPlayerId + " didn't connect - they'll fall back to the relay.");
                return;
            }

            java.net.Socket localServer;
            try {
                localServer = new java.net.Socket();
                localServer.connect(new java.net.InetSocketAddress("127.0.0.1", HOST_LAN_PORT), 5000);
            } catch (IOException e) {
                System.out.println("[Campfire] Hole-punch with " + fromPlayerId + " connected, but couldn't reach our own local server: " + e.getMessage());
                try {
                    punched.close();
                } catch (IOException ignored) {
                }
                return;
            }

            System.out.println("[Campfire] Hole-punched a direct connection with " + fromPlayerId + " - bridging to our local server.");
            SocketBridge.pump(punched, localServer, "host-" + fromPlayerId);
        }, "campfire-hole-punch-host-" + fromPlayerId).start();
    }

    // Once we (as a friend) have an established P2P socket to the host,
    // Minecraft still needs somewhere local to Direct Connect to - it can't
    // be handed a raw Socket directly, since ConnectScreen.connect() makes
    // its own Netty channel. A one-shot loopback listener on
    // PUNCH_BRIDGE_PORT (distinct from RELAY_LISTEN_PORT, which
    // RelayFriendListener already owns) exists just long enough to accept
    // that one connection and splice it straight into the punched socket.
    private void bridgePunchedSocketAndConnect(java.net.Socket punched) {
        java.net.ServerSocket bridgeListener;
        try {
            bridgeListener = new java.net.ServerSocket();
            bridgeListener.setReuseAddress(true);
            bridgeListener.bind(new java.net.InetSocketAddress("127.0.0.1", PUNCH_BRIDGE_PORT));
        } catch (IOException e) {
            System.out.println("[Campfire] Couldn't start local bridge listener for hole-punch: " + e.getMessage() + " - using the relay instead.");
            try {
                punched.close();
            } catch (IOException ignored) {
            }
            reconnectThroughRelay();
            return;
        }

        new Thread(() -> {
            try {
                java.net.Socket local = bridgeListener.accept();
                bridgeListener.close();
                SocketBridge.pump(local, punched, "friend");
            } catch (IOException e) {
                System.out.println("[Campfire] Hole-punch bridge listener failed: " + e.getMessage());
            }
        }, "campfire-punch-bridge-accept").start();

        connectionTier = ConnectionTier.PUNCHED;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            String addr = "127.0.0.1:" + PUNCH_BRIDGE_PORT;
            ServerAddress address = ServerAddress.parse(addr);
            ServerInfo info = new ServerInfo("Campfire (direct)", addr, false);
            ConnectScreen.connect(new TitleScreen(), client, address, info, false);
        });
    }

    // Friends call this once the coordinator confirms the new host is
    // actually ready - reconnects through our own local relay listener
    // without needing the user to Direct Connect manually again. The
    // fallback path when a direct connection isn't available or wasn't
    // reachable.
    private void reconnectThroughRelay() {
        connectionTier = ConnectionTier.RELAY;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            String addr = "127.0.0.1:" + RELAY_LISTEN_PORT;
            ServerAddress address = ServerAddress.parse(addr);
            ServerInfo info = new ServerInfo("Campfire Relay", addr, false);
            ConnectScreen.connect(new TitleScreen(), client, address, info, false);
        });
    }

    private void sendJson(JsonObject json) {
        if (webSocket == null) return;
        sendQueue.add(gson.toJson(json));
        pumpSendQueue();
    }

    /** Drains sendQueue one message at a time, never starting the next send until the previous one's future completes. */
    private void pumpSendQueue() {
        if (!sendInFlight.compareAndSet(false, true)) return; // another pump is already draining
        String msg = sendQueue.poll();
        if (msg == null) {
            sendInFlight.set(false);
            if (!sendQueue.isEmpty()) pumpSendQueue(); // item snuck in between poll() and the flag reset
            return;
        }
        WebSocket ws = webSocket;
        if (ws == null) {
            sendInFlight.set(false);
            return;
        }
        ws.sendText(msg, true).whenComplete((w, err) -> {
            if (err != null) {
                System.out.println("[Campfire] Failed sending to coordinator: " + err.getMessage());
            }
            sendInFlight.set(false);
            pumpSendQueue();
        });
    }

    // Announces us to the coordinator - both on first connect, and again
    // any time we reopen the managed world locally (see onClientJoinedWorld).
    private void sendHello() {
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("groupId", groupId);
        hello.addProperty("playerId", playerId);
        hello.addProperty("playerName", playerName);
        if (MOD_LIST_HASH != null) hello.addProperty("modHash", MOD_LIST_HASH);
        if (MOD_COUNT >= 0) hello.addProperty("modCount", MOD_COUNT);
        sendJson(hello);
    }

    // ---------- Upload path ----------

    // A failed upload here means the NEXT host migrates from a stale save -
    // the single worst outcome this mod can produce short of losing data
    // outright. A transient blip (the exact moment someone's quitting is
    // also when their machine is busiest) shouldn't be allowed to cause
    // that, so the send is retried a couple of times before giving up. Only
    // network-shaped failures retry; a 4xx (our request itself is bad)
    // never will heal on its own.
    private static final int UPLOAD_ATTEMPTS = 3;
    private static final long UPLOAD_RETRY_DELAY_MS = 2500;

    // Saves above this size go up in sequential parts instead of one POST:
    // the community coordinator sits behind a proxy that rejects any single
    // request body over ~100MB, so a grown world simply cannot travel as one
    // request there. Small saves keep the original one-shot multipart, which
    // every coordinator version accepts.
    private static final long CHUNKED_UPLOAD_THRESHOLD_BYTES = 64L * 1024 * 1024;
    private static final int UPLOAD_PART_BYTES = 32 * 1024 * 1024;

    private boolean uploadSave(Path worldSaveDir) {
        byte[] zipBytes;
        try {
            System.out.println("[Campfire] Zipping world save at " + worldSaveDir);
            // Under the transfer lock: the upload runs on its own thread now,
            // and a concurrent downloadCurrentSave swaps the very folder
            // being zipped out from underneath a walk otherwise.
            synchronized (worldTransferLock) {
                zipBytes = zipDirectory(worldSaveDir);
            }
            System.out.println("[Campfire] Zipped save, " + (zipBytes.length / 1024 / 1024) + " MB");
        } catch (IOException e) {
            System.out.println("[Campfire] Couldn't zip the world save: " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        if (uploadSaveViaWebSocket(zipBytes)) return true;
        System.out.println("[Campfire] Websocket save upload didn't complete - falling back to HTTP.");

        if (zipBytes.length > CHUNKED_UPLOAD_THRESHOLD_BYTES) {
            Boolean chunked = uploadSaveChunked(zipBytes);
            if (chunked != null) return chunked;
            // An older self-hosted coordinator doesn't know the chunked
            // protocol (404 on begin). It also has no proxy size cap, so the
            // one-shot path below is safe there at any size.
            System.out.println("[Campfire] Coordinator doesn't know the chunked upload protocol - sending as one request.");
        }

        byte[] body;
        String boundary = "----CampfireBoundary" + System.currentTimeMillis();
        try {
            body = buildMultipartBody(boundary, "save", "save.zip", zipBytes);
        } catch (IOException e) {
            System.out.println("[Campfire] Couldn't build the upload request: " + e.getMessage());
            return false;
        }

        for (int attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(coordinatorHost + "/groups/" + groupId + "/save"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        // Generous - this carries the whole zipped world, which
                        // can legitimately take a while on a slow upload - but
                        // still bounded, so a connection that goes silent mid
                        // transfer eventually fails and retries instead of
                        // hanging this attempt (and the whole retry loop)
                        // forever.
                        .timeout(Duration.ofMinutes(5))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

                // Fresh client per attempt (see newTransferClient) - a retry
                // riding the same stale pooled connection that failed the
                // first attempt just fails identically ("header parser
                // received no bytes", three times in a row in a real log).
                HttpResponse<String> response = newTransferClient().send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println("[Campfire] Upload response (" + response.statusCode() + "): " + response.body());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    recordUploadedSaveVersion(response.body());
                    return true;
                }
                if (response.statusCode() >= 400 && response.statusCode() < 500) return false;
            } catch (IOException e) {
                System.out.println("[Campfire] Upload attempt " + attempt + "/" + UPLOAD_ATTEMPTS + " failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (attempt < UPLOAD_ATTEMPTS) {
                try {
                    Thread.sleep(UPLOAD_RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    // The chunked upload: begin -> sequential raw-bytes parts -> commit,
    // each leg with the same retry contract as the one-shot path. Parts are
    // idempotent on the coordinator side (a resend of the last part is
    // acknowledged, not double-stored), so a lost response can't corrupt the
    // upload. Returns TRUE/FALSE for a definitive outcome, or null when the
    // coordinator doesn't know this protocol at all (404 on begin) so the
    // caller can fall back to the one-shot path.
    private Boolean uploadSaveChunked(byte[] zipBytes) {
        try {
            HttpResponse<String> begin = sendTransferWithRetries(HttpRequest.newBuilder()
                    .uri(URI.create(coordinatorHost + "/groups/" + groupId + "/save/begin"))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());
            if (begin != null && begin.statusCode() == 404) return null;
            if (begin == null || begin.statusCode() < 200 || begin.statusCode() >= 300) return false;
            String uploadId = JsonParser.parseString(begin.body()).getAsJsonObject().get("uploadId").getAsString();

            int parts = (zipBytes.length + UPLOAD_PART_BYTES - 1) / UPLOAD_PART_BYTES;
            for (int i = 0; i < parts; i++) {
                int off = i * UPLOAD_PART_BYTES;
                byte[] part = java.util.Arrays.copyOfRange(zipBytes, off, Math.min(off + UPLOAD_PART_BYTES, zipBytes.length));
                System.out.println("[Campfire] Uploading save part " + (i + 1) + "/" + parts
                        + " (" + (part.length / 1024 / 1024) + " MB)");
                HttpResponse<String> r = sendTransferWithRetries(HttpRequest.newBuilder()
                        .uri(URI.create(coordinatorHost + "/groups/" + groupId + "/save/part/" + i + "?uploadId=" + uploadId))
                        .timeout(Duration.ofMinutes(5))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(part))
                        .build());
                if (r == null || r.statusCode() < 200 || r.statusCode() >= 300) {
                    System.out.println("[Campfire] Save part " + (i + 1) + "/" + parts + " didn't go through - giving up on this upload.");
                    return false;
                }
            }

            JsonObject commitBody = new JsonObject();
            commitBody.addProperty("uploadId", uploadId);
            commitBody.addProperty("parts", parts);
            HttpResponse<String> commit = sendTransferWithRetries(HttpRequest.newBuilder()
                    .uri(URI.create(coordinatorHost + "/groups/" + groupId + "/save/commit"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(1))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(commitBody)))
                    .build());
            if (commit == null || commit.statusCode() < 200 || commit.statusCode() >= 300) return false;
            System.out.println("[Campfire] Chunked upload committed: " + commit.body());
            recordUploadedSaveVersion(commit.body());
            return true;
        } catch (Exception e) {
            System.out.println("[Campfire] Chunked upload failed: " + e.getMessage());
            return false;
        }
    }

    // Same retry contract as the one-shot upload loop: network-shaped
    // failures and 5xx retry (they can heal), any other response is returned
    // for the caller to judge. Null means no attempt produced a usable
    // response. Fresh client per attempt for the same dead-pooled-connection
    // reason as everywhere else.
    private HttpResponse<String> sendTransferWithRetries(HttpRequest request) {
        for (int attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = newTransferClient().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 500) return response;
                System.out.println("[Campfire] Transfer got " + response.statusCode()
                        + " (attempt " + attempt + "/" + UPLOAD_ATTEMPTS + "): " + response.body());
            } catch (IOException e) {
                System.out.println("[Campfire] Transfer attempt " + attempt + "/" + UPLOAD_ATTEMPTS + " failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (attempt < UPLOAD_ATTEMPTS) {
                try {
                    Thread.sleep(UPLOAD_RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    // ---------- Save transfer over the coordinator WebSocket (preferred) ----------
    //
    // Same destination/extraction logic as the HTTP paths above - this just
    // rides the persistent connection every client already has open instead
    // of separate HTTP requests, so a whole migration's transfer costs a
    // handful of messages on an already-open socket instead of dozens of
    // separately billed HTTP requests (and sidesteps the community
    // coordinator's proxy body-size cap entirely, since there's no single
    // request body to cap). Both methods return a definitive "didn't work"
    // value (false / null) for ANY reason - coordinator doesn't speak this
    // protocol yet, a real error, a timeout - so the caller always has one
    // answer: fall back to the proven HTTP path, unchanged.

    private static final long SAVE_TRANSFER_ACK_TIMEOUT_MS = 10_000;
    private static final int WS_TRANSFER_PART_BYTES = 512 * 1024;

    // Blocks (off the websocket thread - callers are always the upload/
    // download background thread) until a message of successType or
    // errorType lands in saveTransferInbox, or the timeout passes with
    // nothing at all - which is exactly what happens talking to a
    // coordinator that doesn't know this protocol, since an unrecognized
    // message type is silently ignored server-side. Returns null on timeout
    // or an explicit error.
    private JsonObject awaitSaveTransferMessage(String successType, String errorType) throws InterruptedException {
        long deadline = System.currentTimeMillis() + SAVE_TRANSFER_ACK_TIMEOUT_MS;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return null;
            JsonObject msg = saveTransferInbox.poll(remaining, TimeUnit.MILLISECONDS);
            if (msg == null) return null;
            String type = msg.has("type") ? msg.get("type").getAsString() : null;
            if (successType.equals(type)) return msg;
            if (errorType.equals(type)) {
                System.out.println("[Campfire] Save transfer error over websocket: " + msg);
                return null;
            }
            // Anything else is a stray leftover - keep waiting for the real answer.
        }
    }

    private boolean uploadSaveViaWebSocket(byte[] zipBytes) {
        if (webSocket == null) return false;
        saveTransferInbox.clear();
        try {
            JsonObject begin = new JsonObject();
            begin.addProperty("type", "save_upload_begin");
            sendJson(begin);
            JsonObject beginAck = awaitSaveTransferMessage("save_upload_begin_ack", "save_upload_error");
            if (beginAck == null) return false;
            String uploadId = beginAck.get("uploadId").getAsString();

            int parts = Math.max(1, (zipBytes.length + WS_TRANSFER_PART_BYTES - 1) / WS_TRANSFER_PART_BYTES);
            for (int i = 0; i < parts; i++) {
                int off = i * WS_TRANSFER_PART_BYTES;
                byte[] part = java.util.Arrays.copyOfRange(zipBytes, off, Math.min(off + WS_TRANSFER_PART_BYTES, zipBytes.length));
                JsonObject partMsg = new JsonObject();
                partMsg.addProperty("type", "save_upload_part");
                partMsg.addProperty("uploadId", uploadId);
                partMsg.addProperty("index", i);
                partMsg.addProperty("data", Base64.getEncoder().encodeToString(part));
                sendJson(partMsg);
                JsonObject ack = awaitSaveTransferMessage("save_upload_part_ack", "save_upload_error");
                if (ack == null) return false;
                System.out.println("[Campfire] Uploaded save part " + (i + 1) + "/" + parts + " over websocket");
            }

            JsonObject commit = new JsonObject();
            commit.addProperty("type", "save_upload_commit");
            commit.addProperty("uploadId", uploadId);
            sendJson(commit);
            JsonObject commitAck = awaitSaveTransferMessage("save_upload_commit_ack", "save_upload_error");
            if (commitAck == null) return false;

            recordUploadedSaveVersion(commitAck.toString());
            System.out.println("[Campfire] Save uploaded over websocket -> v" + knownSaveVersion);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private byte[] downloadSaveViaWebSocket(String groupId) {
        if (webSocket == null) return null;
        saveTransferInbox.clear();
        try {
            JsonObject request = new JsonObject();
            request.addProperty("type", "save_download_request");
            sendJson(request);

            JsonObject begin = awaitSaveTransferMessage("save_download_begin", "save_download_error");
            if (begin == null) return null;
            int totalParts = begin.get("totalParts").getAsInt();
            int totalBytes = begin.get("totalBytes").getAsInt();

            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(totalBytes, 0));
            for (int i = 0; i < totalParts; i++) {
                JsonObject part = awaitSaveTransferMessage("save_download_part", "save_download_error");
                if (part == null) return null;
                out.write(Base64.getDecoder().decode(part.get("data").getAsString()));
            }
            JsonObject done = awaitSaveTransferMessage("save_download_done", "save_download_error");
            if (done == null) return null;

            byte[] result = out.toByteArray();
            if (result.length != totalBytes) {
                System.out.println("[Campfire] Websocket download size mismatch (" + result.length + " != " + totalBytes + ") - falling back to HTTP.");
                return null;
            }
            System.out.println("[Campfire] Save downloaded over websocket (" + (result.length / 1024 / 1024) + " MB)");
            return result;
        } catch (IOException e) {
            // Never actually thrown by ByteArrayOutputStream in practice, but
            // its write() signature declares it.
            System.out.println("[Campfire] Websocket download failed: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // The coordinator's upload response echoes the version our upload just
    // became. Recording it means our local copy is provably the latest -
    // next "Open the World" skips the download.
    private void recordUploadedSaveVersion(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (json.has("saveVersion")) {
                localSaveVersion = json.get("saveVersion").getAsInt();
                knownSaveVersion = localSaveVersion;
                persistConfig();
            }
        } catch (Exception e) {
            System.out.println("[Campfire] Couldn't parse upload response for saveVersion - will re-download next open.");
        }
    }

    private byte[] zipDirectory(Path sourceDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // World data (region files, NBT) is already compressed internally -
            // re-compressing it here just burns CPU for almost no size benefit,
            // and is the main thing slowing down this step. Store instead of
            // deflate so this is basically a straight copy.
            zos.setLevel(Deflater.NO_COMPRESSION);
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

    // If we already have our own local copy of the managed world open (e.g.
    // someone manually reopened it instead of waiting for a coordinator
    // hand-off - see the "Known design gap" in CLAUDE.md), becoming the new
    // host must NOT just leave that server running: downloadCurrentSave is
    // about to delete and replace the on-disk save out from under it, and an
    // already-running IntegratedServer never re-reads level.dat off disk. Left
    // alone, that stale server just keeps running on its own divergent
    // in-memory state and silently overwrites the freshly-downloaded/swapped
    // save the next time it autosaves or the player quits - which is exactly
    // how a guest's inventory/position survived the swap on disk but still
    // got lost. So: close it first, and wait for it to actually finish before
    // downloading, so the world the new host opens next is genuinely the one
    // we just unpacked.
    private void stopLocalServerIfRunningManagedWorld() {
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null || !isManagedWorld(server)) return;

        System.out.println("[Campfire] Our own local copy of this world is already open - closing it first so the downloaded save actually takes effect.");
        internalRestartInProgress = true;
        CountDownLatch latch = new CountDownLatch(1);
        internalRestartLatch = latch;

        MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().disconnect());

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                System.out.println("[Campfire] Timed out waiting for our local copy to close - proceeding anyway.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            internalRestartInProgress = false;
            internalRestartLatch = null;
        }
    }

    // ---------- Download path ----------

    // How many of each safety artifact to keep around. Two is deliberate:
    // one might be mid-write when things go wrong, so the one before it is
    // the real safety net - and worlds are big, so hoarding more than that
    // silently eats a player's disk.
    private static final int KEEP_LOCAL_BACKUPS = 2;
    private static final int KEEP_MIGRATION_ZIPS = 2;

    // The old version of this method unpacked the downloaded zip DIRECTLY
    // over the live world folder - deleting it first. A corrupt/truncated
    // download (flaky Wi-Fi mid-migration is exactly when this runs) would
    // therefore destroy the only local copy AND fail to replace it: total
    // local data loss. Now nothing touches the live folder until a complete,
    // fully-unpacked replacement is sitting next to it, and the old folder
    // is moved aside (a rename - instant even for multi-GB worlds), not
    // deleted, so even a failure after that point is recoverable.
    //
    // Called from two unsynchronized places - the migrate handler's active-
    // handoff branch (websocket thread) and openManagedWorld's background
    // thread (a manual "Open the World" click) - and nothing stopped both
    // from racing to download+extract into the SAME .campfire-incoming
    // folder at once. A real test hit this: the player, disconnected while
    // waiting for the (correctly delayed, up to MIGRATE_UPLOAD_TIMEOUT_MS)
    // migrate broadcast, manually clicked Open before it arrived. Whichever
    // finished first renamed .campfire-incoming out from under the other
    // mid-extraction, crashing it with a bare NoSuchFileException on a
    // region file. worldTransferLock forces the two calls to run one at a
    // time; the version check right after acquiring it turns the loser into
    // a no-op success (the winner already got the world to this exact
    // version) instead of a redundant second download.
    private boolean downloadCurrentSave(String groupId, int saveVersion, String outgoingHostId) {
        synchronized (worldTransferLock) {
        if (localSaveVersion == saveVersion && managedWorldExistsLocally()) {
            System.out.println("[Campfire] Already at save v" + saveVersion + " - a concurrent download/open must have just finished it, skipping duplicate.");
            return true;
        }
        try {
            byte[] zipBytes = downloadSaveViaWebSocket(groupId);
            if (zipBytes == null) {
                System.out.println("[Campfire] Websocket save download didn't complete - falling back to HTTP.");
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(coordinatorHost + "/groups/" + groupId + "/save"))
                        // Same reasoning as the upload's timeout - this is exactly
                        // the request that sat hung with no error for minutes,
                        // stuck on a disabled "Getting the world ready..." button,
                        // when a network hiccup left the connection open but silent.
                        .timeout(Duration.ofMinutes(5))
                        .GET()
                        .build();

                // Same retry discipline as the upload, for the same reason: a
                // failed download here means the incoming host opens a stale
                // world (or nothing at all). Fresh client per attempt so a stale
                // pooled connection can't fail every attempt identically.
                for (int attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++) {
                    try {
                        HttpResponse<byte[]> response = newTransferClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
                        System.out.println("[Campfire] Download response: " + response.statusCode()
                                + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                        if (response.statusCode() == 200) {
                            zipBytes = response.body();
                            break;
                        } else if (response.statusCode() == 404) {
                            System.out.println("[Campfire] No save uploaded yet for group " + groupId);
                            return true; // first-ever host of a brand new group - nothing to download yet, not a failure
                        } else if (response.statusCode() >= 400 && response.statusCode() < 500) {
                            return false; // our request is wrong - retrying won't heal it
                        }
                    } catch (IOException e) {
                        System.out.println("[Campfire] Download attempt " + attempt + "/" + UPLOAD_ATTEMPTS + " failed: " + e.getMessage());
                    }
                    if (attempt < UPLOAD_ATTEMPTS) Thread.sleep(UPLOAD_RETRY_DELAY_MS);
                }
                if (zipBytes == null) return false;
            }

            {
                Path gameDir = FabricLoader.getInstance().getGameDir();

                Path migrationDir = gameDir.resolve("migration");
                Files.createDirectories(migrationDir);
                Path downloadedZip = migrationDir.resolve("save-v" + saveVersion + ".zip");
                Files.write(downloadedZip, zipBytes);
                System.out.println("[Campfire] Saved downloaded world to " + downloadedZip);
                pruneOldestFiles(migrationDir, KEEP_MIGRATION_ZIPS);

                Path savesRoot = gameDir.resolve("saves");
                Path worldDir = savesRoot.resolve(groupId);

                // Stage 1: unpack + player-data swap into a scratch dir. Any
                // corruption throws HERE, with the live world untouched.
                Path incomingDir = savesRoot.resolve(groupId + ".campfire-incoming");
                extractZipToDirectory(zipBytes, incomingDir);
                swapPlayerData(incomingDir, outgoingHostId, this.playerId);

                // Stage 2: move the live world aside as a timestamped backup,
                // then move the verified replacement into its place. If that
                // second move somehow fails, the backup goes straight back -
                // the player is never left with no world at all.
                if (Files.exists(worldDir)) {
                    Path backupRoot = savesRoot.resolve("_campfire-backups");
                    Files.createDirectories(backupRoot);
                    Path backup = backupRoot.resolve(groupId + "-" + System.currentTimeMillis());
                    Files.move(worldDir, backup);
                    try {
                        Files.move(incomingDir, worldDir);
                    } catch (IOException e) {
                        Files.move(backup, worldDir);
                        throw e;
                    }
                    System.out.println("[Campfire] Previous local copy kept at " + backup);
                    pruneOldestDirectories(backupRoot, groupId + "-", KEEP_LOCAL_BACKUPS);
                } else {
                    Files.move(incomingDir, worldDir);
                }
                System.out.println("[Campfire] Unpacked save into " + worldDir);
                localSaveVersion = saveVersion;
                // The world's display name travels inside the save - grab it
                // so the campfire list can label this campfire by its world
                // ("Our Campfire") instead of its code.
                String worldName = readWorldDisplayName(worldDir);
                if (worldName != null) activeWorldName = worldName;
                persistConfig();
                return true;
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("[Campfire] Download failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        }
    }

    // Best-effort read of the display name a world's creator gave it -
    // level.dat's Data.LevelName. Null when unreadable; callers keep
    // whatever name they already had.
    private static String readWorldDisplayName(Path worldDir) {
        try {
            NbtCompound root = NbtIo.readCompressed(worldDir.resolve("level.dat").toFile());
            String name = root.getCompound("Data").getString("LevelName");
            return name == null || name.isBlank() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    // The migration/ zip stash and saves/_campfire-backups/ both grow by one
    // entry per migration - on an active group that's every play session, and
    // a multi-GB world would quietly fill the disk within weeks. Keep the
    // newest few of each, delete the rest.
    private void pruneOldestFiles(Path dir, int keep) {
        try (var listing = Files.list(dir)) {
            java.util.List<Path> files = new java.util.ArrayList<>(listing.filter(Files::isRegularFile).toList());
            files.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime((Path) p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }).reversed());
            for (int i = keep; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException e) {
            System.out.println("[Campfire] Couldn't prune old files in " + dir + ": " + e.getMessage());
        }
    }

    private void pruneOldestDirectories(Path root, String namePrefix, int keep) {
        try (var listing = Files.list(root)) {
            // Backup names end in System.currentTimeMillis(), all 13 digits in
            // this era, so a plain descending name sort is newest-first.
            java.util.List<Path> dirs = new java.util.ArrayList<>(listing
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(namePrefix))
                    .toList());
            dirs.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            for (int i = keep; i < dirs.size(); i++) {
                deleteRecursively(dirs.get(i));
            }
        } catch (IOException e) {
            System.out.println("[Campfire] Couldn't prune old backups in " + root + ": " + e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
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
                System.out.println("[Campfire] No level.dat found yet, skipping player-data swap.");
                return;
            }

            NbtCompound root = NbtIo.readCompressed(levelDatFile);
            NbtCompound data = root.getCompound("Data");

            Path playerDataDir = worldDir.resolve("playerdata");
            Files.createDirectories(playerDataDir);

            if (data.contains("Player")) {
                NbtCompound outgoingPlayerData = data.getCompound("Player");
                // Whose data is this? Trust the NBT itself first: the Player
                // compound carries its owner's UUID (4-int array in 1.20),
                // and that's ground truth no matter which code path got us
                // here or how stale/absent the caller's hint is. The hint is
                // only a fallback for a save whose Player tag somehow lacks
                // a UUID. Getting this wrong loses the previous host's whole
                // inventory - it happened in a real test when the hint was
                // derived from an already-nulled currentHostId.
                String ownerId = readPlayerUuid(outgoingPlayerData);
                if (ownerId == null) ownerId = outgoingHostId;
                if (ownerId != null) {
                    File outgoingFile = playerDataDir.resolve(ownerId + ".dat").toFile();
                    NbtIo.writeCompressed(outgoingPlayerData, outgoingFile);
                    System.out.println("[Campfire] Preserved previous host's player data -> " + outgoingFile.getName());
                } else {
                    System.out.println("[Campfire] Player data in level.dat has no UUID and no hint - can't preserve it.");
                }
            } else {
                System.out.println("[Campfire] No outgoing host data to preserve (first host of this save).");
            }

            File incomingFile = playerDataDir.resolve(incomingHostId + ".dat").toFile();
            if (incomingFile.exists()) {
                NbtCompound incomingPlayerData = NbtIo.readCompressed(incomingFile);
                data.put("Player", incomingPlayerData);
                System.out.println("[Campfire] Restored incoming host's own player data into level.dat.");
            } else {
                data.remove("Player");
                System.out.println("[Campfire] No prior data for incoming host - they'll spawn fresh.");
            }

            root.put("Data", data);
            NbtIo.writeCompressed(root, levelDatFile);
            System.out.println("[Campfire] Player-data swap complete.");
        } catch (IOException e) {
            System.out.println("[Campfire] Player-data swap failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Minecraft 1.20 stores an entity's UUID as an int-array tag of 4 ints
    // (most-significant to least). Returns the canonical lowercase-dashed
    // string form - the same shape playerdata/<uuid>.dat filenames use -
    // or null if the tag is missing/malformed.
    private static String readPlayerUuid(NbtCompound playerData) {
        int[] parts = playerData.getIntArray("UUID");
        if (parts.length != 4) return null;
        long most = ((long) parts[0] << 32) | (parts[1] & 0xFFFFFFFFL);
        long least = ((long) parts[2] << 32) | (parts[3] & 0xFFFFFFFFL);
        return new java.util.UUID(most, least).toString();
    }

    // ---------- WebSocket listener ----------

    private class CoordinatorListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            coordinatorStatus = CoordinatorStatus.CONNECTED;
            lastCoordinatorInboundMs = System.currentTimeMillis();
            sendHello();
            WebSocket.Listener.super.onOpen(ws);
        }

        // Pings/pongs count as signs of life for the keepalive silence
        // check - the coordinator pings us every ~30s and answers our own
        // pings, so a healthy-but-quiet link never looks dead. Both
        // delegate to the default implementation (which handles frame
        // accounting/pong replies).
        @Override
        public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer message) {
            lastCoordinatorInboundMs = System.currentTimeMillis();
            return WebSocket.Listener.super.onPing(ws, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, java.nio.ByteBuffer message) {
            lastCoordinatorInboundMs = System.currentTimeMillis();
            return WebSocket.Listener.super.onPong(ws, message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            System.out.println("[Campfire] Coordinator connection closed (" + statusCode + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ")");
            onCoordinatorConnectionLost();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            System.out.println("[Campfire] Coordinator connection error: " + error.getMessage());
            onCoordinatorConnectionLost();
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            lastCoordinatorInboundMs = System.currentTimeMillis();
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
            JsonObject json;
            try {
                json = JsonParser.parseString(message).getAsJsonObject();
            } catch (Exception e) {
                System.out.println("[Campfire] Failed to parse message: " + message);
                return;
            }

            String type = json.has("type") ? json.get("type").getAsString() : null;
            if (type == null) return;

            // relay_data fires once per network chunk tunneled through the relay -
            // logging every single one is what was flooding the console. Everything
            // else (state changes, migrations, errors) is rare enough to log in full.
            if (!type.equals("relay_data")) {
                System.out.println("[Campfire] Received: " + message);
            }

            switch (type) {
                case "state" -> {
                    currentHostId = (json.has("hostId") && !json.get("hostId").isJsonNull())
                            ? json.get("hostId").getAsString() : null;
                    // Authoritative from the coordinator - covers both "no one's
                    // hosting" and "the current host hasn't had UPnP resolve yet".
                    hostDirectAddress = (json.has("hostDirectAddress") && !json.get("hostDirectAddress").isJsonNull())
                            ? json.get("hostDirectAddress").getAsString() : null;
                    if (json.has("saveVersion") && !json.get("saveVersion").isJsonNull()) {
                        knownSaveVersion = json.get("saveVersion").getAsInt();
                    }
                    updateMemberRoster(json);
                    updateHostingMode();

                    if (autoConnectOnNextState) {
                        autoConnectOnNextState = false;
                        if (currentHostId != null && !isHost()) {
                            System.out.println("[Campfire] Joined - a friend is already hosting, connecting you now.");
                            showMigrationToast("Joined your Campfire!", "Connecting you to the host...");
                            connectToNewHost();
                        } else if (isNextUp()) {
                            showMigrationToast("Joined your Campfire!", "You're up first - open the world whenever you're ready");
                        } else if (currentHostId == null) {
                            showMigrationToast("Joined your Campfire!", "No one's hosting yet - ask a friend to open the world");
                        }
                    }
                }
                case "host_direct_address" -> {
                    hostDirectAddress = (json.has("address") && !json.get("address").isJsonNull())
                            ? json.get("address").getAsString() : null;
                    System.out.println("[Campfire] Host direct address: " + (hostDirectAddress != null ? hostDirectAddress : "(none - UPnP unavailable)"));
                    if (waitingForDirectAddress && hostDirectAddress != null && !isHost()) {
                        waitingForDirectAddress = false;
                        tryDirectConnectThenFallback(hostDirectAddress);
                    }
                }
                case "punch_candidate" -> {
                    String address = json.has("address") && !json.get("address").isJsonNull()
                            ? json.get("address").getAsString() : null;
                    if (address == null) break;

                    if (isHost()) {
                        // A friend proposed its address to us - fromPlayerId
                        // is who to reply to and punch toward.
                        String fromPlayerId = json.has("fromPlayerId") && !json.get("fromPlayerId").isJsonNull()
                                ? json.get("fromPlayerId").getAsString() : null;
                        if (fromPlayerId != null) {
                            handleIncomingPunchCandidate(fromPlayerId, address);
                        }
                    } else if (pendingPunchCandidate != null) {
                        // The host replied to our own attemptHolePunch() request.
                        pendingPunchCandidate.complete(address);
                    }
                }
                case "migrate" -> {
                    // A migrate is a DESIGNATION, mirroring the coordinator:
                    // its hostId stays null until the designee's im_hosting
                    // lands, so ours must too. Setting currentHostId here used
                    // to make the designee's own screen claim "(you) hosting"
                    // (and spin up the host multiplexer) for a world that
                    // wasn't running, and friends' screens offer "Join X's
                    // World" toward a host that didn't exist yet. Who the
                    // PREVIOUS host was rides on the message itself now - the
                    // local currentHostId is useless for that, because the
                    // departure 'state' broadcast already nulled it moments
                    // before this arrives.
                    String newHostId = json.get("newHostId").getAsString();
                    int saveVersion = json.get("saveVersion").getAsInt();
                    String previousHostId = json.has("previousHostId") && !json.get("previousHostId").isJsonNull()
                            ? json.get("previousHostId").getAsString() : null;
                    knownSaveVersion = Math.max(knownSaveVersion, saveVersion);
                    hostDirectAddress = null; // the old host's address is stale either way

                    // Every migrate follows a real host departure now (the
                    // old promote-on-hello path is gone), so the only question
                    // is whether it interrupted OUR live play: were we in the
                    // shared session moments ago? That's what makes handoffs
                    // hands-free for active players while never hijacking
                    // someone idling in menus.
                    boolean sessionJustInterrupted = inSharedSession
                            || System.currentTimeMillis() - lastSharedSessionEndMs < ACTIVE_HANDOFF_WINDOW_MS;

                    if (newHostId.equals(playerId)) {
                        if (!sessionJustInterrupted) {
                            // No toast here: the coordinator's follow-up
                            // 'state' (no host, us at queue front) triggers
                            // the "You're next up to host" toast, which
                            // already says exactly what to do next.
                            System.out.println("[Campfire] Designated next host (save v" + saveVersion + ") - waiting for the player to open the world.");
                            return;
                        }

                        lastActiveHandoffMs = System.currentTimeMillis();
                        handoffOpenPending = true;

                        System.out.println("[Campfire] We are the new host, downloading save v" + saveVersion);
                        migrationInProgress = true;
                        showMigrationToast("Migrating world...", "Hang tight, getting things ready");

                        // Off the websocket thread. This handler runs inside
                        // the WebSocket listener's onText, and blocking there
                        // (stopLocalServer... waits up to 30s, the download
                        // can take minutes) freezes ALL coordinator message
                        // delivery - roster updates, the save_ready that
                        // supersedes this very download, even the transport's
                        // ping replies. A real test saw exactly that: the
                        // wedged client got reaped as unresponsive
                        // mid-handoff and went blind for a minute.
                        new Thread(() -> {
                            stopLocalServerIfRunningManagedWorld();
                            boolean downloaded = downloadCurrentSave(groupId, saveVersion, previousHostId);

                            migrationInProgress = false;
                            if (downloaded) {
                                showMigrationToast("World ready!", "Reopening it as yours...");
                                startManagedWorldIfIdle();
                            } else {
                                handoffOpenPending = false;
                                showMigrationToast("Migration problem", "Couldn't download the world - check your connection and try reconnecting");
                            }
                        }, "campfire-handoff").start();
                    } else {
                        // Tell the friends who's taking over, so the pause
                        // while the new host downloads/opens the world reads
                        // as progress instead of a mysterious dead connection.
                        String newHostName = null;
                        for (GroupMember m : members) {
                            if (m.id().equals(newHostId)) {
                                newHostName = m.name();
                                break;
                            }
                        }
                        showMigrationToast("Passing the torch",
                                (newHostName != null ? newHostName : "A friend") + " is getting the world ready...");
                        // If this handoff just cost us our session, we're owed
                        // an automatic reconnect when the new host is up -
                        // however long their download takes.
                        if (sessionJustInterrupted) {
                            awaitingHandoffSinceMs = System.currentTimeMillis();
                        }
                    }
                }
                case "relay_open" -> {
                    if (isHost() && relayHostMultiplexer != null) {
                        String streamId = json.get("streamId").getAsString();
                        String fromPlayerId = json.has("fromPlayerId") && !json.get("fromPlayerId").isJsonNull()
                                ? json.get("fromPlayerId").getAsString() : null;
                        relayHostMultiplexer.openStream(streamId, fromPlayerId);
                    }
                }
                case "relay_data" -> {
                    String streamId = json.get("streamId").getAsString();
                    byte[] payload = Base64.getDecoder().decode(json.get("data").getAsString());
                    if (isHost()) {
                        if (relayHostMultiplexer != null) relayHostMultiplexer.feedData(streamId, payload);
                    } else {
                        if (relayFriendListener != null) relayFriendListener.feedData(streamId, payload);
                    }
                }
                case "relay_close" -> {
                    String streamId = json.get("streamId").getAsString();
                    if (isHost()) {
                        if (relayHostMultiplexer != null) relayHostMultiplexer.closeStream(streamId);
                    } else {
                        if (relayFriendListener != null) relayFriendListener.closeStream(streamId);
                    }
                }
                case "relay_reset" -> {
                    System.out.println("[Campfire] Host disconnected - tearing down relay streams.");
                    if (relayFriendListener != null) relayFriendListener.closeAllStreams();
                }
                case "relay_error" -> {
                    String streamId = json.has("streamId") && !json.get("streamId").isJsonNull()
                            ? json.get("streamId").getAsString() : null;
                    String errMsg = json.has("reason") && !json.get("reason").isJsonNull()
                            ? json.get("reason").getAsString()
                            : json.has("message") && !json.get("message").isJsonNull()
                                    ? json.get("message").getAsString() : "unknown error";
                    System.out.println("[Campfire] Relay error" + (streamId != null ? " for stream " + streamId : "") + ": " + errMsg);
                    if ("no_host".equals(errMsg)) {
                        showMigrationToast("No one is hosting yet", "Ask a friend to open the world, then try connecting again");
                    }
                    if (streamId != null && relayFriendListener != null) relayFriendListener.abortStream(streamId);
                }
                case "host_confirmed" -> {
                    String hostId = json.get("hostId").getAsString();
                    currentHostId = hostId;
                    hostDirectAddress = null; // new host - any previously known address is stale
                    updateHostingMode();
                    if (!hostId.equals(playerId)) {
                        // Auto-reconnect ONLY when a handoff interrupted our
                        // own play (the whole point is not interrupting it) -
                        // and never out of an unrelated world. A friend idling
                        // in menus gets told, not yanked: their status screen
                        // now reads "Join <name>'s World".
                        long now = System.currentTimeMillis();
                        boolean owedReconnect = inSharedSession
                                || now - lastSharedSessionEndMs < ACTIVE_HANDOFF_WINDOW_MS
                                || (awaitingHandoffSinceMs > 0 && now - awaitingHandoffSinceMs < AWAITING_HANDOFF_MAX_MS);
                        boolean inUnrelatedWorld = MinecraftClient.getInstance().world != null && !inSharedSession;
                        if (owedReconnect && !inUnrelatedWorld) {
                            awaitingHandoffSinceMs = 0;
                            System.out.println("[Campfire] New host is ready - reconnecting.");
                            connectToNewHost();
                        } else {
                            String hostName = null;
                            for (GroupMember m : members) {
                                if (m.id().equals(hostId)) {
                                    hostName = m.name();
                                    break;
                                }
                            }
                            System.out.println("[Campfire] " + hostId + " is hosting - not auto-joining (we weren't in the session).");
                            showMigrationToast((hostName != null ? hostName : "A friend") + " lit the campfire",
                                    "The world is open - join from the Campfire screen");
                            playCrackle();
                        }
                    }
                }
                case "error" -> {
                    String reason = json.has("reason") && !json.get("reason").isJsonNull()
                            ? json.get("reason").getAsString() : null;
                    System.out.println("[Campfire] Server error: " + json);
                    if ("invalid_id".equals(reason)) {
                        showMigrationToast("Couldn't join", "That invite code isn't valid - double check it and try again");
                    } else if ("already_hosting".equals(reason)) {
                        // We just opened our own local copy to LAN and asked to
                        // become host, but lost a race (or manually reopened a
                        // stale copy) while someone else was already live. That
                        // local server is now a forked, uncoordinated copy -
                        // left running, the player would end up playing alone
                        // in it and its save would get uploaded over the real
                        // group save on quit (onServerStopped can't tell it
                        // apart from a legitimate host otherwise). Close it
                        // immediately rather than let that happen.
                        System.out.println("[Campfire] Someone else is already hosting - closing our own accidental copy.");
                        showMigrationToast("Someone's already hosting", "Closing this copy - join them from the Campfire screen");
                        // Off the websocket thread - stopLocalServer... blocks
                        // until the server actually closes (up to 30s), and
                        // blocking HERE freezes all coordinator message
                        // delivery (see the migrate handler's identical fix).
                        new Thread(() -> {
                            stopLocalServerIfRunningManagedWorld();
                            // disconnect() with no target screen leaves the player
                            // on a bare black screen - fine for the migrate flow,
                            // which immediately follows it with a new world load
                            // and its own screen, but there's no such follow-up
                            // here, so send them somewhere real.
                            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new TitleScreen()));
                        }, "campfire-close-fork").start();
                    }
                }
                case "save_upload_begin_ack", "save_upload_part_ack", "save_upload_commit_ack", "save_upload_error",
                        "save_download_begin", "save_download_part", "save_download_done", "save_download_error" -> {
                    // A save transfer in progress is waiting on exactly this,
                    // synchronously, from its own thread - see
                    // awaitSaveTransferMessage. Never handled inline here:
                    // uploadSaveViaWebSocket/downloadSaveViaWebSocket own the
                    // whole conversation once they send the first message.
                    saveTransferInbox.offer(json);
                }
                default -> {
                    // save_ready: not yet used by the mod
                }
            }
        }
    }
}