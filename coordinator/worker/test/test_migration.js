// End-to-end exercise of the Workers coordinator, run against a local
// `npm run dev` (wrangler dev on :8080). Same core scenario as the Node
// coordinator's test_migration.js - Alice hosts, Bob joins, Alice leaves,
// Bob must receive a 'migrate' naming him - plus the HTTP surface: minting,
// the exists/status checks, and both save upload paths round-tripped.
//
// The only intentional protocol difference from the Node coordinator shows
// up here: the WebSocket URL carries ?group= (the Node server ignores it).

const WebSocket = require('ws');

// Default matches `npm run dev` (wrangler dev --port 8787); override with
// COORDINATOR_URL to point anywhere else. Not 8080 - the Node coordinator
// often occupies that locally.
const BASE = process.env.COORDINATOR_URL || 'http://127.0.0.1:8787';
const WS_BASE = BASE.replace(/^http/, 'ws');
let failures = 0;

function check(name, cond, detail = '') {
  if (cond) console.log(`  ok: ${name}`);
  else {
    console.log(`  FAIL: ${name} ${detail}`);
    failures += 1;
  }
}

function connect(groupId, playerId, playerName, onMessage) {
  const ws = new WebSocket(`${WS_BASE}/ws?group=${groupId}`);
  const received = [];
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    received.push(msg);
    console.log(`  [${playerName}] received: ${msg.type}${msg.type === 'state' ? ` (host=${msg.hostId}, members=${msg.members.length})` : ''}`);
    if (onMessage) onMessage(msg);
  });
  return new Promise((resolve) => {
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'hello', groupId, playerId, playerName }));
      resolve({ ws, received });
    });
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// A tiny but real zip (empty central directory) so the "PK" check passes.
const FAKE_ZIP = Buffer.concat([
  Buffer.from('PK\x05\x06', 'binary'),
  Buffer.alloc(18),
]);

(async () => {
  console.log('--- health ---');
  const health = await (await fetch(`${BASE}/health`)).json();
  check('health ok', health.ok === true, JSON.stringify(health));

  console.log('--- mint a group ---');
  const { groupId } = await (await fetch(`${BASE}/groups/new`, { method: 'POST' })).json();
  check('minted a 10-char code', typeof groupId === 'string' && groupId.length === 10, groupId);

  console.log('--- exists is false before anyone says hello ---');
  const before = await (await fetch(`${BASE}/groups/${groupId}/exists`)).json();
  check('exists=false pre-hello', before.exists === false, JSON.stringify(before));

  console.log('--- Alice connects and claims host ---');
  const alice = await connect(groupId, 'alice', 'Alice');
  await sleep(400);
  alice.ws.send(JSON.stringify({ type: 'im_hosting' }));
  await sleep(400);
  const aliceState = alice.received.filter((m) => m.type === 'state').pop();
  check('state shows alice hosting', aliceState && aliceState.hostId === 'alice', JSON.stringify(aliceState));

  const after = await (await fetch(`${BASE}/groups/${groupId}/exists`)).json();
  check('exists=true post-hello', after.exists === true, JSON.stringify(after));

  console.log('--- Bob connects ---');
  let bobMigrate;
  const bobMigratePromise = new Promise((r) => { bobMigrate = r; });
  const bob = await connect(groupId, 'bob', 'Bob', (msg) => {
    if (msg.type === 'migrate') bobMigrate(msg);
  });
  await sleep(400);
  const bobState = bob.received.filter((m) => m.type === 'state').pop();
  check('bob sees 2 members', bobState && bobState.members.length === 2, JSON.stringify(bobState));
  check('ownerId is alice (first hello), not bob', bobState && bobState.ownerId === 'alice', JSON.stringify(bobState));
  check('ownerName is Alice', bobState && bobState.ownerName === 'Alice', JSON.stringify(bobState));

  console.log('--- status endpoint ---');
  const status = await (await fetch(`${BASE}/groups/${groupId}/status`)).json();
  check('status shows both + host', status.online === 2 && status.hostName === 'Alice', JSON.stringify(status));

  console.log('--- legacy one-shot save upload ---');
  const form = new FormData();
  form.append('save', new Blob([FAKE_ZIP], { type: 'application/zip' }), 'save.zip');
  const up1 = await (await fetch(`${BASE}/groups/${groupId}/save`, { method: 'POST', body: form })).json();
  check('legacy upload -> v1', up1.ok === true && up1.saveVersion === 1, JSON.stringify(up1));
  await sleep(300);
  check('save_ready broadcast', bob.received.some((m) => m.type === 'save_ready' && m.saveVersion === 1));

  const down1 = Buffer.from(await (await fetch(`${BASE}/groups/${groupId}/save`)).arrayBuffer());
  check('download round-trips', down1.equals(FAKE_ZIP), `${down1.length} bytes`);

  console.log('--- garbage upload is rejected ---');
  const badForm = new FormData();
  badForm.append('save', new Blob([Buffer.from('this is not a zip')]), 'save.zip');
  const bad = await fetch(`${BASE}/groups/${groupId}/save`, { method: 'POST', body: badForm });
  check('non-zip rejected with 400', bad.status === 400);

  console.log('--- chunked save upload ---');
  const { uploadId } = await (await fetch(`${BASE}/groups/${groupId}/save/begin`, { method: 'POST' })).json();
  check('begin -> uploadId', typeof uploadId === 'string');
  const half = Math.ceil(FAKE_ZIP.length / 2);
  for (const [i, part] of [FAKE_ZIP.subarray(0, half), FAKE_ZIP.subarray(half)].entries()) {
    const r = await (await fetch(`${BASE}/groups/${groupId}/save/part/${i}?uploadId=${uploadId}`, {
      method: 'PUT',
      body: part,
    })).json();
    check(`part ${i} accepted`, r.ok === true, JSON.stringify(r));
  }
  const commit = await (await fetch(`${BASE}/groups/${groupId}/save/commit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ uploadId, parts: 2 }),
  })).json();
  check('commit -> v2', commit.ok === true && commit.saveVersion === 2, JSON.stringify(commit));
  const down2 = Buffer.from(await (await fetch(`${BASE}/groups/${groupId}/save`)).arrayBuffer());
  check('chunked download round-trips', down2.equals(FAKE_ZIP), `${down2.length} bytes`);

  console.log('--- Alice disconnects; Bob should get migrate within ~15s (upload-wait timeout) ---');
  alice.ws.close();
  const migrate = await Promise.race([
    bobMigratePromise,
    sleep(25000).then(() => null),
  ]);
  check('bob promoted via migrate', migrate && migrate.newHostId === 'bob', JSON.stringify(migrate));
  check('migrate carries previous host', migrate && migrate.previousHostId === 'alice', JSON.stringify(migrate));
  check('migrate carries save version', migrate && migrate.saveVersion === 2, JSON.stringify(migrate));

  bob.ws.close();
  await sleep(200);

  console.log(failures === 0 ? '\nPASS: all checks passed.' : `\nFAIL: ${failures} check(s) failed.`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});
