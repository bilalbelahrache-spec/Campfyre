const WebSocket = require('ws');

function connect(groupId, playerId, playerName) {
  const ws = new WebSocket('ws://localhost:8080/ws');
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    console.log(`[${playerName}] received:`, msg);
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

  console.log('\n--- Alice disconnects (should migrate host to Bob) ---');
  alice.close();
  await new Promise((r) => setTimeout(r, 500));

  console.log('\n--- done, closing Bob ---');
  bob.close();
  process.exit(0);
})();
