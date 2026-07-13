# Campfyre coordinator (Cloudflare Workers)

The coordinator, rebuilt to run on Cloudflare's free plan: no server process to keep alive, no
sleep/wake delay, no credit card, and it scales per-group automatically (each group lives in its own
Durable Object). It speaks the same protocol as the Node coordinator in `../coordinator/`, which
remains useful for local development and as the self-hosting option for anyone who prefers a plain
Node process.

## Develop / test locally (no Cloudflare account needed)

```bash
npm install
npm run dev          # local coordinator on http://127.0.0.1:8787
npm test             # in a second terminal: full protocol exercise, expects PASS
```

`npm run dev` runs entirely on this machine (wrangler's local runtime), including Durable Objects
and alarms - nothing touches the internet.

`npm test` only covers the fast path (a few seconds). The dead-connection sweep needs its own script
since it deliberately waits out a real ~2-minute staleness window - run it by hand after touching
`sweepDeadConnections`/`HEARTBEAT_*` in `group.js`: `node test/test_heartbeat_sweep_manual.js` (with
`npm run dev` already running), expects PASS.

## Deploy

```bash
npx wrangler deploy
```

First time, wrangler opens a browser to log in to (or create) a free Cloudflare account. The deploy
prints the public URL (`https://campfyre-coordinator.<account>.workers.dev`) - that's the
coordinator address.

## Differences from the Node coordinator

- The WebSocket URL carries the group id: `wss://.../ws?group=<groupId>`. (The mod sends it always;
  the Node coordinator ignores it.)
- Saves are stored as 2MB rows in each group's Durable Object storage, uploaded either as the
  original one-shot multipart POST (small saves - Cloudflare caps any single request body at
  ~100MB) or via the chunked path (`/save/begin`, `/save/part/:n`, `/save/commit`) the mod switches
  to for big saves.
- `GET /reflect` can't see the caller's source port through Cloudflare's proxy, so it answers
  `port: null`. The hole-punch tier already had this limitation behind any proxied host; direct
  connect via UPnP and the relay tier are unaffected.
- Dead-connection detection is app-level, not edge-level: raw WS ping/pong frames never reach this
  object's code (Cloudflare's hibernation model only surfaces JSON messages), so the mod sends an
  explicit no-op `heartbeat` message on the same ~20s schedule as its keepalive ping, and a Durable
  Object alarm sweeps any connection silent for 65+ seconds - same idea as the Node coordinator's own
  sweep, just triggered by a storage alarm instead of an in-memory `setInterval` (which can't survive
  hibernation). See `test/test_heartbeat_sweep_manual.js`.
- Free-plan ceilings to know about: ~100,000 requests/day (relayed game traffic is by far the
  hungriest consumer - direct connections cost nothing), 5GB total save storage, 1GB per group.
