// Exercises the new WebSocket-transport save upload/download protocol
// directly against a running coordinator (node server.js), the same way
// coordinator/coordinator/test_migration.js exercises host migration with
// fake clients instead of real game clients. Verifies: chunked upload
// reassembles correctly (zip-magic check, atomic write), saveVersion bumps,
// and download round-trips byte-for-byte identical to what was uploaded.

const WebSocket = require('ws');

// Defaults to the plain Node coordinator; pass a Worker URL to test the
// Cloudflare port instead, e.g.:
//   node test_ws_save_transfer.js ws://localhost:8787/ws
// (the Worker routes by a ?group= query param rather than the 'hello'
// message alone, which connect() below appends when present.)
const COORDINATOR_BASE = process.argv[2] || 'ws://localhost:8080/ws';
const GROUP_ID = 'ws-transfer-test-' + Date.now();
const PART_BYTES = 512 * 1024;

function wsUrlFor(groupId) {
  return COORDINATOR_BASE.includes('?')
    ? `${COORDINATOR_BASE}&group=${groupId}`
    : `${COORDINATOR_BASE}?group=${groupId}`;
}

// A single persistent listener buffers every inbound message into an array
// from the moment of connection - matching how the real Java client's
// saveTransferInbox (a BlockingQueue fed unconditionally by the websocket
// listener) never loses a message that arrives before something is actively
// waiting for it. A fresh per-call listener instead (this script's first
// draft) drops anything that arrives in the gap between two waitFor()
// calls - the download side sends its whole part burst synchronously right
// after 'save_download_begin', faster than a real network round trip, so
// that gap is real, not just theoretical.
function connect(playerId, groupId = GROUP_ID) {
  return new Promise((resolve, reject) => {
    // The Worker routes the Upgrade request itself by ?group=, before any
    // message is ever sent - a second 'hello' with a different groupId on
    // an already-connected socket (which the Node coordinator tolerates,
    // since it only learns groupId from 'hello') would just keep talking to
    // the SAME Durable Object. So every distinct group gets its own
    // connection, which works identically against both backends.
    const ws = new WebSocket(wsUrlFor(groupId));
    ws.inbox = [];
    ws.on('message', (raw) => ws.inbox.push(JSON.parse(raw.toString())));
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'hello', groupId, playerId, playerName: playerId }));
      resolve(ws);
    });
    ws.on('error', reject);
  });
}

function waitFor(ws, predicate, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    function tryConsume() {
      const idx = ws.inbox.findIndex(predicate);
      if (idx !== -1) {
        const [msg] = ws.inbox.splice(idx, 1);
        resolve(msg);
        return;
      }
      if (Date.now() > deadline) {
        reject(new Error('timeout waiting for message matching ' + predicate));
        return;
      }
      setTimeout(tryConsume, 20);
    }
    tryConsume();
  });
}

// Builds a deterministic fake "zip" (real magic bytes + pseudo-random body)
// large enough to require multiple parts in both directions.
function fakeZip(size) {
  const buf = Buffer.alloc(size);
  buf[0] = 0x50; buf[1] = 0x4b; buf[2] = 0x03; buf[3] = 0x04; // "PK\x03\x04"
  for (let i = 4; i < size; i++) buf[i] = (i * 2654435761) % 256;
  return buf;
}

async function main() {
  const zip = fakeZip(PART_BYTES * 3 + 12345); // forces a non-whole final part
  console.log(`Test zip: ${zip.length} bytes, group ${GROUP_ID}`);

  const uploader = await connect('alice');
  await waitFor(uploader, (m) => m.type === 'state'); // initial state after hello

  // --- Upload ---
  uploader.send(JSON.stringify({ type: 'save_upload_begin' }));
  const beginAck = await waitFor(uploader, (m) => m.type === 'save_upload_begin_ack');
  const uploadId = beginAck.uploadId;
  console.log('Got uploadId', uploadId);

  const parts = Math.ceil(zip.length / PART_BYTES);
  for (let i = 0; i < parts; i++) {
    const off = i * PART_BYTES;
    const part = zip.subarray(off, Math.min(off + PART_BYTES, zip.length));
    uploader.send(JSON.stringify({ type: 'save_upload_part', uploadId, index: i, data: part.toString('base64') }));
    const ack = await waitFor(uploader, (m) => m.type === 'save_upload_part_ack' && m.index === i);
    if (ack.uploadId !== uploadId) throw new Error('part ack uploadId mismatch');
  }
  console.log(`Uploaded ${parts} parts`);

  uploader.send(JSON.stringify({ type: 'save_upload_commit', uploadId }));
  const commitAck = await waitFor(uploader, (m) => m.type === 'save_upload_commit_ack');
  console.log('Commit ack, saveVersion =', commitAck.saveVersion);
  if (commitAck.saveVersion !== 1) throw new Error('expected saveVersion 1, got ' + commitAck.saveVersion);

  // Idempotent-retry check: resending the last part after commit should be
  // harmless (simulates a lost ack causing a client retry) - NOT expected to
  // succeed post-commit since the staging entry is gone; this just confirms
  // the coordinator doesn't crash on it.
  uploader.send(JSON.stringify({ type: 'save_upload_part', uploadId, index: parts - 1, data: zip.subarray(0, 10).toString('base64') }));
  const staleAck = await waitFor(uploader, (m) => m.type === 'save_upload_error' || m.type === 'save_upload_part_ack');
  console.log('Post-commit stale part message (expected):', staleAck.type, staleAck.reason || '');

  // --- Download (fresh connection, simulating the new host) ---
  const downloader = await connect('bob');
  await waitFor(downloader, (m) => m.type === 'state');

  downloader.send(JSON.stringify({ type: 'save_download_request' }));
  const dlBegin = await waitFor(downloader, (m) => m.type === 'save_download_begin');
  console.log('Download begin:', dlBegin.totalBytes, 'bytes,', dlBegin.totalParts, 'parts, saveVersion', dlBegin.saveVersion);
  if (dlBegin.totalBytes !== zip.length) throw new Error(`totalBytes mismatch: ${dlBegin.totalBytes} != ${zip.length}`);

  const received = Buffer.alloc(dlBegin.totalBytes);
  let writeOffset = 0;
  for (let i = 0; i < dlBegin.totalParts; i++) {
    const partMsg = await waitFor(downloader, (m) => m.type === 'save_download_part' && m.index === i);
    const chunk = Buffer.from(partMsg.data, 'base64');
    chunk.copy(received, writeOffset);
    writeOffset += chunk.length;
  }
  await waitFor(downloader, (m) => m.type === 'save_download_done');
  console.log(`Downloaded ${writeOffset} bytes total`);

  if (!received.equals(zip)) {
    throw new Error('DOWNLOADED BYTES DO NOT MATCH UPLOADED BYTES - integrity check failed!');
  }
  console.log('Byte-for-byte integrity check PASSED.');

  // --- No-save-yet case, fresh group ---
  const emptyGroupId = 'ws-transfer-empty-' + Date.now();
  const emptyGroupWs = await connect('carol', emptyGroupId);
  await waitFor(emptyGroupWs, (m) => m.type === 'state');
  emptyGroupWs.send(JSON.stringify({ type: 'save_download_request' }));
  const noSave = await waitFor(emptyGroupWs, (m) => m.type === 'save_download_error');
  console.log('No-save-yet case:', noSave.reason);
  if (noSave.reason !== 'no_save') throw new Error('expected reason no_save, got ' + noSave.reason);

  uploader.close();
  downloader.close();
  emptyGroupWs.close();
  console.log('\nALL WS SAVE-TRANSFER PROTOCOL TESTS PASSED');
  process.exit(0);
}

main().catch((e) => {
  console.error('TEST FAILED:', e);
  process.exit(1);
});
