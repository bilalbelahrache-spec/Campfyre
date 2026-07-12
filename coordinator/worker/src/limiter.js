// Per-IP rate limiter for POST /groups/new - the one unauthenticated
// endpoint a bored script could hammer. One instance per caller IP; each
// keeps a sliding window of mint timestamps in its own storage and cleans
// itself up (deleteAll) once the window empties, so idle IPs cost nothing.
// Same policy as the Node coordinator: codes cost nothing until someone
// says hello, so the only real risk is noise - a generous cap kills that
// without ever getting in the way of a legitimate friend group (who mint
// one code, ever).

const MINT_LIMIT_PER_HOUR = 20;
const MINT_WINDOW_MS = 60 * 60 * 1000;

export class MintLimiter {
  constructor(ctx) {
    this.ctx = ctx;
  }

  async fetch() {
    const now = Date.now();
    const times = ((await this.ctx.storage.get('times')) || []).filter((t) => now - t < MINT_WINDOW_MS);
    if (times.length >= MINT_LIMIT_PER_HOUR) {
      return new Response('too many new groups from this address - try again later', { status: 429 });
    }
    times.push(now);
    await this.ctx.storage.put('times', times);
    // Self-destruct once the whole window has aged out.
    await this.ctx.storage.setAlarm(now + MINT_WINDOW_MS + 60000);
    return Response.json({ ok: true });
  }

  async alarm() {
    const now = Date.now();
    const times = ((await this.ctx.storage.get('times')) || []).filter((t) => now - t < MINT_WINDOW_MS);
    if (times.length === 0) {
      await this.ctx.storage.deleteAll();
    } else {
      await this.ctx.storage.put('times', times);
      await this.ctx.storage.setAlarm(now + MINT_WINDOW_MS + 60000);
    }
  }
}

// General per-IP sliding-window limiter for everything else that was
// completely unthrottled: GET /exists, /status, /save (download), and WS
// 'hello' against an already-existing group (see group.js's checkGeneralRate
// -Limit). Those all skip checkGroupCreationAllowed entirely once a group
// is real, so a stuck reconnect loop or a deliberately hostile script could
// otherwise hammer them without limit - and because this whole coordinator
// runs on one shared free-tier account, burning through its daily request
// budget this way takes the service down for every unrelated public group,
// not just the caller's own.
//
// One instance per (ip, bucket) pair - callers key the DO name as
// `${ip}:${bucket}` so different endpoint classes (cheap reads vs. large
// downloads) get independent budgets instead of sharing one counter. limit/
// windowMs travel as query params so this one class serves every bucket
// without a wrangler.toml binding per policy.
export class IpRateLimiter {
  constructor(ctx) {
    this.ctx = ctx;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const limit = Number(url.searchParams.get('limit')) || 60;
    const windowMs = Number(url.searchParams.get('windowMs')) || 60000;
    const now = Date.now();
    const times = ((await this.ctx.storage.get('times')) || []).filter((t) => now - t < windowMs);
    if (times.length >= limit) {
      return new Response('rate limited', { status: 429 });
    }
    times.push(now);
    await this.ctx.storage.put('times', times);
    await this.ctx.storage.setAlarm(now + windowMs + 60000);
    return Response.json({ ok: true });
  }

  async alarm() {
    // No per-bucket window recorded here (limit/windowMs only ever arrive on
    // fetch()) - just drop everything once nothing has hit this IP+bucket
    // recently. A safe, generous default: if it's been quiet a full minute
    // past whatever the last request's window was, it's idle either way.
    await this.ctx.storage.deleteAll();
  }
}
