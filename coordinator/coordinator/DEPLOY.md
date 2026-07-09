# Deploying the Campfire coordinator

Every Campfire group needs one coordinator that all players can reach. It's a single small
Node.js process - it never runs Minecraft, so nearly any machine works: a $3–5/month VPS, a
Raspberry Pi, or (with caveats below) someone's home PC. It needs Node 18+, `npm install`, and
one open TCP port (default `8080`).

**A fresh install doesn't need any of this to just work.** `CampfireClient.DEFAULT_COORDINATOR_HOST`
ships pointing at a community coordinator (`https://campfire-coordinator.onrender.com`) so a player
who's never touched this file can install the mod and immediately create/join a group with zero
setup. Everything below is for anyone who wants their OWN coordinator instead - self-hosting for
privacy/control, or running a second one to take load off the shared default.

Whoever runs their own gives everyone in that group its address (e.g. `203.0.113.7:8080` or
`campfire.example.com:8080`). The person who creates the group types that address once in the
"Light a New Campfire" screen - after that, the invite code they share
(`ABCD123XYZ@203.0.113.7:8080`) carries the address to everyone automatically.

## Before this default coordinator is ready for real public-scale traffic

Tonight's Render + GitHub-Releases setup was built and sized for a single friend group, and two
things about it specifically do NOT hold up once this is actually the default for anyone who
downloads the mod, not just one group:

- **Save storage**: backing up save zips to GitHub Releases in this repo works fine for one
  group's saves, but is the wrong place for potentially thousands of unrelated strangers' Minecraft
  worlds - it mixes other people's private game data into the maintainer's own personal code repo,
  and GitHub Releases isn't built to be a general-purpose multi-tenant blob store at that volume.
  Real public use needs actual object storage (S3-compatible - R2, Backblaze B2, etc.), most likely
  on a paid tier.
- **Compute**: the Render Free instance (512MB RAM, 0.1 CPU, sleeps after 15 min idle) is sized for
  one group's occasional traffic, not concurrent groups at real scale. Growing usage will need a
  paid Render plan (or equivalent) with more headroom, and likely horizontal scaling eventually -
  which also means revisiting the in-memory `groups` Map, since that only works as one process.
- **Abuse/moderation**: `POST /groups/new` has a basic per-IP rate limit, but there's no real abuse
  handling (e.g. someone scripting mass group creation from many IPs, or uploading non-Minecraft
  data as a "save") sized for public internet traffic rather than a friend group.

None of this blocks using the mod today - it just means "default coordinator" should be treated as
an early/best-effort service until it's rebuilt on real infrastructure, not something to advertise
as production-grade yet.

**The whole point of this design is that the coordinator's address never changes.** Nobody's game
client needs to "discover" a new coordinator when the host changes - only who's hosting changes,
never who's the coordinator. So the right way to think about this section isn't "which option do I
pick per-session" - it's "one person in the group sets this up ONE time, on a machine that stays
on, and everyone forgets about it." The recommended path below is free forever and survives
reboots/crashes on its own.

## Recommended: free, no credit card, no home PC required

Two things had to both be true and turned out to be in tension: genuinely free-forever compute
without a card on file (Oracle/Fly/GCP/AWS/Azure all require one now, specifically to stop free-tier
abuse), *and* a save file that survives indefinitely (the no-card-required free hosts achieve that by
sleeping after ~15 min of inactivity and wiping local disk on every sleep/wake cycle). The fix is to
stop asking one host to do both jobs:

- **[Render](https://render.com)'s free web service tier** for the always-on bookkeeping (who's
  online, whose turn to host). No card needed to deploy. It sleeps after 15 min idle and wipes local
  disk on wake - but the coordinator's in-memory group/queue state is *already* designed to be
  disposable on restart; reconnecting clients rebuild it automatically. The only thing that can never
  be allowed to just vanish is the save file itself, so:
- **GitHub Releases in this same repo** (which you already have, so this needs no new account or
  card) back up the save .zip after every upload, and restore it automatically the moment the
  coordinator wakes up - before it answers any request. See `githubBackup.js` for how; it's a
  no-op unless the two env vars below are set, so it can't affect local dev.

### Setup (~10 minutes, no card anywhere)

1. **Create a GitHub token**: on github.com, Settings → Developer settings → Personal access tokens
   → Fine-grained tokens → Generate new token. Scope it to just this repository, with **Contents:
   Read and write** permission (that's what covers Releases). Copy the token - you won't see it
   again.
2. **Deploy to Render**: on render.com, New → Web Service → connect this GitHub repo, root directory
   `coordinator/coordinator`. Render auto-detects Node; set:
   - Build command: `npm install`
   - Start command: `node server.js`
   - Plan: **Free**
3. **Add environment variables** on the Render service (Environment tab):
   - `GITHUB_TOKEN` = the token from step 1
   - `GITHUB_REPO` = `bilalbelahrache-spec/host-migration`
4. Deploy. Render gives you a URL like `https://coordinator-campfire.onrender.com` - that IS the
   coordinator address, already on `https://` (port 443, no separate port to forward or remember).
5. Verify from a *different* network: `https://<your-app>.onrender.com/health` should show
   `{"ok":true,...}`.
6. In-game, type the **full address including `https://`** into the "Light a New Campfire"
   coordinator field (the mod already understands `https://`/`wss://` - see `CampfireClient.java`).
   The invite code carries it to everyone else automatically.

### What to actually expect

- **The first connection after a quiet spell takes ~30-60s** while Render wakes the service back up
  and it restores the last save from GitHub - the mod's existing auto-reconnect (retries every 5s)
  handles this without you doing anything; it just looks like a slightly slow first connect, not a
  failure.
- **Known gap, low stakes**: the existing 180-day-inactive → trash → 90-day purge archival lifecycle
  still only operates on Render's local disk, so an *archived* save wouldn't survive
  a sleep/wake cycle the same way a live one does. This only matters if a group goes silent for
  6+ months, at which point re-running an upload/download restores it from GitHub again anyway (the
  live-save path above always wins first). Not worth the extra complexity to close for a friend group
  that's actively playing.

### Alternative: a VPS or your own always-on machine

If you'd rather not deal with any of the above - a cheap always-on VPS (DigitalOcean/Hetzner/Linode,
~$4-6/month) or a spare machine that's already on 24/7 sidesteps the whole sleep/persistence problem,
using the plain Docker/systemd setup below.

```bash
# on the VPS
git clone <this repo> && cd coordinator/coordinator
sudo docker compose up -d
```
`restart: unless-stopped` in `docker-compose.yml` means it survives a crash; enabling Docker's own
systemd service (on by default on most distros) means it survives a reboot too. Saves live in
`./saves` next to `docker-compose.yml`, mounted into the container so they persist across rebuilds.

Don't want Docker? `deploy/coordinator.service` is a ready-to-copy systemd unit
(`sudo cp deploy/coordinator.service /etc/systemd/system/ && sudo systemctl enable --now
coordinator`) - adjust `WorkingDirectory` and create the `campfire` user (or swap in your own) first.

## Option 1 - a small VPS (any provider, manual)

Any cheap cloud VM (Hetzner, Oracle Free Tier, DigitalOcean, etc.) works and is the only option
with zero home-network headaches, since VPSes have real public IPs.

```bash
# on the VPS
git clone <this repo> && cd coordinator/coordinator
npm install
PORT=8080 node server.js
```

- Open TCP 8080 in the provider's firewall/security-group settings.
- Keep it running across reboots with something like `systemd` or `pm2`
  (`npm i -g pm2 && pm2 start server.js && pm2 save && pm2 startup`).
- Verify from any other network: `http://<vps-ip>:8080/health` in a browser should show
  `{"ok":true,...}`.

**What about the saves?** They live in `saves/` next to `server.js`. That directory is the only
copy of your group's world when nobody's playing - back it up like you would any world folder.

## Option 2 - a tunnel service from a home PC (no router setup)

If the coordinator runs on a home PC but port forwarding is impossible (see CGNAT below) or just
unwanted, a TCP tunnel service such as [playit.gg](https://playit.gg) gives you a public address
that forwards to `localhost:8080`. Run the coordinator normally, create a TCP tunnel to local
port 8080, and share the tunnel's address as the coordinator address. The tunnel agent must stay
running whenever anyone wants to play.

## Option 3 - a home PC with a port forward

Works, but has the most failure modes:

1. Run `node server.js` on the PC.
2. In the router's admin page, forward external TCP 8080 → that PC's LAN IP, port 8080.
3. Give friends your **public** IP (what [api.ipify.org](https://api.ipify.org) shows, not your
   `192.168.x.x` LAN address).

Gotchas, in the order they actually bite people:

- **CGNAT**: if your ISP puts you behind carrier-grade NAT, no port forward you configure can
  ever work - the ISP's own NAT sits in front of your router and only they control it. Telltale
  sign: the WAN/Internet IP shown *inside your router's admin page* differs from what
  api.ipify.org reports. If they differ, skip straight to Option 2.
- **Testing from inside your own network lies.** Your own browser hitting your own public IP
  often fails (or succeeds) for reasons that don't apply to the outside world (hairpin NAT). The
  only trustworthy test is someone on a *different* network opening
  `http://<your-public-ip>:8080/health`.
- **Dynamic IPs rotate.** Home public IPs change every so often; when yours does, the address in
  everyone's invite goes stale. A free dynamic-DNS name (DuckDNS etc.) fixes this permanently.
- The PC must be awake and the coordinator running whenever anyone wants to play or a host
  handoff needs to happen.

## Notes for any deployment

- State (who's in which group, whose turn to host) is in-memory only - restarting the process
  loses none of the *saves*, and reconnecting clients rebuild the rest automatically.
- There is no admin auth on the HTTP endpoints; a group's unguessable id is its access control.
  Don't put a reverse proxy path-rewrite in front of it that could leak or normalize ids.
- Health check for uptime monitors: `GET /health`.
