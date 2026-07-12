# Deploying the Campfire coordinator

Every Campfire group needs one coordinator that all players can reach.

**A fresh install doesn't need any of this to just work.** `CampfireClient.DEFAULT_COORDINATOR_HOST`
ships pointing at the shared community coordinator, which runs on Cloudflare Workers + Durable
Objects (see `coordinator/worker/README.md`) - free with no credit card, no sleep/wake delay, and it
scales per-group automatically since every group gets its own isolated Durable Object. A player who's
never touched this file can install the mod and immediately create/join a group with zero setup.

Everything in this file is for anyone who wants their OWN coordinator instead - self-hosting for
privacy/control, or running a second one to take load off the shared default. This one (in
`coordinator/coordinator/`) is the plain Node.js version: a single process that never runs Minecraft,
so nearly any machine works - a $3–5/month VPS, a Raspberry Pi, or (with caveats below) someone's
home PC. It needs Node 18+, `npm install`, and one open TCP port (default `8080`).

Whoever runs their own gives everyone in that group its address (e.g. `203.0.113.7:8080` or
`campfire.example.com:8080`). The person who creates the group types that address once in the
"Light a New Campfire" screen - after that, the invite code they share
(`ABCD123XYZ@203.0.113.7:8080`) carries the address to everyone automatically.

**The whole point of this design is that a coordinator's address never changes.** Nobody's game
client needs to "discover" a new coordinator when the host changes - only who's hosting changes,
never who's the coordinator. So the right way to think about the options below isn't "which one do I
pick per-session" - it's "one person in the group sets this up ONE time, on something that stays up,
and everyone forgets about it."

## Option 1 - a small VPS (any provider, manual)

Any cheap cloud VM (Hetzner, Oracle Free Tier, DigitalOcean, etc., ~$4-6/month) works and is the
option with the fewest failure modes, since VPSes have real public IPs and don't sleep or wipe disk
between requests the way card-free serverless free tiers do.

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
- Prefer Docker or a managed unit over a bare foreground process: `sudo docker compose up -d`
  (`restart: unless-stopped` in `docker-compose.yml` survives a crash; Docker's own systemd
  integration, on by default on most distros, survives a reboot too), or copy the ready-made
  `deploy/coordinator.service` systemd unit (`sudo cp deploy/coordinator.service
  /etc/systemd/system/ && sudo systemctl enable --now coordinator` - adjust `WorkingDirectory` and
  create the `campfire` user first).

**What about the saves?** They live in `saves/` next to `server.js` (or in the mounted `./saves`
volume with Docker). That directory is the only copy of your group's world when nobody's playing -
back it up like you would any world folder. Because a VPS or systemd-managed process doesn't sleep
or wipe its disk, there's no need for the GitHub-Releases backup trick this doc used to describe for
card-free serverless free tiers - that persistence problem doesn't exist here.

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
- If the coordinator sits behind a reverse proxy or tunnel (nginx, Caddy, Cloudflare Tunnel,
  ngrok, etc. - common for TLS or NAT reasons), set `TRUST_PROXY=1` in its environment. Without
  it, every request looks like it comes from the proxy's own address, which collapses the
  per-IP rate limits (group creation, upload throttling) into one shared bucket for every real
  visitor. Only set this if a proxy actually sits in front - otherwise a client could set its own
  `X-Forwarded-For` header to dodge its rate limit for free.
- Health check for uptime monitors: `GET /health`.
