// Coordinator server for the host-migration Minecraft mod.
//
// Responsibilities:
//   1. Track which players are currently online, per friend-group ("group").
//   2. Know the join order, so it can pick who becomes host next.
//   3. Broadcast a "migrate" message telling everyone to reconnect to the new host.
//   4. Hold the latest zipped world save so the next host can pull it before
//      opening the world (simple file relay, nothing fancy).
//   5. Relay live Minecraft traffic between friends and whoever's hosting,
//      so friends can join and play in real time without any port forwarding
//      on anyone's end. The coordinator never looks inside the bytes - it's
//      a switchboard, not a game server.
//
// This is intentionally dumb and small. It's a phone book + a dead-drop for
// the save file + a traffic switchboard. That's what keeps it free to run.

const http = require('http');
const crypto = require('crypto');
const express = require('express');
const multer = require('multer');
const { WebSocketServer } = require('ws');
const fs = require('fs');
const path = require('path');

// Last-resort safety net: this process holds every group's live state in
// memory (see the file header), so an uncaught error anywhere - not just in
// the per-message handler below, which has its own try/catch - killing the
// whole process would silently drop every connected player in every group
// at once, not just whoever triggered it. Log loudly and keep running
// instead of dying; a coordinator restart already loses all in-memory group
// state (documented, accepted), so "degraded but alive" beats "cleanly
// dead" here specifically.
process.on('uncaughtException', (err) => {
  console.error('[FATAL-CAUGHT] Uncaught exception (process staying up):', err);
});
process.on('unhandledRejection', (reason) => {
  console.error('[FATAL-CAUGHT] Unhandled rejection (process staying up):', reason);
});

const PORT = process.env.PORT || 8080;
const SAVES_DIR = path.join(__dirname, 'saves');
const TRASH_DIR = path.join(SAVES_DIR, '_trash');
fs.mkdirSync(SAVES_DIR, { recursive: true });
fs.mkdirSync(TRASH_DIR, { recursive: true });

// How long to wait for the outgoing host's save upload to land before
// migrating anyway with whatever save version we've got. This is what
// keeps a migration from hanging forever if the upload never arrives
// (failed upload, or a host that crashed instead of quitting cleanly and
// never got to upload at all).
//
// BUT: this base window only covers "did an upload even show up?". A real
// world zip is a couple hundred MB, and a real test saw one take ~35s
// (slow link + a transient rename failure forcing client retries) - the
// 15s timer fired mid-upload and migrated the next host onto a STALE save,
// with the fresh one landing 20s later. So while an upload for the group
// is actively in flight (or one very recently was - the client waits 2.5s
// between retry attempts), the timeout extends itself in small increments
// instead of firing, up to a hard cap so a wedged upload can never hold a
// migration hostage forever.
const MIGRATE_UPLOAD_TIMEOUT_MS = 15000;
const MIGRATE_UPLOAD_EXTENSION_MS = 5000;
const MIGRATE_UPLOAD_RECENT_ACTIVITY_MS = 8000;
const MIGRATE_UPLOAD_HARD_CAP_MS = 180000;

// Dead-connection detection for the WebSocket side. Without this, a client
// that vanishes without a clean TCP close (power loss, network drop, a
// wedged game) stays in the group's members map INDEFINITELY - and a ghost
// "host" entry permanently blocks everyone else's im_hosting claim
// (already_hosting checks members.has(hostId)). Ping every sweep; anyone
// who shows no sign of life for two consecutive sweeps gets terminated,
// which fires their normal 'close' -> handleDeparture cleanup.
const HEARTBEAT_INTERVAL_MS = 30000;

// Generous headroom above any real friend group (the whole use case this
// coordinator exists for) - not a meaningful limit on legitimate play, just
// a ceiling on how far a leaked/guessed group code (a group id IS its own
// access control) could be scripted into growing this group's in-memory
// members/names/modHashes/modLists/queue without bound.
const MAX_CONCURRENT_MEMBERS = 40;

// A real friend group can easily go quiet for weeks (school, work, a break
// from the game) and come back expecting their world exactly as they left
// it - a save file is the only copy of that world that exists anywhere, so
// nothing here is allowed to just delete it on a whim. Retention is two
// stages instead of one outright delete:
//   1. After ARCHIVE_AFTER_MS with nobody online, the save is *moved* to a
//      trash folder (not deleted) and its in-memory bookkeeping is dropped
//      (that part's harmless either way - reconnecting recreates it).
//   2. Only after another PURGE_AFTER_MS sitting untouched in trash does it
//      actually get deleted. Reconnecting/downloading at any point before
//      that automatically restores it from trash first - see
//      restoreFromTrashIfNeeded().
// ~6 months to archive, ~3 more to purge - generous enough that "we haven't
// played in a while" is never mistaken for "we're never coming back."
const ARCHIVE_AFTER_MS = 180 * 24 * 60 * 60 * 1000;
const PURGE_AFTER_MS = 90 * 24 * 60 * 60 * 1000;
const CLEANUP_INTERVAL_MS = 6 * 60 * 60 * 1000;

// groupId is used directly as a filename (saves/<groupId>.zip) and as a
// WebSocket routing key, so it must be restricted to a safe, predictable
// charset - otherwise a crafted groupId like "../../../etc" could read or
// overwrite files outside SAVES_DIR entirely (path traversal).
function isValidGroupId(id) {
  return typeof id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(id);
}

// Invite codes for new groups: anyone who knows a groupId can join that
// group, become host, and download/overwrite its save - there's no
// separate password/account layer. So a group's real security is that its
// id is unguessable, not memorable. 10 chars from a 32-symbol alphabet
// (no 0/O/1/I/L, easy to misread aloud) is ~50 bits of entropy - far too
// many combinations to brute-force by guessing, while still short enough
// to read out over Discord voice chat.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
function generateGroupCode() {
  let code;
  do {
    code = '';
    const bytes = crypto.randomBytes(10);
    for (let i = 0; i < 10; i++) code += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  } while (groups.has(code)); // vanishingly unlikely, but never hand out a code already in use
  return code;
}

// playerId is echoed back to OTHER players (as hostId/newHostId) and those
// clients use it to build local file paths for their own player data
// (playerdata/<id>.dat). The mod only ever sends real UUIDs, but the
// coordinator can't assume every connecting client is the real mod - a
// crafted playerId could otherwise plant "../" segments that land in some
// other player's playerdata write path. Same safe charset as groupId
// (rather than requiring UUID shape specifically) so dev/test tooling can
// still use plain readable ids like "alice"/"bob".
function isValidPlayerId(id) {
  return isValidGroupId(id);
}

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

// groupId -> { members: Map<playerId, ws>, queue: [playerId,...], hostId: string|null,
//              saveVersion: number, pendingMigration: { timer } | null }
const groups = new Map();

function getGroup(groupId) {
  if (!groups.has(groupId)) {
    groups.set(groupId, {
      members: new Map(),
      names: new Map(), // playerId -> display name (last seen in a 'hello'); kept after departure so reconnects stay labeled
      lastSeen: new Map(), // playerId -> Date.now() at departure; feeds the "seen 2h ago" away roster
      // playerId -> { hash: <64-char lowercase sha256 hex or null>, count: <int or null> },
      // from each 'hello'. Hosting rotates between every member here (unlike
      // a normal modded server where one machine's mod list is the only one
      // that ever matters), so a mismatch has to be visible in the roster
      // BEFORE it lands on someone as host - see groupStateMessage below.
      modHashes: new Map(),
      // playerId -> string[] of "id@version" entries (same list modHashes
      // was hashed from), from each 'hello'. The hash alone can only say
      // "differs" - this is what lets CampfireModsScreen say WHICH mods, so
      // a crash from a missing/mismatched content mod is actually
      // diagnosable instead of just flagged. Opaque display data as far as
      // the coordinator's concerned - same "never inspects payloads"
      // treatment as everything else here, just type/length capped so a
      // malformed client can't bloat group state. The cap matches the
      // Worker coordinator's (see group.js), even though Node itself has no
      // comparable per-value storage ceiling, so both implementations
      // accept the same protocol.
      modLists: new Map(),
      queue: [],
      hostId: null,
      hostDirectAddress: null,
      // The group's original creator - set once, on whichever 'hello' the
      // coordinator sees first for this groupId, and never changed again
      // (not even if that player leaves for good - see CampfireClient's
      // World Settings screen, the only thing that reads this). Distinct
      // from hostId, which rotates every session.
      ownerId: null,
      ownerName: null,
      // Opaque snapshot (gamerules/difficulty/time/weather) reported by
      // whoever's currently hosting, via 'world_settings_report' - the
      // coordinator never interprets it, just stores and rebroadcasts, same
      // as every other pass-through payload here. Lets the owner's settings
      // screen show accurate current values even when the owner isn't the
      // one hosting.
      worldSettings: null,
      // saveVersion bookkeeping is in-memory, but the save zip itself
      // survives restarts on disk. Seed 1 when a zip exists so a client
      // comparing its own persisted "version of my local copy" against ours
      // sees a mismatch and re-downloads, instead of trusting a local copy
      // that may be older than what's sitting in saves/.
      saveVersion: fs.existsSync(path.join(SAVES_DIR, `${groupId}.zip`)) ? 1 : 0,
      pendingMigration: null,
      // Who most recently ACTUALLY hosted (set on im_hosting, survives their
      // departure). Rides on 'migrate' as previousHostId so the incoming host
      // knows whose player data sits inside the save's level.dat and can
      // preserve it - clients can't track this themselves, since hostId is
      // already null in every state they see by the time migrate arrives.
      lastHostId: null,
      // Live-upload bookkeeping for the migration timeout above: how many
      // save POSTs are streaming right now, and when one last started or
      // finished. Maintained by the middleware on the upload route.
      uploadsInFlight: 0,
      lastUploadActivityMs: 0,
      emptySince: null,
      // In-progress WebSocket-transport uploads, keyed by uploadId - see the
      // 'save_upload_*' handlers below. Never persisted; a dropped
      // connection mid-transfer just orphans the entry until GC, same as an
      // abandoned HTTP multipart upload today.
      activeUploads: new Map(),
      // The legacy HTTP POST /groups/:groupId/save path (below) never
      // participated in the activeUploads lock that save_upload_begin uses
      // to keep two WebSocket-transport uploads from racing the same
      // group's save file - and two concurrent HTTP POSTs to the same
      // group weren't serialized against EACH OTHER either. Either
      // combination could interleave writeFileSync/renameWithRetry calls
      // targeting the exact same tmp/dest paths (derived only from
      // groupId), corrupting the save or double-bumping saveVersion for
      // what looks like one logical upload. This flag is the HTTP path's
      // side of the same lock; see save_upload_begin and the POST handler.
      httpUploadInProgress: false,
    });
  }
  return groups.get(groupId);
}

function send(ws, msg) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function broadcast(group, msg, exceptId = null) {
  for (const [pid, ws] of group.members) {
    if (pid !== exceptId) send(ws, msg);
  }
}

// One authoritative snapshot of the group, sent to EVERYONE whenever
// membership or hosting changes - not just to a newcomer on hello. This is
// what lets every client render a live "who's around the fire" roster
// (names, who's hosting, queue order) instead of only ever knowing the
// hostId. Clients that predate the 'members' field just ignore it.
function groupStateMessage(group) {
  return {
    type: 'state',
    hostId: group.hostId,
    hostDirectAddress: group.hostDirectAddress,
    ownerId: group.ownerId,
    ownerName: group.ownerName,
    worldSettings: group.worldSettings,
    saveVersion: group.saveVersion,
    queue: group.queue,
    members: [...group.members.keys()].map((id) => {
      const mh = group.modHashes.get(id);
      return {
        id,
        name: group.names.get(id) || id,
        modHash: mh ? mh.hash : null,
        modCount: mh ? mh.count : null,
        mods: group.modLists.get(id) || [],
      };
    }),
    // Everyone the group has ever seen who ISN'T online right now, newest
    // departure first - lets clients show the whole friend circle, not just
    // who's connected this second. lastSeenMs is null for names that
    // predate this server process (names survive only in memory).
    // Older clients simply ignore this field.
    away: [...group.names.keys()]
      .filter((id) => !group.members.has(id))
      .map((id) => ({
        id,
        name: group.names.get(id) || id,
        lastSeenMs: group.lastSeen.get(id) || null,
      }))
      .sort((a, b) => (b.lastSeenMs || 0) - (a.lastSeenMs || 0))
      .slice(0, 8),
  };
}

function broadcastState(group) {
  broadcast(group, groupStateMessage(group));
}

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

// Picks the next host candidate from the queue, skipping anyone no longer
// connected. Deliberately does NOT set group.hostId: being chosen is a
// designation, not a confirmation. hostId only ever gets set by 'im_hosting'
// - i.e. when the designated player has actually downloaded the save, opened
// the world, and opened it to LAN. Between those two moments the group
// truthfully has no host (state broadcasts say so), which is what stops
// friends' screens from offering "Join X's World" toward someone whose
// world isn't actually running yet - and means a designated host who never
// opens the world (quit the game, walked away) just falls out of the queue
// and the next person's screen offers hosting instead, with no stuck state.
function pickNextHost(groupId) {
  const group = getGroup(groupId);
  while (group.queue.length > 0) {
    const candidate = group.queue[0];
    if (group.members.has(candidate)) return candidate;
    group.queue.shift();
  }
  return null;
}

// --- Migration gating (fixes the stale-save race) ---
//
// When a host leaves, we don't immediately promote+migrate. We wait until
// their save upload has actually landed (bumping saveVersion), or until the
// timeout fires, and only THEN broadcast "migrate" - so the new host is
// always told about a save version that's really sitting on disk, never one
// that's about to be replaced seconds later.

function beginPendingMigration(groupId) {
  const group = getGroup(groupId);

  if (group.pendingMigration) {
    // Already waiting on a previous departure - let that one resolve.
    return;
  }

  const timer = setTimeout(() => onPendingMigrationTimeout(groupId), MIGRATE_UPLOAD_TIMEOUT_MS);
  group.pendingMigration = { timer, startedAt: Date.now() };
}

function onPendingMigrationTimeout(groupId) {
  const group = getGroup(groupId);
  if (!group.pendingMigration) return;

  // An upload that's still streaming (or was active seconds ago - the mod
  // waits 2.5s between retry attempts, so a brief gap between attempts
  // doesn't mean it gave up) is EXACTLY what this wait exists for. Don't
  // cut it off just because the world is big or the link is slow; keep
  // extending in small steps until it lands or the hard cap says a wedged
  // upload can't hold the whole group hostage.
  const waitedMs = Date.now() - group.pendingMigration.startedAt;
  const uploadLooksAlive = group.uploadsInFlight > 0
    || (group.lastUploadActivityMs > 0 && Date.now() - group.lastUploadActivityMs < MIGRATE_UPLOAD_RECENT_ACTIVITY_MS);
  if (uploadLooksAlive && waitedMs < MIGRATE_UPLOAD_HARD_CAP_MS) {
    log(`[${groupId}] departing host's upload still in flight after ${Math.round(waitedMs / 1000)}s - holding the migration for it`);
    group.pendingMigration.timer = setTimeout(() => onPendingMigrationTimeout(groupId), MIGRATE_UPLOAD_EXTENSION_MS);
    return;
  }

  log(`[${groupId}] upload didn't arrive within ${Math.round(waitedMs / 1000)}s - migrating with last known save v${group.saveVersion} anyway`);
  resolvePendingMigration(groupId);
}

function resolvePendingMigration(groupId) {
  const group = getGroup(groupId);
  if (!group.pendingMigration) return;

  clearTimeout(group.pendingMigration.timer);
  group.pendingMigration = null;

  const newHost = pickNextHost(groupId);
  if (newHost) {
    log(`[${groupId}] migrating host -> ${newHost} (save v${group.saveVersion}, previous host ${group.lastHostId || 'unknown'})`);
    broadcast(group, {
      type: 'migrate',
      newHostId: newHost,
      saveVersion: group.saveVersion,
      previousHostId: group.lastHostId || null,
    });
    broadcastState(group); // hostId stays null until the new host's im_hosting lands
  } else {
    log(`[${groupId}] no one left to host`);
  }
}

// --- Relay switchboard ---
//
// Friends never talk to each other directly. Everything goes:
//   friend -> coordinator -> host   (relay_open / relay_data / relay_close, no target needed - always means "the host")
//   host -> coordinator -> friend   (same message types, but MUST include toPlayerId - the host may have several friends' streams open at once)
//
// The coordinator's only job is to stamp "fromPlayerId" on the way to the host,
// and route by "toPlayerId" on the way back. It never inspects msg.data.

function relayToHost(group, fromPlayerId, msg) {
  const hostWs = group.hostId ? group.members.get(group.hostId) : null;
  if (!hostWs) {
    const senderWs = group.members.get(fromPlayerId);
    if (senderWs) send(senderWs, { type: 'relay_error', streamId: msg.streamId, reason: 'no_host' });
    log(`[relay] dropped ${msg.type} from ${fromPlayerId} - no host connected`);
    return;
  }
  send(hostWs, { ...msg, fromPlayerId });
}

function relayToPlayer(group, toPlayerId, msg) {
  const targetWs = group.members.get(toPlayerId);
  if (!targetWs) {
    log(`[relay] dropped ${msg.type} to ${toPlayerId} - not connected`);
    return;
  }
  send(targetWs, msg);
}

wss.on('connection', (ws, req) => {
  let groupId = null;
  let playerId = null;
  const remoteIp = (req && req.socket && req.socket.remoteAddress) || 'unknown';

  // Heartbeat bookkeeping (see HEARTBEAT_INTERVAL_MS). Any sign of life
  // counts: a pong reply to our ping, the client's OWN keepalive pings
  // (the mod sends one every ~20s partly to keep NAT mappings warm), or
  // any real message.
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('ping', () => { ws.isAlive = true; });

  ws.on('message', async (raw) => {
    ws.isAlive = true;
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return send(ws, { type: 'error', reason: 'bad_json' });
    }

    // A single malformed-but-valid-JSON message (missing/wrong-typed field
    // reaching something like Buffer.from(msg.data, 'base64') with no
    // string to decode) used to throw straight out of this async handler
    // with nothing catching it - an unhandled rejection that takes the
    // WHOLE process down on modern Node, dropping every group's connections
    // at once over one bad message from any single client. Catch broadly
    // here so a bug in any one message-type branch degrades to "this one
    // client sees an error" instead of "everyone's session dies".
    try {

    if (msg.type === 'hello') {
      if (!isValidGroupId(msg.groupId) || !isValidPlayerId(msg.playerId)) {
        send(ws, { type: 'error', reason: 'invalid_id' });
        return;
      }
      groupId = msg.groupId;
      playerId = msg.playerId;
      if (!isGroupAlreadyCreated(groupId) && !isGroupCreationAllowed(remoteIp)) {
        log(`[${groupId}] rejected hello that would create a new group - ${remoteIp} is over the group-creation rate limit`);
        send(ws, { type: 'error', reason: 'rate_limited' });
        groupId = null;
        playerId = null;
        return;
      }
      const group = getGroup(groupId);

      // Reconnects (an existing member saying hello again) always win even
      // at the cap below; only a genuinely new member past the limit is
      // rejected. A group id is its own access control (see the file
      // header) - without a cap, anyone who has a leaked/guessed code could
      // script open connections with unique playerIds forever, growing
      // this group's members/names/modHashes/modLists/queue without bound.
      if (!group.members.has(playerId) && group.members.size >= MAX_CONCURRENT_MEMBERS) {
        log(`[${groupId}] rejected hello from ${playerId} - group already at ${MAX_CONCURRENT_MEMBERS} concurrent members`);
        send(ws, { type: 'error', reason: 'group_full' });
        return;
      }

      // A reconnect while the old socket is still lingering: the new
      // socket wins: close the old one right away (rather than leaving it
      // for the heartbeat sweep to eventually notice) so its own belated
      // close event can't later evict this live entry - handleDeparture's
      // same-socket check already guards against that too, but closing it
      // promptly here means stale-connection cleanup doesn't wait up to a
      // full heartbeat cycle.
      const oldSocket = group.members.get(playerId);
      if (oldSocket && oldSocket !== ws) {
        try { oldSocket.close(); } catch { /* already gone */ }
      }

      group.members.set(playerId, ws);
      group.names.set(playerId, String(msg.playerName || playerId).slice(0, 32));
      // Optional: an older client just never sends these, which must read as
      // "nothing to compare" (null) rather than a false mismatch alarm - so
      // anything not exactly a sha256 hex digest / a sane count is dropped
      // to null rather than stored as-is.
      const modHash = typeof msg.modHash === 'string' && /^[0-9a-f]{64}$/i.test(msg.modHash)
        ? msg.modHash.toLowerCase()
        : null;
      const modCount = Number.isInteger(msg.modCount) && msg.modCount >= 0 && msg.modCount <= 5000
        ? msg.modCount
        : null;
      group.modHashes.set(playerId, { hash: modHash, count: modCount });
      // Same tolerant treatment: drop anything that isn't a short string
      // rather than rejecting the hello over it, cap the array itself so a
      // broken/hostile client can't balloon this group's memory footprint.
      const mods = Array.isArray(msg.mods)
        ? msg.mods.filter((m) => typeof m === 'string' && m.length <= 80).slice(0, 400)
        : [];
      group.modLists.set(playerId, mods);
      group.lastSeen.delete(playerId); // they're here, not "last seen"
      group.emptySince = null;
      if (!group.queue.includes(playerId)) group.queue.push(playerId);
      // Ownership is decided by whoever's hello the coordinator sees FIRST
      // for this group, permanently - the mint call (POST /groups/new) has
      // no player identity attached, and in practice the creator is always
      // the first to say hello to their own fresh group anyway.
      if (!group.ownerId) {
        group.ownerId = playerId;
        group.ownerName = group.names.get(playerId);
        log(`[${groupId}] ${group.ownerName} is the original owner`);
      }

      log(`[${groupId}] ${msg.playerName || playerId} connected`);

      // Everyone (newcomer included) gets the fresh state: who's host, what
      // save version exists, the host's direct address if UPnP already
      // resolved one (so a friend joining after that point can skip the
      // relay entirely), and the live member roster - existing members see
      // the new arrival appear without waiting for anything else to happen.
      broadcastState(group);

      // Deliberately NO auto-promotion here. This used to broadcast a
      // 'migrate' naming the newcomer host the moment they said hello, which
      // meant simply LAUNCHING the game hijacked the player into a download
      // + world-list screen before they'd clicked anything. Hosting is now
      // claim-based: the queue-front player's status screen offers "Open the
      // World" (pulling the latest save first), and actually opening it
      // sends 'im_hosting' - that's when hostId gets set. Mid-session
      // handoffs (host disconnects while friends wait) still use the
      // beginPendingMigration -> 'migrate' path below, which is unrelated to
      // this hello branch.
      return;
    }

    if (!groupId || !playerId) return; // must say hello first

    const group = getGroup(groupId);

    if (msg.type === 'im_hosting') {
      // Hosting is claim-based (see the hello handler), so guard the claim:
      // if someone else is ALREADY actively hosting and still connected, a
      // second im_hosting (e.g. another player opening their local copy of
      // the world on the side) must not steal the group's relay routing out
      // from under the live session.
      if (group.hostId && group.hostId !== playerId && group.members.has(group.hostId)) {
        log(`[${groupId}] ${playerId} tried to claim host but ${group.hostId} is already hosting - rejected`);
        send(ws, { type: 'error', reason: 'already_hosting' });
        return;
      }
      // handleDeparture clears hostId (and broadcasts that) BEFORE the
      // pending-migration wait for the outgoing host's upload even starts -
      // that's what lets a freshly-idle "Open the World" button appear
      // right away instead of a confusing dead period. But it also means
      // hostId==null for the whole gating window, so the check above alone
      // can't stop a DIFFERENT queue-front client from claiming host during
      // that exact window and starting from whatever stale save it already
      // has locally - precisely the race beginPendingMigration exists to
      // prevent, just reached through 'im_hosting' instead of 'migrate'.
      if (group.pendingMigration) {
        log(`[${groupId}] ${playerId} tried to claim host while a migration is still pending - rejected`);
        send(ws, { type: 'error', reason: 'migration_pending' });
        return;
      }
      group.hostId = playerId;
      group.lastHostId = playerId;
      // A new host's direct address (if any) hasn't been discovered yet -
      // UPnP mapping happens after this and arrives as a separate
      // 'direct_address' message, possibly several seconds later. Clearing
      // it here stops friends from trying to reach the PREVIOUS host's
      // now-stale address.
      group.hostDirectAddress = null;
      broadcast(group, { type: 'host_confirmed', hostId: playerId }, playerId);
      broadcastState(group); // rosters everywhere should now show this player as host
      log(`[${groupId}] ${playerId} is now hosting`);
      return;
    }

    // Sent by the host once its own UPnP port-mapping attempt resolves -
    // may arrive well after 'im_hosting', or not at all if UPnP isn't
    // available on the host's network. Only the current host is trusted to
    // set this (a friend claiming to be host here would just be lying about
    // an address, not gaining any access they don't already have).
    if (msg.type === 'direct_address') {
      if (playerId !== group.hostId) return;
      group.hostDirectAddress = typeof msg.address === 'string' ? msg.address : null;
      broadcast(group, { type: 'host_direct_address', address: group.hostDirectAddress }, playerId);
      log(`[${groupId}] host direct address: ${group.hostDirectAddress || '(none - UPnP unavailable)'}`);
      return;
    }

    if (msg.type === 'save_uploaded') {
      // Legacy no-op. The HTTP upload handler is the ONE place saveVersion
      // gets bumped - bumping here too would double-count any client that
      // sends both, desyncing every client's persisted localSaveVersion
      // (which the upload response echoes) and forcing spurious
      // re-downloads. No shipped client sends this; kept only so an old
      // client doing so gets silence instead of an error.
      return;
    }

    if (msg.type === 'leaving') {
      handleDeparture(groupId, playerId, ws);
      return;
    }

    // --- Save transfer over the WebSocket (preferred transport) ---
    //
    // Same destination file, same atomic-write/version-bump/migration-gating
    // rules as the HTTP endpoints further down - this just rides the
    // connection every client already has open instead of separate HTTP
    // requests, so a big world's transfer costs a handful of messages on an
    // already-open socket instead of dozens of separately billed requests.
    // The HTTP endpoints stay as the fallback for any coordinator/client
    // version that doesn't speak this yet (client gives up waiting for
    // 'save_upload_begin_ack'/'save_download_begin' and falls back itself).

    if (msg.type === 'save_upload_begin') {
      // Only the (most recent) host may start an upload - nothing checked
      // this before, so any connected member could overwrite the group's
      // only save with two messages. group.hostId itself is NOT the right
      // field: the outgoing host's own 'leaving' message (sent before the
      // client zips and uploads anything) already clears hostId to null via
      // handleDeparture by the time this runs. group.lastHostId is what
      // stays correctly pointed at them - set once on im_hosting, and
      // nothing can reassign it out from under a legitimate in-flight
      // upload because the pendingMigration gate blocks any OTHER
      // im_hosting claim until this same upload lands or times out.
      if (playerId !== group.lastHostId) {
        log(`[${groupId}] rejected save upload from ${playerId} - not the (most recent) host`);
        send(ws, { type: 'save_upload_error', reason: 'not_host' });
        return;
      }
      // Cap concurrent uploads per group at 1 - nothing about a legitimate
      // migration ever needs two in flight at once, and without this a
      // client could open unlimited uploadIds and stage unbounded buffers.
      // Also checked against the HTTP path's own lock (httpUploadInProgress)
      // - see its definition in getGroup() for why the two need to share one.
      if (group.activeUploads.size > 0 || group.httpUploadInProgress) {
        send(ws, { type: 'save_upload_error', reason: 'upload_already_in_progress' });
        return;
      }
      const uploadId = crypto.randomUUID();
      // Tags which connection owns this upload so a disconnect mid-transfer
      // (exactly the scenario this whole system exists for - a host
      // quitting while its save streams out) can release the lock right
      // away in handleDeparture, instead of every OTHER save upload for
      // this group - including the new host's own first upload - being
      // rejected with upload_already_in_progress for up to the full
      // WS_UPLOAD_ABANDON_MS (10 minute) sweep window.
      group.activeUploads.set(uploadId, { parts: [], size: 0, startedAt: Date.now(), ws });
      group.uploadsInFlight += 1;
      group.lastUploadActivityMs = Date.now();
      send(ws, { type: 'save_upload_begin_ack', uploadId });
      return;
    }

    if (msg.type === 'save_upload_part') {
      const up = group.activeUploads.get(msg.uploadId);
      if (!up) {
        send(ws, { type: 'save_upload_error', uploadId: msg.uploadId, reason: 'unknown_upload' });
        return;
      }
      group.lastUploadActivityMs = Date.now();
      if (typeof msg.data !== 'string' || !Number.isInteger(msg.index) || msg.index < 0) {
        send(ws, { type: 'save_upload_error', uploadId: msg.uploadId, reason: 'bad_part' });
        return;
      }
      // Overwriting the same index again (a retried part) is harmless - the
      // same bytes just land in the same slot.
      const buf = Buffer.from(msg.data, 'base64');
      const previous = up.parts[msg.index];
      up.size += buf.length - (previous ? previous.length : 0);
      if (up.size > WS_UPLOAD_MAX_BYTES) {
        group.activeUploads.delete(msg.uploadId);
        group.uploadsInFlight = Math.max(0, group.uploadsInFlight - 1);
        log(`[${groupId}] rejected websocket save upload - exceeded ${WS_UPLOAD_MAX_BYTES / 1024 / 1024 / 1024}GB cap`);
        send(ws, { type: 'save_upload_error', uploadId: msg.uploadId, reason: 'too_large' });
        return;
      }
      up.parts[msg.index] = buf;
      send(ws, { type: 'save_upload_part_ack', uploadId: msg.uploadId, index: msg.index });
      return;
    }

    if (msg.type === 'save_upload_commit') {
      const up = group.activeUploads.get(msg.uploadId);
      if (!up) {
        send(ws, { type: 'save_upload_error', uploadId: msg.uploadId, reason: 'unknown_upload' });
        return;
      }
      group.lastUploadActivityMs = Date.now();
      // The activeUploads entry (and the "another upload can't start" lock
      // save_upload_begin checks via activeUploads.size) used to be released
      // right here, before the disk write below even started - so a client
      // that assumed a slow write meant failure and retried with a whole
      // new save_upload_begin could open a second writer racing the same
      // tmp/dest paths (both derived only from groupId) while THIS commit's
      // write+rename was still in flight, corrupting the save or
      // double-bumping saveVersion for what the client thinks is one
      // logical upload. The lock now stays held for the actual duration it
      // exists to serialize - released only once below, after the write
      // (success OR failure) has genuinely finished.
      const releaseUploadLock = () => {
        group.activeUploads.delete(msg.uploadId);
        group.uploadsInFlight = Math.max(0, group.uploadsInFlight - 1);
      };

      if (up.parts.some((p) => !p)) {
        releaseUploadLock();
        send(ws, { type: 'save_upload_error', uploadId: msg.uploadId, reason: 'missing_part' });
        return;
      }
      const buf = Buffer.concat(up.parts);
      // Same cheap sanity check as the HTTP upload: every zip starts with "PK".
      if (buf.length < 4 || buf[0] !== 0x50 || buf[1] !== 0x4b) {
        releaseUploadLock();
        log(`[${groupId}] rejected websocket save upload - not a zip (${buf.length} bytes)`);
        send(ws, { type: 'save_upload_error', uploadId: msg.uploadId, reason: 'not_a_zip' });
        return;
      }

      restoreFromTrashIfNeeded(groupId);
      const dest = path.join(SAVES_DIR, `${groupId}.zip`);
      const tmp = `${dest}.uploading`;
      try {
        fs.writeFileSync(tmp, buf);
        await renameWithRetry(tmp, dest);
      } catch (e) {
        log(`[${groupId}] websocket save upload failed - couldn't move the new zip into place: ${e.message}`);
        try { fs.unlinkSync(tmp); } catch { /* sweep gets it later */ }
        releaseUploadLock();
        send(ws, { type: 'save_upload_error', uploadId: msg.uploadId, reason: 'write_failed' });
        return;
      }
      releaseUploadLock();

      group.saveVersion += 1;
      broadcast(group, { type: 'save_ready', saveVersion: group.saveVersion });
      log(`[${groupId}] save uploaded over websocket (${(buf.length / 1024 / 1024).toFixed(1)} MB) -> v${group.saveVersion}`);
      send(ws, { type: 'save_upload_commit_ack', uploadId: msg.uploadId, saveVersion: group.saveVersion });

      if (group.pendingMigration) {
        resolvePendingMigration(groupId);
      }
      return;
    }

    if (msg.type === 'save_download_request') {
      restoreFromTrashIfNeeded(groupId);
      const filePath = path.join(SAVES_DIR, `${groupId}.zip`);
      if (!fs.existsSync(filePath)) {
        send(ws, { type: 'save_download_error', reason: 'no_save' });
        return;
      }
      const buf = fs.readFileSync(filePath);
      const totalParts = Math.max(1, Math.ceil(buf.length / WS_TRANSFER_PART_BYTES));
      send(ws, { type: 'save_download_begin', totalBytes: buf.length, totalParts, saveVersion: group.saveVersion });
      for (let i = 0; i < totalParts; i++) {
        const off = i * WS_TRANSFER_PART_BYTES;
        const part = buf.subarray(off, Math.min(off + WS_TRANSFER_PART_BYTES, buf.length));
        send(ws, { type: 'save_download_part', index: i, data: part.toString('base64') });
      }
      send(ws, { type: 'save_download_done' });
      log(`[${groupId}] save downloaded over websocket (${(buf.length / 1024 / 1024).toFixed(1)} MB, v${group.saveVersion})`);
      return;
    }

    // --- Hole-punch signaling (STUN-style address exchange for direct P2P) ---
    //
    // Same "one target is implied" convention as the relay messages below: a
    // friend sending this always means "to the host" (there's only ever
    // one), while the host sending it back MUST include toPlayerId, since it
    // may be answering whichever friend most recently asked. The coordinator
    // only ever passes the address string through - it never validates or
    // interprets it, same as it never inspects relay_data's payload.
    // The current host reports its live gamerule/difficulty/time/weather
    // snapshot after opening (and again after applying an owner_settings_change)
    // so the owner's settings screen can show real current values even when
    // the owner isn't the one hosting. Only the host is trusted to set this -
    // same reasoning as direct_address above.
    if (msg.type === 'world_settings_report') {
      if (playerId !== group.hostId) return;
      group.worldSettings = msg.settings || null;
      broadcastState(group);
      return;
    }

    // The owner asking whoever's currently hosting to change a setting
    // (gamemode/gamerule/time/weather/difficulty). No target needed from the
    // owner's side - same "always means the host" convention as
    // punch_candidate/relay_* below. The coordinator doesn't check that the
    // sender IS the owner (it never validates payloads), and doesn't need to:
    // it stamps fromPlayerId from the connection's own validated identity, and
    // the acting host cross-checks that against the coordinator-broadcast
    // ownerId before applying anything - a non-owner sending this just gets
    // ignored on the receiving end.
    if (msg.type === 'owner_settings_change') {
      relayToHost(group, playerId, { type: 'owner_settings_change', action: msg.action, value: msg.value });
      return;
    }

    if (msg.type === 'punch_candidate') {
      const isHost = playerId === group.hostId;
      if (isHost) {
        if (!msg.toPlayerId) {
          log(`[punch] host ${playerId} sent punch_candidate with no toPlayerId - dropped`);
          return;
        }
        relayToPlayer(group, msg.toPlayerId, { type: 'punch_candidate', address: msg.address });
      } else {
        relayToHost(group, playerId, { type: 'punch_candidate', address: msg.address });
      }
      return;
    }

    // --- Relay messages ---
    if (msg.type === 'relay_open' || msg.type === 'relay_data' || msg.type === 'relay_close') {
      const isHost = playerId === group.hostId;

      if (isHost) {
        // Host -> a specific friend. toPlayerId is required.
        if (!msg.toPlayerId) {
          log(`[relay] host ${playerId} sent ${msg.type} with no toPlayerId - dropped`);
          return;
        }
        relayToPlayer(group, msg.toPlayerId, {
          type: msg.type,
          streamId: msg.streamId,
          data: msg.data, // present on relay_data only, harmless undefined otherwise
        });
      } else {
        // Friend -> the host, whoever that currently is.
        relayToHost(group, playerId, {
          type: msg.type,
          streamId: msg.streamId,
          data: msg.data,
        });
      }
      return;
    }
    } catch (err) {
      log(`[${groupId || '?'}] error handling '${msg && msg.type}' message from ${playerId || '?'}: ${err && err.message}`);
      try { send(ws, { type: 'error', reason: 'server_error' }); } catch { /* socket already gone */ }
    }
  });

  ws.on('close', () => {
    if (groupId && playerId) handleDeparture(groupId, playerId, ws);
  });
});

// The heartbeat sweep itself: anyone who hasn't shown a sign of life since
// the previous sweep is presumed gone and terminated - terminate() fires
// their 'close' handler, so departure cleanup (including host migration if
// the ghost was hosting) runs exactly as if they'd disconnected cleanly.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    try { ws.ping(); } catch { /* socket already dying - the next sweep reaps it */ }
  }
}, HEARTBEAT_INTERVAL_MS);

function handleDeparture(groupId, playerId, ws) {
  const group = getGroup(groupId);

  if (!group.members.has(playerId) && !group.queue.includes(playerId)) {
    return; // already processed - the 'leaving' message and the socket
             // close event both call this for a normal clean quit
  }

  // If this player already has a DIFFERENT, newer socket registered (a
  // reconnect that raced ahead of this stale connection's own belated
  // 'close'/'leaving'), this departure is stale and must not evict the live
  // session or migrate away from a host who's actually still connected -
  // only the socket that's actually still the current registered one is
  // allowed to remove itself.
  if (ws && group.members.get(playerId) !== ws) {
    return;
  }

  const wasHost = group.hostId === playerId;

  group.members.delete(playerId);
  group.queue = group.queue.filter((id) => id !== playerId);
  group.lastSeen.set(playerId, Date.now());
  // Unlike names/lastSeen (deliberately kept so the "away" roster stays
  // labeled), mod-mismatch data only means anything for someone currently
  // in the live roster being compared against - keeping it around after
  // departure was pure unbounded growth (one entry per distinct playerId
  // ever seen, forever) with no feature depending on it surviving.
  group.modHashes.delete(playerId);
  group.modLists.delete(playerId);
  // Release any upload this exact connection had in flight right away,
  // rather than leaving every other save upload for this group blocked on
  // upload_already_in_progress until the 10-minute abandoned-upload sweep
  // catches up.
  if (ws) {
    for (const [uploadId, up] of group.activeUploads) {
      if (up.ws === ws) {
        group.activeUploads.delete(uploadId);
        group.uploadsInFlight = Math.max(0, group.uploadsInFlight - 1);
      }
    }
  }
  log(`[${groupId}] ${playerId} left${wasHost ? ' (was host)' : ''}`);

  if (group.members.size === 0) {
    group.emptySince = Date.now();
  }

  if (wasHost) {
    // Nobody left to relay to right now - tell everyone still connected to
    // close out any open relay streams cleanly instead of hanging.
    broadcast(group, { type: 'relay_reset', reason: 'host_left' });
    group.hostId = null;
  }

  // Everyone still connected sees the departure immediately - previously a
  // NON-host quitting was completely invisible to the rest of the group.
  // Broadcast AFTER hostId is cleared above, so a departing host's roster
  // update already reads "no host" instead of naming someone who's gone.
  broadcastState(group);

  if (wasHost) {
    if (group.queue.length === 0) {
      log(`[${groupId}] no one left to host`);
      return;
    }

    // Don't migrate yet - wait for the outgoing host's save upload to land
    // (or time out) so the new host never downloads a stale version.
    beginPendingMigration(groupId);
  }
}

// --- Save file relay (plain HTTP, separate from the WebSocket signaling) ---

const upload = multer({ limits: { fileSize: 2 * 1024 * 1024 * 1024 } }); // 2GB cap for now

// Chunk size for the WebSocket-transport save transfer above. Comfortably
// under Workers' 32MB-per-message WebSocket limit even after base64's ~33%
// inflation, and small enough to keep per-message memory pressure low on a
// player's JVM heap.
const WS_TRANSFER_PART_BYTES = 512 * 1024;

// Same 2GB backstop as the HTTP multer upload above, applied to the
// WebSocket path too - it never had one, which meant a client could stream
// parts forever with nothing to stop it. This is not a real-world-size
// limit (no legitimate Minecraft save gets remotely close); it exists only
// to bound a runaway or malicious upload. Checked incrementally per-part so
// a bad actor is cut off early instead of after fully buffering gigabytes.
const WS_UPLOAD_MAX_BYTES = 2 * 1024 * 1024 * 1024;

// An abandoned WebSocket upload (connection dropped mid-transfer) has
// nothing else to reap it - unlike the HTTP path's temp file, it lives only
// in this Map, so without a sweep it stays allocated forever. Real uploads
// finish in seconds; anything older than this is orphaned.
const WS_UPLOAD_ABANDON_MS = 10 * 60 * 1000;

// If a group's save was archived to trash for inactivity and someone just
// reconnected/uploaded/downloaded, that's proof the group isn't abandoned
// after all - bring it back before doing anything else.
function restoreFromTrashIfNeeded(groupId) {
  const trashPath = path.join(TRASH_DIR, `${groupId}.zip`);
  const livePath = path.join(SAVES_DIR, `${groupId}.zip`);
  if (fs.existsSync(trashPath) && !fs.existsSync(livePath)) {
    fs.renameSync(trashPath, livePath);
    log(`[${groupId}] welcome back - restored save from trash`);
  }
}

// Windows can transiently refuse a rename when something else briefly holds
// either file open - the observed culprit is antivirus scanning a
// freshly-written multi-hundred-MB temp the instant it's closed. A real
// session hit EPERM here twice in a row, each failure costing the departing
// host a full re-upload of the entire zip AND delaying the save landing past
// the migration window (-> stale-save migrate). The file itself is fine and
// the lock clears in seconds, so retry with backoff before declaring defeat.
function renameWithRetry(src, dest, attempts = 15, delayMs = 400) {
  return new Promise((resolve, reject) => {
    const tryOnce = (remaining) => {
      try {
        fs.renameSync(src, dest);
        resolve();
      } catch (e) {
        if (remaining <= 1 || !['EPERM', 'EBUSY', 'EACCES'].includes(e.code)) {
          reject(e);
          return;
        }
        setTimeout(() => tryOnce(remaining - 1), delayMs);
      }
    };
    tryOnce(attempts);
  });
}

// Runs BEFORE multer parses the body, so the group knows an upload is
// actively streaming from the moment its first bytes arrive - that's what
// lets the migration timeout (onPendingMigrationTimeout) hold the handoff
// for an upload that's genuinely underway instead of firing mid-transfer.
function markUploadInFlight(req, res, next) {
  const { groupId } = req.params;
  if (isValidGroupId(groupId)) {
    if (!isGroupAlreadyCreated(groupId)) {
      const ip = req.socket.remoteAddress || 'unknown';
      if (!isGroupCreationAllowed(ip)) {
        log(`[${groupId}] rejected upload that would create a new group - ${ip} is over the group-creation rate limit`);
        res.status(429).json({ error: 'too many new groups from this address - try again later' });
        return;
      }
    }
    const group = getGroup(groupId);
    group.uploadsInFlight += 1;
    group.lastUploadActivityMs = Date.now();
    let settled = false;
    const settle = () => {
      if (settled) return;
      settled = true;
      group.uploadsInFlight = Math.max(0, group.uploadsInFlight - 1);
      group.lastUploadActivityMs = Date.now();
    };
    res.on('finish', settle);
    res.on('close', settle);
  }
  next();
}

app.post('/groups/:groupId/save', markUploadInFlight, upload.single('save'), async (req, res) => {
  const { groupId } = req.params;
  if (!isValidGroupId(groupId)) return res.status(400).json({ error: 'invalid group id' });
  if (!req.file) return res.status(400).json({ error: 'missing file' });
  const group = getGroup(groupId);
  // Same lock save_upload_begin uses (activeUploads.size), from the other
  // side - see httpUploadInProgress's definition in getGroup() for why a
  // second concurrent writer here (whether another HTTP POST or a
  // WebSocket-transport upload) can't be allowed to interleave with this
  // one's writeFileSync/rename against the same tmp/dest paths.
  if (group.activeUploads.size > 0 || group.httpUploadInProgress) {
    return res.status(409).json({ error: 'upload already in progress' });
  }
  group.httpUploadInProgress = true;
  try {
  // The stored zip is the ONLY copy of the group's world - never let
  // garbage replace it. Cheap sanity check (every zip starts with "PK"),
  // then write-to-temp + rename so a crash/disk-full mid-write can never
  // leave a half-written file where the good save used to be. rename() on
  // the same filesystem is atomic; the good save exists untouched right up
  // until the complete new one takes its place.
  const buf = req.file.buffer;
  if (buf.length < 4 || buf[0] !== 0x50 || buf[1] !== 0x4b) {
    log(`[${groupId}] rejected save upload - not a zip (${buf.length} bytes)`);
    return res.status(400).json({ error: 'not a zip' });
  }
  restoreFromTrashIfNeeded(groupId);
  const dest = path.join(SAVES_DIR, `${groupId}.zip`);
  const tmp = `${dest}.uploading`;
  fs.writeFileSync(tmp, buf);
  try {
    await renameWithRetry(tmp, dest);
  } catch (e) {
    log(`[${groupId}] save upload failed - couldn't move the new zip into place: ${e.message}`);
    try { fs.unlinkSync(tmp); } catch { /* sweep gets it later */ }
    // 5xx on purpose: the mod's upload loop retries 5xx (it can heal),
    // never 4xx.
    return res.status(503).json({ error: 'save file busy - retry' });
  }
  group.saveVersion += 1;
  broadcast(group, { type: 'save_ready', saveVersion: group.saveVersion });
  log(`[${groupId}] save uploaded (${(req.file.buffer.length / 1024 / 1024).toFixed(1)} MB) -> v${group.saveVersion}`);

  // If a host departure is waiting on exactly this upload, this is the
  // signal it's been waiting for - migrate now, with the version we just set.
  if (group.pendingMigration) {
    resolvePendingMigration(groupId);
  }

  res.json({ ok: true, saveVersion: group.saveVersion });
  } finally {
    group.httpUploadInProgress = false;
  }
});

app.get('/groups/:groupId/save', (req, res) => {
  const { groupId } = req.params;
  if (!isValidGroupId(groupId)) return res.status(400).send('invalid group id');
  restoreFromTrashIfNeeded(groupId);
  const filePath = path.join(SAVES_DIR, `${groupId}.zip`);
  if (!fs.existsSync(filePath)) return res.status(404).send('no save yet');
  res.download(filePath, `${groupId}.zip`);
});

// Lets a friend's "Join a Campfire" screen check a code before committing to
// it. getGroup() lazily creates a group on the first 'hello' regardless of
// whether that code was ever really handed out by anyone - so a typo'd or
// made-up (but still validly-charset) code would otherwise silently spin up
// a brand-new empty group instead of surfacing as wrong. A group that has
// never had anyone say hello AND has no uploaded save (live or archived in
// trash) has definitely never really existed, so that's what "exists" means
// here - checked ahead of ever opening a websocket for it.
app.get('/groups/:groupId/exists', (req, res) => {
  const { groupId } = req.params;
  if (!isValidGroupId(groupId)) return res.status(400).send('invalid group id');
  restoreFromTrashIfNeeded(groupId);
  const hasSave = fs.existsSync(path.join(SAVES_DIR, `${groupId}.zip`));
  res.json({ exists: hasSave || groups.has(groupId) });
});

// Ambient presence for the mod's "Your Campfires" list screen: who's online
// and who's hosting in a group WITHOUT joining its websocket (a client only
// ever holds one live websocket - its active campfire - but the list screen
// shows all of them). Read-only on purpose: never getGroup() (a poll must
// not lazily create groups), never restoreFromTrashIfNeeded (a passive
// look-in isn't the "they're back" signal that justifies un-archiving).
// Knowing the groupId is already full access to the group (its id IS its
// access control), so this reveals nothing an owner of the code couldn't
// get by connecting.
app.get('/groups/:groupId/status', (req, res) => {
  const { groupId } = req.params;
  if (!isValidGroupId(groupId)) return res.status(400).json({ error: 'invalid group id' });
  const group = groups.get(groupId) || null;
  const hasSave = fs.existsSync(path.join(SAVES_DIR, `${groupId}.zip`))
    || fs.existsSync(path.join(TRASH_DIR, `${groupId}.zip`));
  if (!group && !hasSave) return res.status(404).json({ exists: false });
  res.json({
    exists: true,
    online: group ? group.members.size : 0,
    memberNames: group ? [...group.members.keys()].map((id) => group.names.get(id) || id) : [],
    hostName: group && group.hostId ? (group.names.get(group.hostId) || group.hostId) : null,
    saveVersion: group ? group.saveVersion : (hasSave ? 1 : 0),
  });
});

// Mint a brand-new, unguessable group id - this is how a fresh friend group
// gets created for a real deploy (as opposed to dev/test setups that just
// hardcode a shared groupId like "dev-test-group"). Nothing needs to be
// created in `groups` yet - getGroup() lazily creates it the moment the
// first player actually says hello.
// Minting is unauthenticated by design (a fresh install has no identity
// yet), which also makes it the one endpoint a bored script could hammer
// for free. Codes cost nothing until someone says hello, so the only real
// risk is log spam/noise - a generous per-IP cap kills that without ever
// getting in the way of a legitimate friend group (who mint one code,
// ever). In-memory, pruned on the cleanup sweep.
const MINT_LIMIT_PER_HOUR = 20;
const MINT_WINDOW_MS = 60 * 60 * 1000;
const mintHistory = new Map(); // ip -> [timestamps within the window]

// Shared by /groups/new AND anywhere else a request could instantiate a
// brand-new group (the 'hello' handler, the upload route's
// markUploadInFlight) - see isGroupAlreadyCreated below for why those other
// paths need this too. Records the attempt as it checks, same as the
// original /groups/new-only version did.
function isGroupCreationAllowed(ip) {
  const now = Date.now();
  const recent = (mintHistory.get(ip) || []).filter((t) => now - t < MINT_WINDOW_MS);
  if (recent.length >= MINT_LIMIT_PER_HOUR) return false;
  recent.push(now);
  mintHistory.set(ip, recent);
  return true;
}

// /groups/new is rate-limited per IP, but 'hello' and the upload route both
// accept ANY syntactically-valid groupId (see isValidGroupId) with no
// relation to /groups/new required - getGroup() lazily instantiates real
// (if empty) state for one, and the upload route accepts up to 2GB into it,
// with zero throttling either way. A script could mint unlimited fake
// groups through either path and upload ~2GB to each, exhausting the
// coordinator's disk for every real group, not just its own. Checked only
// for a group that doesn't already have real state - an established
// group's normal traffic never pays this cost.
function isGroupAlreadyCreated(groupId) {
  return groups.has(groupId)
    || fs.existsSync(path.join(SAVES_DIR, `${groupId}.zip`))
    || fs.existsSync(path.join(TRASH_DIR, `${groupId}.zip`));
}

app.post('/groups/new', (req, res) => {
  const ip = req.socket.remoteAddress || 'unknown';
  if (!isGroupCreationAllowed(ip)) {
    log(`[mint] rate-limited ${ip}`);
    return res.status(429).send('too many new groups from this address - try again later');
  }
  res.json({ groupId: generateGroupCode() });
});

// A tiny self-hosted TCP "reflector" - functionally a STUN server, but TCP.
// Public STUN infra (Google's, etc.) is overwhelmingly UDP-only, but a NAT
// tracks UDP and TCP port mappings completely independently - a UDP STUN
// result tells us nothing about how the same router will map a TCP
// connection, which is what Minecraft actually needs. So instead of relying
// on third-party UDP STUN (or a second raw TCP port of our own, which would
// need its own separate port-forward on top of the one this coordinator
// already needs), a client connects here from the exact local port it's
// about to try hole-punching with, and whatever remote address/port this
// HTTP request arrived from IS that port's real external NAT mapping - this
// coordinator just answers on the same port it already listens on.
app.get('/reflect', (req, res) => {
  let ip = req.socket.remoteAddress || '';
  if (ip.startsWith('::ffff:')) ip = ip.slice(7); // unwrap IPv4-mapped IPv6 form
  res.json({ ip, port: req.socket.remotePort });
});

let coordinatorVersion = '0.0.0';
try {
  coordinatorVersion = require('./package.json').version || coordinatorVersion;
} catch {
  // no package.json (unusual but survivable) - report the fallback
}
const startedAt = Date.now();

app.get('/health', (req, res) => res.json({
  ok: true,
  groups: groups.size,
  uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000),
  version: coordinatorVersion,
}));

// --- Stale group cleanup (disk/memory hygiene for a long-running free deploy) ---
//
// Two independent sweeps, per the retention policy above: archive long-empty
// groups' saves (move, don't delete), then separately purge anything that's
// been sitting in trash even longer. Neither step ever touches a group/save
// that's had anyone reconnect - restoreFromTrashIfNeeded() undoes the
// archive step the moment that happens.

function archiveStaleGroups() {
  const now = Date.now();
  for (const [groupId, group] of groups) {
    if (!group.emptySince || now - group.emptySince < ARCHIVE_AFTER_MS) continue;

    const livePath = path.join(SAVES_DIR, `${groupId}.zip`);
    const trashPath = path.join(TRASH_DIR, `${groupId}.zip`);
    if (fs.existsSync(livePath)) fs.renameSync(livePath, trashPath);
    groups.delete(groupId); // cheap in-memory bookkeeping only - recreated instantly if anyone reconnects
    log(`[${groupId}] empty for over ${ARCHIVE_AFTER_MS / 86400000}d - archived save to trash (not deleted)`);
  }
}

function purgeOldTrash() {
  const now = Date.now();
  for (const fileName of fs.readdirSync(TRASH_DIR)) {
    const filePath = path.join(TRASH_DIR, fileName);
    const ageMs = now - fs.statSync(filePath).mtimeMs;
    if (ageMs < PURGE_AFTER_MS) continue;
    fs.unlinkSync(filePath);
    log(`[trash] permanently deleted ${fileName} after ${(ageMs / 86400000).toFixed(0)}d in trash`);
  }
}

// A crash between "write temp" and "rename into place" on the upload path
// leaves a .uploading file behind; anything older than a few minutes is
// definitely orphaned (real uploads finish in seconds), so sweep them.
function cleanOrphanedUploadTemps() {
  const now = Date.now();
  for (const fileName of fs.readdirSync(SAVES_DIR)) {
    if (!fileName.endsWith('.uploading')) continue;
    const filePath = path.join(SAVES_DIR, fileName);
    if (now - fs.statSync(filePath).mtimeMs > 10 * 60 * 1000) {
      fs.unlinkSync(filePath);
      log(`[cleanup] removed orphaned upload temp ${fileName}`);
    }
  }
}

function pruneMintHistory() {
  const now = Date.now();
  for (const [ip, times] of mintHistory) {
    const recent = times.filter((t) => now - t < MINT_WINDOW_MS);
    if (recent.length === 0) mintHistory.delete(ip);
    else mintHistory.set(ip, recent);
  }
}

// A WebSocket upload whose connection dropped mid-transfer has no temp file
// to sweep (unlike the HTTP path) - it only lives in group.activeUploads,
// so without this it stays allocated in memory forever.
function pruneAbandonedUploads() {
  const now = Date.now();
  for (const [groupId, group] of groups) {
    for (const [uploadId, up] of group.activeUploads) {
      if (now - up.startedAt > WS_UPLOAD_ABANDON_MS) {
        group.activeUploads.delete(uploadId);
        group.uploadsInFlight = Math.max(0, group.uploadsInFlight - 1);
        log(`[${groupId}] swept abandoned websocket upload ${uploadId}`);
      }
    }
  }
}

setInterval(() => {
  archiveStaleGroups();
  purgeOldTrash();
  cleanOrphanedUploadTemps();
  pruneMintHistory();
  pruneAbandonedUploads();
}, CLEANUP_INTERVAL_MS);

// Express's default error handler answers with a full HTML error page,
// which the mod then logs as the maximally-unhelpful
// 'Upload response (500): <!DOCTYPE html>' - the actual cause (an EPERM
// rename, an aborted request) never leaves the server. Log the real error
// once, answer compact JSON.
app.use((err, req, res, next) => {
  log(`[http] ${req.method} ${req.path} failed: ${err.message}`);
  if (res.headersSent) return next(err);
  res.status(500).json({ error: err.message });
});

server.listen(PORT, () => log(`coordinator listening on :${PORT}`));

module.exports = { app, server, groups };
