// Coordinator server for the host-migration Minecraft mod.
//
// Responsibilities (and ONLY these - it never simulates the world):
//   1. Track which players are currently online, per friend-group ("group").
//   2. Know the join order, so it can pick who becomes host next.
//   3. Broadcast a "migrate" message telling everyone to reconnect to the new host.
//   4. Hold the latest zipped world save so the next host can pull it before
//      opening the world (simple file relay, nothing fancy).
//
// This is intentionally dumb and small. It's a phone book + a dead-drop for
// the save file, not a game server. That's what keeps it free to run.

const http = require('http');
const express = require('express');
const multer = require('multer');
const { WebSocketServer } = require('ws');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 8080;
const SAVES_DIR = path.join(__dirname, 'saves');
fs.mkdirSync(SAVES_DIR, { recursive: true });

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

// groupId -> { members: Map<playerId, ws>, queue: [playerId,...], hostId: string|null, saveVersion: number }
const groups = new Map();

function getGroup(groupId) {
  if (!groups.has(groupId)) {
    groups.set(groupId, { members: new Map(), queue: [], hostId: null, saveVersion: 0 });
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

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

// Picks the next host from the queue, skipping anyone no longer connected.
function promoteNextHost(groupId) {
  const group = getGroup(groupId);
  group.hostId = null;
  while (group.queue.length > 0) {
    const candidate = group.queue[0];
    if (group.members.has(candidate)) {
      group.hostId = candidate;
      break;
    }
    group.queue.shift();
  }
  return group.hostId;
}

wss.on('connection', (ws) => {
  let groupId = null;
  let playerId = null;

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return send(ws, { type: 'error', reason: 'bad_json' });
    }

    if (msg.type === 'hello') {
      groupId = msg.groupId;
      playerId = msg.playerId;
      const group = getGroup(groupId);

      group.members.set(playerId, ws);
      if (!group.queue.includes(playerId)) group.queue.push(playerId);

      log(`[${groupId}] ${msg.playerName || playerId} connected`);

      // Tell the newcomer the current state: who's host, what save version exists.
      send(ws, {
        type: 'state',
        hostId: group.hostId,
        saveVersion: group.saveVersion,
        queue: group.queue,
      });

      // If nobody's hosting yet, this player becomes host candidate immediately.
      if (!group.hostId) {
        const newHost = promoteNextHost(groupId);
        if (newHost) {
          broadcast(group, { type: 'migrate', newHostId: newHost, saveVersion: group.saveVersion });
        }
      }
      return;
    }

    if (!groupId || !playerId) return; // must say hello first

    const group = getGroup(groupId);

    if (msg.type === 'im_hosting') {
      group.hostId = playerId;
      broadcast(group, { type: 'host_confirmed', hostId: playerId }, playerId);
      log(`[${groupId}] ${playerId} is now hosting`);
      return;
    }

    if (msg.type === 'save_uploaded') {
      // Client tells us it finished uploading via the HTTP endpoint below.
      group.saveVersion += 1;
      log(`[${groupId}] save updated to v${group.saveVersion} by ${playerId}`);
      return;
    }

    if (msg.type === 'leaving') {
      handleDeparture(groupId, playerId);
      return;
    }
  });

  ws.on('close', () => {
    if (groupId && playerId) handleDeparture(groupId, playerId);
  });
});

function handleDeparture(groupId, playerId) {
  const group = getGroup(groupId);
  const wasHost = group.hostId === playerId;

  group.members.delete(playerId);
  group.queue = group.queue.filter((id) => id !== playerId);
  log(`[${groupId}] ${playerId} left${wasHost ? ' (was host)' : ''}`);

  if (wasHost) {
    const newHost = promoteNextHost(groupId);
    if (newHost) {
      log(`[${groupId}] migrating host -> ${newHost}`);
      broadcast(group, { type: 'migrate', newHostId: newHost, saveVersion: group.saveVersion });
    } else {
      log(`[${groupId}] no one left to host`);
    }
  }
}

// --- Save file relay (plain HTTP, separate from the WebSocket signaling) ---

const upload = multer({ limits: { fileSize: 2 * 1024 * 1024 * 1024 } }); // 2GB cap for now

app.post('/groups/:groupId/save', upload.single('save'), (req, res) => {
  const { groupId } = req.params;
  if (!req.file) return res.status(400).send('missing file');
  const dest = path.join(SAVES_DIR, `${groupId}.zip`);
  fs.writeFileSync(dest, req.file.buffer);
  const group = getGroup(groupId);
  group.saveVersion += 1;
  broadcast(group, { type: 'save_ready', saveVersion: group.saveVersion });
  log(`[${groupId}] save uploaded (${(req.file.buffer.length / 1024 / 1024).toFixed(1)} MB) -> v${group.saveVersion}`);
  res.json({ ok: true, saveVersion: group.saveVersion });
});

app.get('/groups/:groupId/save', (req, res) => {
  const { groupId } = req.params;
  const filePath = path.join(SAVES_DIR, `${groupId}.zip`);
  if (!fs.existsSync(filePath)) return res.status(404).send('no save yet');
  res.download(filePath, `${groupId}.zip`);
});

app.get('/health', (req, res) => res.json({ ok: true, groups: groups.size }));

server.listen(PORT, () => log(`coordinator listening on :${PORT}`));

module.exports = { app, server, groups };
