const WebSocket = require('ws');

// Resolved when Bob receives his 'migrate' - the whole point of the script.
let bobMigrated;
const bobMigratedPromise = new Promise((r) => { bobMigrated = r; });

function connect(groupId, playerId, playerName) {
  const ws = new WebSocket('ws://localhost:8080/ws');
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    console.log(`[${playerName}] received:`, msg);
    if (playerName === 'Bob' && msg.type === 'migrate') bobMigrated(msg);
  });
  return new Promise((resolve) => {
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'hello', groupId, playerId, playerName }));
      resolve(ws);
    });
  });
}

(async () => {
  const groupId = 'test-group';

  console.log('--- Alice connects first ---');
  const alice = await connect(groupId, 'alice', 'Alice');
  await new Promise((r) => setTimeout(r, 300));

  console.log('\n--- Alice confirms she is hosting ---');
  alice.send(JSON.stringify({ type: 'im_hosting' }));
  await new Promise((r) => setTimeout(r, 300));

  console.log('\n--- Bob connects while Alice is hosting ---');
  const bob = await connect(groupId, 'bob', 'Bob');
  await new Promise((r) => setTimeout(r, 300));

  // Promotion is deliberately NOT immediate: the coordinator holds the
  // migration (beginPendingMigration) until Alice's save upload lands or
  // MIGRATE_UPLOAD_TIMEOUT_MS (15s) passes - Alice never uploads here, so
  // Bob's 'migrate' arrives after the full timeout. A short fixed sleep
  // used to sit here and silently stopped observing the migrate at all
  // when that gating was added; wait for the message itself instead.
  console.log('\n--- Alice disconnects (Bob should get migrate within ~15s, after the upload-wait times out) ---');
  alice.close();
  const migrate = await Promise.race([
    bobMigratedPromise,
    new Promise((r) => setTimeout(() => r(null), 20000)),
  ]);

  if (migrate && migrate.newHostId === 'bob') {
    console.log('\nPASS: Bob was promoted to host.');
  } else {
    console.log('\nFAIL: Bob never received a migrate naming him host.');
    process.exitCode = 1;
  }

  console.log('--- done, closing Bob ---');
  bob.close();
  process.exit();
})();
