// Front door for the Campfire coordinator on Cloudflare Workers. This layer
// is stateless: it validates ids, answers the couple of endpoints that
// aren't about any particular group, and forwards everything else -
// including the WebSocket upgrade - to the group's own Durable Object
// (src/group.js), which holds all real state and logic.
//
// Route map (same public surface as the Node coordinator's server.js):
//   GET  /health                     liveness + version
//   GET  /reflect                    caller's address as seen from outside their NAT
//   POST /groups/new                 mint a fresh unguessable group id (rate limited per IP)
//   GET  /groups/:id/exists          pre-join check ("is this code real?")
//   GET  /groups/:id/status          read-only presence for the campfire list screen
//   GET  /groups/:id/save            download the current save zip
//   POST /groups/:id/save            legacy one-shot upload (multipart; small saves only -
//                                    Cloudflare rejects request bodies over ~100MB)
//   POST /groups/:id/save/begin      chunked upload: open
//   PUT  /groups/:id/save/part/:n    chunked upload: sequential raw-bytes parts
//   POST /groups/:id/save/commit     chunked upload: finalize (atomic pointer flip)
//   GET  /ws?group=:id  (upgrade)    the group's signaling/relay WebSocket
//
// The one protocol change from the Node version: the WebSocket URL must
// carry the group id (`/ws?group=...`). The Node server learned it from the
// first 'hello' message, but here the upgrade has to be routed to the right
// Durable Object before any message can flow. The 'hello' still carries
// groupId too, and the Node server ignores unknown query params - so a mod
// that always appends ?group= works against either coordinator.

import { CampfireGroup, isValidGroupId } from './group.js';
import { MintLimiter } from './limiter.js';

export { CampfireGroup, MintLimiter };

const VERSION = '0.1.0';

// Invite-code alphabet: no 0/O/1/I/L, easy to misread aloud. 10 chars is
// ~50 bits - a group's id IS its access control, so it must be unguessable.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
function generateGroupCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(10));
  let code = '';
  for (let i = 0; i < 10; i++) code += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  return code;
}

function groupStub(env, groupId) {
  return env.GROUPS.get(env.GROUPS.idFromName(groupId));
}

// Forward a request to a group's Durable Object with the /groups/:id prefix
// stripped and the id preserved as ?group= (a Durable Object can't read its
// own name, and the group id is also the WebSocket routing key).
function forwardToGroup(env, groupId, request, innerPath, init) {
  const url = new URL(request.url);
  url.pathname = innerPath;
  url.searchParams.set('group', groupId);
  return groupStub(env, groupId).fetch(new Request(url, init || request));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === '/health') {
      return Response.json({ ok: true, version: VERSION, platform: 'cloudflare' });
    }

    // A client calls this from the local port it's about to hole-punch
    // with; the answer is that port's external NAT mapping. Behind
    // Cloudflare's proxy the caller's source PORT isn't visible (only the
    // IP), so this can only answer half the question - same limitation the
    // Render deployment already had, since Render fronts everything with a
    // proxy too. Kept answering honestly (port: null) rather than lying;
    // direct-connect still works via the UPnP tier, and the relay tier
    // needs no address at all.
    if (path === '/reflect') {
      return Response.json({
        ip: request.headers.get('CF-Connecting-IP') || '',
        port: null,
      });
    }

    if (path === '/groups/new' && request.method === 'POST') {
      const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
      const limited = await env.LIMITER.get(env.LIMITER.idFromName(ip)).fetch(request.url);
      if (limited.status === 429) return limited;

      // Collision odds at ~50 bits are astronomically small, but handing an
      // existing group's id to a stranger would be catastrophic (the id IS
      // the group's access control) - so spend one lookup making sure.
      for (let attempt = 0; attempt < 5; attempt++) {
        const code = generateGroupCode();
        const res = await forwardToGroup(env, code, request, '/exists', { method: 'GET' });
        const { exists } = await res.json();
        if (!exists) return Response.json({ groupId: code });
      }
      return Response.json({ error: 'could not mint a group id' }, { status: 500 });
    }

    // The group WebSocket. The Upgrade request is forwarded to the group's
    // Durable Object, which accepts it with hibernation enabled.
    if (path === '/ws') {
      if (request.headers.get('Upgrade') !== 'websocket') {
        return new Response('expected a websocket upgrade', { status: 400 });
      }
      const groupId = url.searchParams.get('group');
      if (!isValidGroupId(groupId)) {
        return new Response('missing or invalid ?group= (this coordinator needs the group id in the URL)', { status: 400 });
      }
      return groupStub(env, groupId).fetch(request);
    }

    const groupMatch = path.match(/^\/groups\/([^/]+)(\/.*)$/);
    if (groupMatch) {
      const [, groupId, rest] = groupMatch;
      if (!isValidGroupId(groupId)) {
        return Response.json({ error: 'invalid group id' }, { status: 400 });
      }

      // The legacy one-shot upload arrives as a multipart form (that's what
      // the mod sends today). Unwrap it here so the Durable Object only
      // ever deals in raw zip bytes - and poke the group first so migration
      // gating knows an upload is underway even while the body is still
      // arriving.
      if (rest === '/save' && request.method === 'POST') {
        await forwardToGroup(env, groupId, request, '/save/incoming', { method: 'POST' });
        let file;
        try {
          const form = await request.formData();
          file = form.get('save');
        } catch {
          return Response.json({ error: 'expected multipart form data with a "save" file' }, { status: 400 });
        }
        if (!file || typeof file.arrayBuffer !== 'function') {
          return Response.json({ error: 'missing file' }, { status: 400 });
        }
        return forwardToGroup(env, groupId, request, '/save', {
          method: 'POST',
          body: await file.arrayBuffer(),
        });
      }

      const known = rest === '/exists' || rest === '/status' || rest === '/save'
        || rest === '/save/begin' || rest === '/save/commit' || /^\/save\/part\/\d+$/.test(rest);
      if (known) {
        return forwardToGroup(env, groupId, request, rest);
      }
    }

    return Response.json({ error: 'not found' }, { status: 404 });
  },
};
