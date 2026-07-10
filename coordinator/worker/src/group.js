// One Durable Object instance per friend group ("campfire"). This is the
// Cloudflare port of the Node coordinator's per-group logic (see
// coordinator/coordinator/server.js, which remains the reference for the
// protocol): a phone book (who's online, whose turn to host), a dead-drop
// for the zipped world save, and a switchboard relaying live game traffic.
// It speaks the exact same WebSocket JSON protocol the mod already speaks.
//
// Differences forced by the platform, all deliberate:
//
// - The object hibernates between messages (that's what makes it free to
//   run), so NOTHING can live only in a JS variable across messages. Group
//   state lives in the object's own SQLite storage; each connection's
//   identity rides on the socket itself via serializeAttachment. In-memory
//   maps here are pure caches rebuilt on wake.
// - Timers don't survive hibernation, so the migration-gating timeout uses
//   the storage alarm API instead of setTimeout.
// - Cloudflare's free plan rejects any single HTTP request body over 100MB,
//   and storage values cap at 2MB - so the save zip is stored as 2MB chunk
//   rows, uploadable either in one legacy POST (small saves, unchanged mod)
//   or in parts via /save/begin + /save/part/:n + /save/commit.
// - No server-initiated ping sweep: Cloudflare's edge owns the TCP
//   connections and closes dead ones itself, which fires webSocketClose ->
//   the normal departure cleanup. (The Node version needed its own sweep
//   because a raw socket to a vanished peer can stay half-open forever.)
// - saveVersion persists in storage, so the Node version's "seed 1 if a
//   zip exists on disk" restart hack isn't needed - a restart here simply
//   remembers the real version.

// How long to wait for the outgoing host's save upload to land before
// migrating anyway with whatever save version we've got. While an upload is
// actively making progress the deadline extends itself in small steps (a
// real-world zip can take minutes on a slow link), up to a hard cap so a
// wedged upload can never hold a migration hostage forever. The "recent
// activity" window is wider than the Node version's 8s because a chunked
// upload only touches this object once per multi-MB part, not continuously
// per network packet.
const MIGRATE_UPLOAD_TIMEOUT_MS = 15000;
const MIGRATE_UPLOAD_EXTENSION_MS = 5000;
const MIGRATE_UPLOAD_RECENT_ACTIVITY_MS = 30000;
const MIGRATE_UPLOAD_HARD_CAP_MS = 180000;

// A save whose group has been completely empty this long gets deleted. The
// Node version staged this (archive to trash at 180d, purge 90d later);
// here there's no disk to reclaim early, so the save stays live and
// downloadable for the FULL ~9 months - strictly gentler than the original.
// Anyone reconnecting at any point before the deadline resets it.
const PURGE_AFTER_EMPTY_MS = 270 * 24 * 60 * 60 * 1000;

// A partial chunked upload that stopped making progress this long ago is
// abandoned - sweep its staged chunks.
const STAGING_TTL_MS = 60 * 60 * 1000;

// Same 2GB backstop on both the legacy chunked-HTTP upload and the newer
// WebSocket upload - neither had one, which meant a client could stream
// parts into Durable Object storage forever. This is not a real-world-size
// limit (no legitimate Minecraft save gets remotely close); it exists only
// to bound a runaway or malicious upload. Checked incrementally per-part so
// a bad actor is cut off early instead of after fully persisting gigabytes.
const UPLOAD_MAX_BYTES = 2 * 1024 * 1024 * 1024;

// Storage values cap at 2MB on the SQLite backend; store save zips as rows
// of this size.
const CHUNK_SIZE = 2 * 1024 * 1024;

// Cap on how many departed players' names/last-seen we remember for the
// "away" roster. Without a cap, a script saying hello with endless random
// playerIds would grow the group record forever.
const MAX_REMEMBERED_NAMES = 200;

const DEFAULT_META = {
  groupLabel: null, // the groupId, remembered for logs (a DO can't read its own name)
  everHello: false, // distinguishes a real group from a typo'd code someone probed
  saveVersion: 0,
  hostId: null,
  hostDirectAddress: null,
  lastHostId: null,
  // The group's original creator - set once, on whichever 'hello' this
  // Durable Object sees first, and never changed again. Distinct from
  // hostId, which rotates every session. See group.js's hello handler and
  // CampfireClient's World Settings screen (the only thing that reads this).
  ownerId: null,
  ownerName: null,
  // Opaque snapshot (gamerules/difficulty/time/weather) reported by
  // whoever's currently hosting, via 'world_settings_report' - never
  // interpreted here, just stored and rebroadcast like every other
  // pass-through payload.
  worldSettings: null,
  queue: [],
  names: {}, // playerId -> display name, kept after departure so reconnects stay labeled
  lastSeen: {}, // playerId -> departure timestamp; feeds the "seen 2h ago" away roster
  // playerId -> { hash: <64-char lowercase sha256 hex or null>, count: <int or null> },
  // from each 'hello'. Hosting rotates between every member here (unlike a
  // normal modded server where one machine's mod list is the only one that
  // ever matters), so a mismatch has to be visible in the roster BEFORE it
  // lands on someone as host - see groupStateMessage below.
  modHashes: {},
  emptySince: null,
  pendingMigrationStartedAt: null,
  lastUploadActivityMs: 0,
};

// groupId doubles as a routing key and (client-side) a filename, so it must
// stay in a safe charset - same rule as the Node coordinator.
export function isValidGroupId(id) {
  return typeof id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(id);
}
const isValidPlayerId = isValidGroupId;

// Base64 helpers for the WebSocket-transport save transfer below. atob/btoa
// are the standard Web APIs available in the Workers runtime - deliberately
// not reaching for Node's Buffer, which needs the nodejs_compat flag.
function base64ToArrayBuffer(b64) {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function arrayBufferToBase64(buf) {
  const bytes = new Uint8Array(buf);
  let binary = '';
  const STEP = 0x8000; // avoid blowing the call stack passing huge arrays to fromCharCode
  for (let i = 0; i < bytes.length; i += STEP) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + STEP));
  }
  return btoa(binary);
}

export class CampfireGroup {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.meta = null; // storage-backed; loaded lazily by loaded()

    // playerId -> WebSocket, rebuilt from the hibernation-surviving sockets
    // and their attachments every time the object wakes up.
    this.members = new Map();
    for (const ws of this.ctx.getWebSockets()) {
      const att = ws.deserializeAttachment();
      if (att && att.playerId) this.members.set(att.playerId, ws);
    }
  }

  async loaded() {
    if (!this.meta) {
      this.meta = (await this.ctx.storage.get('meta')) || structuredClone(DEFAULT_META);
      // Migrate groups persisted before modHashes existed - loaded() returns
      // the STORED object as-is for any group that already has one, so a
      // field added later is simply missing rather than defaulted.
      if (!this.meta.modHashes) this.meta.modHashes = {};
      // Same for ownerId/worldSettings: a group persisted before this
      // shipped just has them undefined, not null - normalize so
      // `!this.meta.ownerId` checks below behave the same for old and new
      // groups. ownerId gets backfilled for real on the next hello.
      if (this.meta.ownerId === undefined) this.meta.ownerId = null;
      if (this.meta.ownerName === undefined) this.meta.ownerName = null;
      if (this.meta.worldSettings === undefined) this.meta.worldSettings = null;
    }
    return this.meta;
  }

  async saveMeta() {
    await this.ctx.storage.put('meta', this.meta);
  }

  log(...args) {
    const label = (this.meta && this.meta.groupLabel) || '?';
    console.log(new Date().toISOString(), `[${label}]`, ...args);
  }

  // ---- alarm multiplexing ----
  // A Durable Object gets exactly ONE alarm, and this object needs several
  // independent deadlines (migration gating, abandoned-upload sweep, empty-
  // group purge). Deadlines live in storage; the real alarm is always set
  // to the soonest one, and alarm() dispatches whatever is due.

  async getAlarms() {
    return (await this.ctx.storage.get('alarms')) || {};
  }

  async setAlarmFor(name, at) {
    const alarms = await this.getAlarms();
    if (at === null) delete alarms[name];
    else alarms[name] = at;
    await this.ctx.storage.put('alarms', alarms);
    const times = Object.values(alarms);
    if (times.length === 0) await this.ctx.storage.deleteAlarm();
    else await this.ctx.storage.setAlarm(Math.min(...times));
  }

  async alarm() {
    await this.loaded();
    const alarms = await this.getAlarms();
    const now = Date.now();
    if (alarms.migrate !== undefined && alarms.migrate <= now) {
      await this.setAlarmFor('migrate', null);
      await this.onPendingMigrationDeadline();
    }
    if (alarms.stagingSweep !== undefined && alarms.stagingSweep <= now) {
      await this.setAlarmFor('stagingSweep', null);
      await this.sweepAbandonedStaging();
    }
    if (alarms.purge !== undefined && alarms.purge <= now) {
      await this.setAlarmFor('purge', null);
      await this.purgeIfStillEmpty();
    }
  }

  // ---- fetch routing (everything arrives as an HTTP request, including
  // the WebSocket upgrade, forwarded here by the outer Worker) ----

  async fetch(request) {
    const url = new URL(request.url);
    const meta = await this.loaded();

    // Remember our own group id for logs. In memory only for now - it gets
    // persisted along with the next real state change. Writing storage here
    // would mean a mere /exists or /status probe of a typo'd code leaves a
    // permanent record behind, which the Node version deliberately never did.
    const label = url.searchParams.get('group');
    if (label && meta.groupLabel !== label) meta.groupLabel = label;

    if (request.headers.get('Upgrade') === 'websocket') {
      const pair = new WebSocketPair();
      // acceptWebSocket (not accept) is what opts this socket into
      // hibernation - the whole object can leave memory while the player
      // stays connected, and message/close events wake it back up.
      this.ctx.acceptWebSocket(pair[1]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }

    const path = url.pathname;
    if (path === '/exists') return this.handleExists();
    if (path === '/status') return this.handleStatus();
    if (path === '/save' && request.method === 'GET') return this.handleDownload();
    if (path === '/save' && request.method === 'POST') return this.handleLegacyUpload(request);
    if (path === '/save/begin' && request.method === 'POST') return this.handleUploadBegin();
    if (path.startsWith('/save/part/') && request.method === 'PUT') return this.handleUploadPart(request, url);
    if (path === '/save/commit' && request.method === 'POST') return this.handleUploadCommit(request);
    // The outer Worker pokes this before it starts reading a legacy upload
    // body off the wire, so migration gating knows an upload is coming even
    // before its bytes finish arriving.
    if (path === '/save/incoming' && request.method === 'POST') {
      meta.lastUploadActivityMs = Date.now();
      await this.saveMeta();
      return Response.json({ ok: true });
    }
    return Response.json({ error: 'not found' }, { status: 404 });
  }

  // ---- WebSocket protocol (same messages as the Node coordinator) ----

  send(ws, msg) {
    try {
      ws.send(JSON.stringify(msg));
    } catch {
      // Socket is already dead; its close event does the cleanup.
    }
  }

  broadcast(msg, exceptId = null) {
    for (const [pid, ws] of this.members) {
      if (pid !== exceptId) this.send(ws, msg);
    }
  }

  // One authoritative snapshot of the group, sent to EVERYONE whenever
  // membership or hosting changes - identical shape to the Node version so
  // the mod's roster screens don't know the difference.
  groupStateMessage() {
    const m = this.meta;
    return {
      type: 'state',
      hostId: m.hostId,
      hostDirectAddress: m.hostDirectAddress,
      ownerId: m.ownerId,
      ownerName: m.ownerName,
      worldSettings: m.worldSettings,
      saveVersion: m.saveVersion,
      queue: m.queue,
      members: [...this.members.keys()].map((id) => {
        const mh = m.modHashes[id];
        return { id, name: m.names[id] || id, modHash: mh ? mh.hash : null, modCount: mh ? mh.count : null };
      }),
      away: Object.keys(m.names)
        .filter((id) => !this.members.has(id))
        .map((id) => ({ id, name: m.names[id] || id, lastSeenMs: m.lastSeen[id] || null }))
        .sort((a, b) => (b.lastSeenMs || 0) - (a.lastSeenMs || 0))
        .slice(0, 8),
    };
  }

  broadcastState() {
    this.broadcast(this.groupStateMessage());
  }

  async webSocketMessage(ws, raw) {
    const meta = await this.loaded();
    let msg;
    try {
      msg = JSON.parse(typeof raw === 'string' ? raw : new TextDecoder().decode(raw));
    } catch {
      return this.send(ws, { type: 'error', reason: 'bad_json' });
    }

    const att = ws.deserializeAttachment() || {};

    if (msg.type === 'hello') {
      if (!isValidGroupId(msg.groupId) || !isValidPlayerId(msg.playerId)) {
        return this.send(ws, { type: 'error', reason: 'invalid_id' });
      }
      const playerId = msg.playerId;
      // The connection's identity must survive hibernation, so it rides on
      // the socket itself - this is the DO equivalent of the Node version's
      // per-connection closure variables.
      ws.serializeAttachment({ playerId });

      // A reconnect while the old socket is still lingering: the new socket
      // wins; close the old one quietly so its close event can't later
      // evict the live entry.
      const old = this.members.get(playerId);
      if (old && old !== ws) {
        try { old.close(1000, 'replaced by a newer connection'); } catch { /* already gone */ }
      }
      this.members.set(playerId, ws);

      meta.everHello = true;
      meta.names[playerId] = String(msg.playerName || playerId).slice(0, 32);
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
      meta.modHashes[playerId] = { hash: modHash, count: modCount };
      delete meta.lastSeen[playerId]; // they're here, not "last seen"
      meta.emptySince = null;
      await this.setAlarmFor('purge', null);
      if (!meta.queue.includes(playerId)) meta.queue.push(playerId);
      // Ownership is decided by whoever's hello this Durable Object sees
      // FIRST, permanently - the mint call has no player identity attached,
      // and in practice the creator is always the first to say hello to
      // their own fresh group anyway.
      if (!meta.ownerId) {
        meta.ownerId = playerId;
        meta.ownerName = meta.names[playerId];
        this.log(`${meta.ownerName} is the original owner`);
      }
      this.pruneRememberedNames();
      await this.saveMeta();

      this.log(`${msg.playerName || playerId} connected`);

      // Everyone (newcomer included) gets the fresh state. Deliberately NO
      // auto-promotion here - hosting is claim-based; see im_hosting.
      this.broadcastState();
      return;
    }

    const playerId = att.playerId;
    if (!playerId) return; // must say hello first

    if (msg.type === 'im_hosting') {
      // Hosting is claim-based; guard the claim: if someone else is ALREADY
      // actively hosting and still connected, a second im_hosting must not
      // steal the group's relay routing out from under the live session.
      if (meta.hostId && meta.hostId !== playerId && this.members.has(meta.hostId)) {
        this.log(`${playerId} tried to claim host but ${meta.hostId} is already hosting - rejected`);
        return this.send(ws, { type: 'error', reason: 'already_hosting' });
      }
      meta.hostId = playerId;
      meta.lastHostId = playerId;
      // The new host's direct address (if any) hasn't been discovered yet -
      // clearing stops friends from trying the PREVIOUS host's stale one.
      meta.hostDirectAddress = null;
      await this.saveMeta();
      this.broadcast({ type: 'host_confirmed', hostId: playerId }, playerId);
      this.broadcastState();
      this.log(`${playerId} is now hosting`);
      return;
    }

    if (msg.type === 'direct_address') {
      // Only the current host is trusted to set this.
      if (playerId !== meta.hostId) return;
      meta.hostDirectAddress = typeof msg.address === 'string' ? msg.address : null;
      await this.saveMeta();
      this.broadcast({ type: 'host_direct_address', address: meta.hostDirectAddress }, playerId);
      this.log(`host direct address: ${meta.hostDirectAddress || '(none - UPnP unavailable)'}`);
      return;
    }

    if (msg.type === 'save_uploaded') {
      // Legacy no-op: the HTTP upload path is the ONE place saveVersion is
      // bumped. Silence, not an error, for any old client that sends it.
      return;
    }

    if (msg.type === 'leaving') {
      await this.handleDeparture(playerId, ws);
      return;
    }

    // --- Save transfer over the WebSocket (preferred transport) ---
    //
    // Same storage-backed staging/chunk rows as the HTTP chunked-upload path
    // below (finishUpload/storeChunks are shared), just driven by messages
    // on the connection every client already has open instead of separate
    // HTTP requests. Incoming WebSocket messages are billed at a 20:1
    // discount versus a plain request, and outgoing ones aren't billed at
    // all - so a whole migration's save transfer costs a small fraction of
    // what the same bytes cost as chunked HTTP PUT/GET. The HTTP endpoints
    // stay in place for any coordinator/client version that doesn't speak
    // this yet (the client gives up waiting for an ack and falls back).

    if (msg.type === 'save_upload_begin') {
      // Cap concurrent uploads per group at 1 (a Durable Object only ever
      // holds one group's staging rows, so any existing one is this
      // group's) - a legitimate migration never needs two in flight, and
      // without this a client could open unlimited uploadIds and stage
      // unbounded storage.
      const existing = await this.ctx.storage.list({ prefix: 'staging:', limit: 1 });
      if (existing.size > 0) {
        this.send(ws, { type: 'save_upload_error', reason: 'upload_already_in_progress' });
        return;
      }
      const uploadId = crypto.randomUUID();
      await this.ctx.storage.put(`staging:${uploadId}`, { startedMs: Date.now(), chunks: 0, size: 0, partsReceived: 0 });
      meta.lastUploadActivityMs = Date.now();
      await this.saveMeta();
      await this.setAlarmFor('stagingSweep', Date.now() + STAGING_TTL_MS);
      this.send(ws, { type: 'save_upload_begin_ack', uploadId });
      return;
    }

    if (msg.type === 'save_upload_part') {
      const uploadId = msg.uploadId;
      const staging = await this.ctx.storage.get(`staging:${uploadId}`);
      if (!staging) {
        this.send(ws, { type: 'save_upload_error', uploadId, reason: 'unknown_upload' });
        return;
      }
      meta.lastUploadActivityMs = Date.now();
      await this.saveMeta();

      // Parts must arrive in order (the mod sends sequentially, waiting for
      // each ack before the next). A repeat of the most recent part is an
      // idempotent retry (its ack got lost); anything else is an error.
      const expected = staging.partsReceived || 0;
      if (msg.index === expected - 1) {
        this.send(ws, { type: 'save_upload_part_ack', uploadId, index: msg.index });
        return;
      }
      if (!Number.isInteger(msg.index) || msg.index !== expected) {
        this.send(ws, { type: 'save_upload_error', uploadId, reason: `expected_part_${expected}` });
        return;
      }

      const buf = base64ToArrayBuffer(msg.data);
      if (msg.index === 0) {
        // Never let garbage replace the only copy of a world: every zip starts "PK".
        const head = new Uint8Array(buf.slice(0, 4));
        if (head.length < 4 || head[0] !== 0x50 || head[1] !== 0x4b) {
          this.send(ws, { type: 'save_upload_error', uploadId, reason: 'not_a_zip' });
          return;
        }
      }
      if ((staging.size || 0) + buf.byteLength > UPLOAD_MAX_BYTES) {
        for (let i = 0; i < (staging.chunks || 0); i++) {
          await this.ctx.storage.delete(`chunk:${uploadId}:${i}`);
        }
        await this.ctx.storage.delete(`staging:${uploadId}`);
        this.log(`rejected websocket save upload - exceeded ${UPLOAD_MAX_BYTES / 1024 / 1024 / 1024}GB cap`);
        this.send(ws, { type: 'save_upload_error', uploadId, reason: 'too_large' });
        return;
      }
      staging.chunks = await this.storeChunks(uploadId, buf, staging.chunks || 0);
      staging.size = (staging.size || 0) + buf.byteLength;
      staging.partsReceived = expected + 1;
      staging.startedMs = Date.now();
      await this.ctx.storage.put(`staging:${uploadId}`, staging);
      this.send(ws, { type: 'save_upload_part_ack', uploadId, index: msg.index });
      return;
    }

    if (msg.type === 'save_upload_commit') {
      const uploadId = msg.uploadId;
      const staging = await this.ctx.storage.get(`staging:${uploadId}`);
      if (!staging) {
        this.send(ws, { type: 'save_upload_error', uploadId, reason: 'unknown_upload' });
        return;
      }
      await this.ctx.storage.delete(`staging:${uploadId}`);
      const result = await this.finishUpload(uploadId, staging.chunks, staging.size);
      const body = await result.json();
      this.send(ws, { type: 'save_upload_commit_ack', uploadId, saveVersion: body.saveVersion });
      return;
    }

    if (msg.type === 'save_download_request') {
      const save = await this.currentSave();
      if (!save) {
        this.send(ws, { type: 'save_download_error', reason: 'no_save' });
        return;
      }
      this.send(ws, { type: 'save_download_begin', totalBytes: save.size, totalParts: save.chunks, saveVersion: meta.saveVersion });
      for (let i = 0; i < save.chunks; i++) {
        const chunk = await this.ctx.storage.get(`chunk:${save.id}:${i}`);
        if (!chunk) {
          this.send(ws, { type: 'save_download_error', reason: 'missing_chunk' });
          return;
        }
        this.send(ws, { type: 'save_download_part', index: i, data: arrayBufferToBase64(chunk) });
      }
      this.send(ws, { type: 'save_download_done' });
      this.log(`save downloaded over websocket (${(save.size / 1024 / 1024).toFixed(1)} MB, v${meta.saveVersion})`);
      return;
    }

    // The current host reports its live gamerule/difficulty/time/weather
    // snapshot after opening (and again after applying an
    // owner_settings_change) so the owner's settings screen can show real
    // current values even when the owner isn't the one hosting. Only the
    // host is trusted to set this - same as direct_address elsewhere.
    if (msg.type === 'world_settings_report') {
      if (playerId !== meta.hostId) return;
      meta.worldSettings = msg.settings || null;
      await this.saveMeta();
      this.broadcastState();
      return;
    }

    // The owner asking whoever's currently hosting to change a setting. No
    // target needed from the owner's side - same "always means the host"
    // convention as punch_candidate/relay_* below. This DO doesn't check
    // that the sender IS the owner (it never validates payloads); it stamps
    // fromPlayerId from the connection's own validated identity via
    // relayToHost, and the acting host cross-checks that against the
    // broadcast ownerId before applying anything.
    if (msg.type === 'owner_settings_change') {
      this.relayToHost(playerId, { type: 'owner_settings_change', action: msg.action, value: msg.value });
      return;
    }

    // Hole-punch signaling: pass-through address exchange. A friend sending
    // always means "to the host"; the host must name a toPlayerId.
    if (msg.type === 'punch_candidate') {
      if (playerId === meta.hostId) {
        if (!msg.toPlayerId) return;
        this.relayToPlayer(msg.toPlayerId, { type: 'punch_candidate', address: msg.address });
      } else {
        this.relayToHost(playerId, { type: 'punch_candidate', address: msg.address });
      }
      return;
    }

    // Relay switchboard: friend -> (implied) host, host -> named friend.
    // Never inspects msg.data.
    if (msg.type === 'relay_open' || msg.type === 'relay_data' || msg.type === 'relay_close') {
      if (playerId === meta.hostId) {
        if (!msg.toPlayerId) return;
        this.relayToPlayer(msg.toPlayerId, { type: msg.type, streamId: msg.streamId, data: msg.data });
      } else {
        this.relayToHost(playerId, { type: msg.type, streamId: msg.streamId, data: msg.data });
      }
      return;
    }
  }

  relayToHost(fromPlayerId, msg) {
    const hostWs = this.meta.hostId ? this.members.get(this.meta.hostId) : null;
    if (!hostWs) {
      const senderWs = this.members.get(fromPlayerId);
      if (senderWs) this.send(senderWs, { type: 'relay_error', streamId: msg.streamId, reason: 'no_host' });
      return;
    }
    this.send(hostWs, { ...msg, fromPlayerId });
  }

  relayToPlayer(toPlayerId, msg) {
    const targetWs = this.members.get(toPlayerId);
    if (targetWs) this.send(targetWs, msg);
  }

  async webSocketClose(ws) {
    const att = ws.deserializeAttachment() || {};
    if (att.playerId) await this.handleDeparture(att.playerId, ws);
  }

  async webSocketError(ws) {
    const att = ws.deserializeAttachment() || {};
    if (att.playerId) await this.handleDeparture(att.playerId, ws);
  }

  async handleDeparture(playerId, ws) {
    const meta = await this.loaded();

    // Idempotent: the 'leaving' message and the socket close both land here
    // for a clean quit. And if this socket was already replaced by a newer
    // connection from the same player (see hello), its departure is stale -
    // it must not evict the live entry.
    if (this.members.get(playerId) !== ws && !meta.queue.includes(playerId)) return;
    if (this.members.get(playerId) === ws) this.members.delete(playerId);
    else if (this.members.has(playerId)) return; // stale socket; live one remains

    const wasHost = meta.hostId === playerId;
    meta.queue = meta.queue.filter((id) => id !== playerId);
    meta.lastSeen[playerId] = Date.now();
    this.log(`${playerId} left${wasHost ? ' (was host)' : ''}`);

    if (this.members.size === 0) {
      meta.emptySince = Date.now();
      await this.setAlarmFor('purge', Date.now() + PURGE_AFTER_EMPTY_MS);
    }

    if (wasHost) {
      // Tell everyone still connected to close out any open relay streams
      // cleanly instead of hanging.
      this.broadcast({ type: 'relay_reset', reason: 'host_left' });
      meta.hostId = null;
    }

    await this.saveMeta();

    // Broadcast AFTER hostId is cleared, so a departing host's roster
    // update already reads "no host" instead of naming someone who's gone.
    this.broadcastState();

    if (wasHost && meta.queue.length > 0) {
      // Don't migrate yet - wait for the outgoing host's save upload to
      // land (or time out) so the new host never downloads a stale version.
      await this.beginPendingMigration();
    } else if (wasHost) {
      this.log('no one left to host');
    }
  }

  // ---- migration gating (fixes the stale-save race; see the Node
  // coordinator's comments for the full history) ----

  async beginPendingMigration() {
    const meta = await this.loaded();
    if (meta.pendingMigrationStartedAt !== null) return; // already waiting
    meta.pendingMigrationStartedAt = Date.now();
    await this.saveMeta();
    await this.setAlarmFor('migrate', Date.now() + MIGRATE_UPLOAD_TIMEOUT_MS);
  }

  async onPendingMigrationDeadline() {
    const meta = await this.loaded();
    if (meta.pendingMigrationStartedAt === null) return;

    const waitedMs = Date.now() - meta.pendingMigrationStartedAt;
    const uploadLooksAlive = meta.lastUploadActivityMs > 0
      && Date.now() - meta.lastUploadActivityMs < MIGRATE_UPLOAD_RECENT_ACTIVITY_MS;
    if (uploadLooksAlive && waitedMs < MIGRATE_UPLOAD_HARD_CAP_MS) {
      this.log(`departing host's upload still in flight after ${Math.round(waitedMs / 1000)}s - holding the migration for it`);
      await this.setAlarmFor('migrate', Date.now() + MIGRATE_UPLOAD_EXTENSION_MS);
      return;
    }

    this.log(`upload didn't arrive within ${Math.round(waitedMs / 1000)}s - migrating with last known save v${meta.saveVersion} anyway`);
    await this.resolvePendingMigration();
  }

  async resolvePendingMigration() {
    const meta = await this.loaded();
    if (meta.pendingMigrationStartedAt === null) return;
    meta.pendingMigrationStartedAt = null;
    await this.saveMeta();
    await this.setAlarmFor('migrate', null);

    // Pick the next host from the queue, skipping anyone no longer
    // connected. Being chosen is a designation, not a confirmation: hostId
    // only ever gets set by im_hosting.
    while (meta.queue.length > 0 && !this.members.has(meta.queue[0])) meta.queue.shift();
    const newHost = meta.queue[0] || null;
    await this.saveMeta();

    if (newHost) {
      this.log(`migrating host -> ${newHost} (save v${meta.saveVersion}, previous host ${meta.lastHostId || 'unknown'})`);
      this.broadcast({
        type: 'migrate',
        newHostId: newHost,
        saveVersion: meta.saveVersion,
        previousHostId: meta.lastHostId || null,
      });
      this.broadcastState(); // hostId stays null until the new host's im_hosting lands
    } else {
      this.log('no one left to host');
    }
  }

  // ---- save transfer ----
  // The zip is stored as 2MB chunk rows under chunk:<uploadId>:<n>, with a
  // single 'save' row pointing at the current uploadId - so flipping to a
  // freshly-uploaded zip is one atomic pointer write, and a crash mid-upload
  // can never leave a half-written save where the good one used to be
  // (same guarantee the Node version got from write-temp-then-rename).

  async currentSave() {
    return (await this.ctx.storage.get('save')) || null;
  }

  async handleExists() {
    const meta = await this.loaded();
    const save = await this.currentSave();
    return Response.json({ exists: meta.everHello || save !== null });
  }

  async handleStatus() {
    const meta = await this.loaded();
    const save = await this.currentSave();
    if (!meta.everHello && !save) return Response.json({ exists: false }, { status: 404 });
    return Response.json({
      exists: true,
      online: this.members.size,
      memberNames: [...this.members.keys()].map((id) => meta.names[id] || id),
      hostName: meta.hostId ? (meta.names[meta.hostId] || meta.hostId) : null,
      saveVersion: meta.saveVersion,
    });
  }

  async handleDownload() {
    const save = await this.currentSave();
    if (!save) return new Response('no save yet', { status: 404 });
    const storage = this.ctx.storage;
    let i = 0;
    const stream = new ReadableStream({
      async pull(controller) {
        if (i >= save.chunks) {
          controller.close();
          return;
        }
        const chunk = await storage.get(`chunk:${save.id}:${i}`);
        i += 1;
        if (!chunk) {
          controller.error(new Error('missing save chunk'));
          return;
        }
        controller.enqueue(new Uint8Array(chunk));
      },
    });
    return new Response(stream, {
      headers: {
        'Content-Type': 'application/zip',
        'Content-Length': String(save.size),
        'Content-Disposition': `attachment; filename="${(this.meta && this.meta.groupLabel) || 'save'}.zip"`,
      },
    });
  }

  // Shared by both upload paths once a complete zip's chunks are staged:
  // flip the current-save pointer, delete the old chunks, bump the version,
  // and let everyone (including a migration waiting on exactly this upload)
  // know.
  async finishUpload(uploadId, chunks, size) {
    const meta = await this.loaded();
    const old = await this.currentSave();
    await this.ctx.storage.put('save', { id: uploadId, chunks, size });
    if (old) {
      for (let i = 0; i < old.chunks; i++) await this.ctx.storage.delete(`chunk:${old.id}:${i}`);
    }
    meta.saveVersion += 1;
    // The upload is DONE - nothing is in flight anymore. Leaving a fresh
    // timestamp here would make migration gating treat the next 30s as "an
    // upload is still streaming" and stall a handoff for no reason.
    meta.lastUploadActivityMs = 0;
    await this.saveMeta();
    this.broadcast({ type: 'save_ready', saveVersion: meta.saveVersion });
    this.log(`save uploaded (${(size / 1024 / 1024).toFixed(1)} MB) -> v${meta.saveVersion}`);

    if (meta.pendingMigrationStartedAt !== null) {
      await this.resolvePendingMigration();
    }
    return Response.json({ ok: true, saveVersion: meta.saveVersion });
  }

  async storeChunks(uploadId, buf, firstIndex) {
    let n = firstIndex;
    for (let off = 0; off < buf.byteLength; off += CHUNK_SIZE) {
      await this.ctx.storage.put(`chunk:${uploadId}:${n}`, buf.slice(off, off + CHUNK_SIZE));
      n += 1;
    }
    return n;
  }

  // The original whole-zip-in-one-POST path, kept so the current mod works
  // unchanged for saves under Cloudflare's 100MB per-request cap. The outer
  // Worker has already unwrapped the multipart form into a raw body.
  async handleLegacyUpload(request) {
    const buf = new Uint8Array(await request.arrayBuffer());
    // Never let garbage replace the only copy of a world: every zip starts "PK".
    if (buf.length < 4 || buf[0] !== 0x50 || buf[1] !== 0x4b) {
      this.log(`rejected save upload - not a zip (${buf.length} bytes)`);
      return Response.json({ error: 'not a zip' }, { status: 400 });
    }
    const uploadId = crypto.randomUUID();
    const chunks = await this.storeChunks(uploadId, buf.buffer, 0);
    return this.finishUpload(uploadId, chunks, buf.length);
  }

  // Chunked upload for saves too big for one request: begin -> N parts ->
  // commit. Parts land under the same chunk:<uploadId>:<n> keys they'll be
  // served from, so commit is just validation + the atomic pointer flip.
  async handleUploadBegin() {
    const meta = await this.loaded();
    // Same concurrent-upload cap as the WebSocket path - see save_upload_begin.
    const existing = await this.ctx.storage.list({ prefix: 'staging:', limit: 1 });
    if (existing.size > 0) {
      return Response.json({ error: 'upload already in progress' }, { status: 409 });
    }
    const uploadId = crypto.randomUUID();
    await this.ctx.storage.put(`staging:${uploadId}`, { startedMs: Date.now(), chunks: 0, size: 0 });
    meta.lastUploadActivityMs = Date.now();
    await this.saveMeta();
    await this.setAlarmFor('stagingSweep', Date.now() + STAGING_TTL_MS);
    return Response.json({ ok: true, uploadId });
  }

  async handleUploadPart(request, url) {
    const meta = await this.loaded();
    const uploadId = url.searchParams.get('uploadId') || '';
    const partIndex = Number(url.pathname.split('/').pop());
    const staging = await this.ctx.storage.get(`staging:${uploadId}`);
    if (!staging) return Response.json({ error: 'unknown uploadId' }, { status: 400 });

    meta.lastUploadActivityMs = Date.now();
    await this.saveMeta();

    // Parts must arrive in order (the mod uploads sequentially). A repeat
    // of the most recent part is an idempotent retry (e.g. the client's
    // response got lost); anything else is an error.
    const expected = staging.partsReceived || 0;
    if (partIndex === expected - 1) {
      return Response.json({ ok: true, partsReceived: expected });
    }
    if (!Number.isInteger(partIndex) || partIndex !== expected) {
      return Response.json({ error: `expected part ${expected}` }, { status: 400 });
    }

    const buf = await request.arrayBuffer();
    if (partIndex === 0) {
      // Never let garbage replace the only copy of a world: every zip starts "PK".
      const head = new Uint8Array(buf.slice(0, 4));
      if (head.length < 4 || head[0] !== 0x50 || head[1] !== 0x4b) {
        return Response.json({ error: 'not a zip' }, { status: 400 });
      }
    }
    if ((staging.size || 0) + buf.byteLength > UPLOAD_MAX_BYTES) {
      for (let i = 0; i < (staging.chunks || 0); i++) {
        await this.ctx.storage.delete(`chunk:${uploadId}:${i}`);
      }
      await this.ctx.storage.delete(`staging:${uploadId}`);
      this.log(`rejected chunked save upload - exceeded ${UPLOAD_MAX_BYTES / 1024 / 1024 / 1024}GB cap`);
      return Response.json({ error: 'too large' }, { status: 413 });
    }
    staging.chunks = await this.storeChunks(uploadId, buf, staging.chunks || 0);
    staging.size = (staging.size || 0) + buf.byteLength;
    staging.partsReceived = expected + 1;
    staging.startedMs = Date.now();
    await this.ctx.storage.put(`staging:${uploadId}`, staging);
    return Response.json({ ok: true, partsReceived: staging.partsReceived });
  }

  async handleUploadCommit(request) {
    let body;
    try {
      body = await request.json();
    } catch {
      return Response.json({ error: 'bad json' }, { status: 400 });
    }
    const uploadId = String(body.uploadId || '');
    const staging = await this.ctx.storage.get(`staging:${uploadId}`);
    if (!staging) return Response.json({ error: 'unknown uploadId' }, { status: 400 });
    if (body.parts !== undefined && body.parts !== staging.partsReceived) {
      return Response.json({ error: `have ${staging.partsReceived} parts, commit says ${body.parts}` }, { status: 400 });
    }
    await this.ctx.storage.delete(`staging:${uploadId}`);
    return this.finishUpload(uploadId, staging.chunks, staging.size);
  }

  async sweepAbandonedStaging() {
    const now = Date.now();
    const stagings = await this.ctx.storage.list({ prefix: 'staging:' });
    let stillActive = false;
    for (const [key, staging] of stagings) {
      if (now - staging.startedMs < STAGING_TTL_MS) {
        stillActive = true;
        continue;
      }
      const uploadId = key.slice('staging:'.length);
      for (let i = 0; i < (staging.chunks || 0); i++) {
        await this.ctx.storage.delete(`chunk:${uploadId}:${i}`);
      }
      await this.ctx.storage.delete(key);
      this.log(`swept abandoned upload ${uploadId}`);
    }
    if (stillActive) await this.setAlarmFor('stagingSweep', now + STAGING_TTL_MS);
  }

  async purgeIfStillEmpty() {
    const meta = await this.loaded();
    if (this.members.size > 0 || meta.emptySince === null) return;
    if (Date.now() - meta.emptySince < PURGE_AFTER_EMPTY_MS) return;
    this.log(`empty for over ${Math.round(PURGE_AFTER_EMPTY_MS / 86400000)}d - deleting group and its save`);
    // deleteAll wipes every row including the alarm bookkeeping; the group
    // id simply stops existing, exactly like the Node version after purge.
    await this.ctx.storage.deleteAll();
    await this.ctx.storage.deleteAlarm();
    this.meta = null;
  }

  pruneRememberedNames() {
    const m = this.meta;
    const ids = Object.keys(m.names);
    if (ids.length <= MAX_REMEMBERED_NAMES) return;
    const droppable = ids
      .filter((id) => !this.members.has(id))
      .sort((a, b) => (m.lastSeen[a] || 0) - (m.lastSeen[b] || 0));
    for (const id of droppable.slice(0, ids.length - MAX_REMEMBERED_NAMES)) {
      delete m.names[id];
      delete m.lastSeen[id];
      delete m.modHashes[id];
    }
  }
}
