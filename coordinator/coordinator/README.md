# Campfyre coordinator

The "phone book" half of Campfyre. It does NOT run Minecraft or touch world
simulation at all. It only:

1. Knows who's currently online in each friend group.
2. Knows the order they joined in (that's the migration queue).
3. Tells everyone's game client when the host changed and who the new host is.
4. Holds the latest zipped world save so the next host can grab it before opening the world.
5. Helps friends connect to the host: reflects public addresses for UPnP/hole-punch
   direct connections, and relays game traffic itself when direct isn't possible.

The other half is the Fabric client mod in this repo's `src/` - the two talk
only via the WebSocket/HTTP protocol in `server.js`.

## Running it

```
npm install
node server.js
```

You should see `coordinator listening on :8080` (override with the `PORT` env var).

Check it's alive: open `http://localhost:8080/health` in a browser, or run
`curl http://localhost:8080/health` - it should say something like
`{"ok":true,"groups":0,"uptimeSeconds":2,"version":"1.0.0"}`.

## Verifying the migration logic yourself

With the server running, in a second terminal:

```
node test_migration.js
```

You'll see Alice connect and become host, Bob join, then Alice disconnect -
and Bob should get a `migrate` message naming him the new host. Run this
after touching any coordinator logic instead of hand-testing with real game
clients.

The save file itself transfers over the same WebSocket connection (chunked
upload/download, falling back to plain HTTP for any coordinator/client that
doesn't speak it yet) rather than separate HTTP requests - run
`node test_ws_save_transfer.js` after touching that specific path. It
uploads a fake multi-part zip, downloads it back on a second connection, and
checks the bytes match exactly.

## Running it so friends can actually reach it

See [DEPLOY.md](DEPLOY.md) - VPS (recommended), tunnel service, or home PC
with a port forward, including the CGNAT/hairpin-NAT gotchas that make the
home-PC option the trickiest.

## Where the worlds live

`saves/<groupId>.zip` next to `server.js` is the only copy of a group's world
when nobody's playing. Stale groups are archived to `saves/_trash/` after 180
days of inactivity and only permanently purged 90 days after that - but treat
the `saves/` directory as data worth backing up regardless.
