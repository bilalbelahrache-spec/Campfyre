// One-off manual verification for the new app-level heartbeat sweep
// (group.js: sweepDeadConnections / HEARTBEAT_STALE_AFTER_MS). Not part of
// the permanent suite - it deliberately waits out the real ~65s staleness
// window, which would make every `npm test` run slower forever.
//
// Simulates exactly the case the sweep exists for: Alice's socket stays
// OPEN (no close(), no 'leaving') but she stops sending anything at all -
// a wedged process / dead network path with no clean TCP close. Confirms
// Bob still gets migrated to host once the sweep notices the silence,
// instead of waiting forever for a close event that may never come.
//
// Run against a local `wrangler dev` (npm run dev) on port 8787.

const WebSocket = require('ws');
const BASE = process.env.COORDINATOR_URL || 'http://127.0.0.1:8787';
const WS_BASE = BASE.replace(/^http/, 'ws');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const mintRes = await fetch(`${BASE}/groups/new`, { method: 'POST' });
  const { groupId } = await mintRes.json();
  console.log('group:', groupId);

  const alice = new WebSocket(`${WS_BASE}/ws?group=${groupId}`);
  const bob = new WebSocket(`${WS_BASE}/ws?group=${groupId}`);

  let bobPromoted = false;

  await new Promise((resolve) => alice.on('open', resolve));
  alice.send(JSON.stringify({ type: 'hello', groupId, playerId: 'alice', playerName: 'Alice' }));
  alice.send(JSON.stringify({ type: 'im_hosting' }));

  await new Promise((resolve) => bob.on('open', resolve));
  bob.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    console.log('[bob]', msg.type, msg.hostId !== undefined ? `hostId=${msg.hostId}` : '');
    if (msg.type === 'migrate') bobPromoted = true;
  });
  bob.send(JSON.stringify({ type: 'hello', groupId, playerId: 'bob', playerName: 'Bob' }));

  // Bob simulates a real client's 20s keepaliveTick, so HE stays fresh while
  // ONLY Alice goes silent - isolates the one variable this test exists to
  // check. Cleared once the whole test winds down (see finish()).
  const bobHeartbeat = setInterval(() => {
    if (bob.readyState === WebSocket.OPEN) bob.send(JSON.stringify({ type: 'heartbeat' }));
  }, 20000);

  await sleep(1000);
  console.log('Alice going silent (socket stays open, no more messages, no close)...');
  // Deliberately NOT calling alice.close() and NOT sending 'leaving' -
  // that's the clean-disconnect path the existing suite already covers.

  // HEARTBEAT_STALE_AFTER_MS (65s) is only checked at HEARTBEAT_INTERVAL_MS
  // (30s) ticks, so worst-case detection is ~95s, not 65s - give it real
  // margin past that instead of cutting it close to the boundary.
  const deadline = Date.now() + 130000;
  while (Date.now() < deadline && !bobPromoted) {
    await sleep(2000);
    process.stdout.write('.');
  }
  console.log();
  clearInterval(bobHeartbeat);

  if (bobPromoted) {
    console.log('PASS: heartbeat sweep detected the silent connection and migrated Bob, who stayed alive throughout.');
    process.exit(0);
  } else {
    console.log('FAIL: no migrate received within 130s of Alice going silent.');
    process.exit(1);
  }
}

main().catch((err) => {
  console.error('FAIL:', err);
  process.exit(1);
});
