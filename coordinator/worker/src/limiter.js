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
