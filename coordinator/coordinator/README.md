# Coordinator (Part 1 of 2)

This is the "phone book" for the host-migration mod. It does NOT run Minecraft
or touch the world simulation at all. It only:

1. Knows who's currently online in your friend group.
2. Knows the order they joined in (that's the migration queue).
3. Tells everyone's game client when the host has changed and who the new host is.
4. Holds the latest zipped world save so the next host can grab it before opening the world.

This part is done and tested. It is NOT yet connected to Minecraft - that's
Part 2, the Fabric mod, which is next.

## Running it

```
npm install
node server.js
```

You should see `coordinator listening on :8080`.

Check it's alive: open `http://localhost:8080/health` in a browser, or run
`curl http://localhost:8080/health` - it should say `{"ok":true,"groups":0}`.

## Verifying the migration logic yourself

With the server running, in a second terminal:

```
node test_migration.js
```

You'll see Alice connect and become host, Bob join, then Alice disconnect -
and Bob should immediately get a `migrate` message naming him the new host.
That's the whole trick, already working.

## Running it so friends can actually reach it

Right now this only listens on your own machine (`localhost`). For your
group to use it for real, it needs to run somewhere all your machines can
reach - options, cheapest first:

- **Free tier hosting** (Render, Fly.io, Railway) - upload this folder,
  they give you a public URL. This is the easiest path and stays free at
  your group's scale.
- **A spare device at home** (old laptop, Raspberry Pi) left running,
  with the port forwarded once - free forever, but only up if that device is up.

Either way: this process is tiny (a phonebook + a file drop), not a game
server, so it costs nothing to keep running 24/7.

## What's next

Part 2 is the actual Fabric mod that:
- talks to this coordinator over WebSocket,
- zips/uploads the world save when the host leaves,
- downloads the latest save and opens it when you become host,
- calls World Host (a separate, existing mod) to actually let friends connect.

I can't compile or test Java/Minecraft mod code in my own environment, so
that part will need you to test it in-game and tell me what happens -
that's the next step.
