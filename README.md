# Campfire

**Share one Minecraft world with your whole friend group - no server rental, no port forwarding, no one stuck being "the host."**

Campfire is a Fabric client mod for Minecraft 1.20.1. Your group's world lives with whoever is
playing: when the current host quits, the save automatically hands off to whoever's next, and
everyone else reconnects to them without touching a thing.

## How it works

- One small always-on **coordinator** server (a "phone book" - it never runs Minecraft) tracks
  whose turn it is to host and ferries the world save between players.
- When the host quits mid-session, their save uploads automatically, the next player in line
  downloads it and becomes the new host, and friends reconnect to them - all hands-free.
- Any other time, hosting is one click: **Open the World** on the Campfire screen always fetches
  your group's newest save first (and skips the download entirely when your copy is already the
  latest). It doesn't matter who hosted last or how long ago - nothing to plan in the group chat.
- Friends connect the fastest way their networks allow, automatically: **direct** via UPnP port
  mapping, **direct** via TCP hole-punching if UPnP isn't available, or **relayed** through the
  coordinator as a last resort.

## Quick start

1. Install [Fabric Loader](https://fabricmc.net/use/) and
   [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 1.20.1, then drop the Campfire
   jar into your `mods` folder.
2. On the title screen, click the **Campfire** flame button (next to the language button).
3. **One person** clicks *Light a New Campfire*, enters the coordinator's address (see below), and
   gets an invite like `HYHHAU4STA@example.com:8080` - copy it and send it to your friends.
4. **Everyone else** clicks *Join a Campfire* and pastes the invite. That's it - the invite carries
   everything needed, no config editing.
5. The creator clicks **Create the Shared World** on the Campfire screen, names the world whatever
   they like (the name travels with the save to every future host), and it builds and opens in one
   click. When someone's hosting, everyone else's Campfire screen offers **Join _name_'s World**.

Launching Minecraft never opens anything by itself - the game always starts fresh at the normal
title screen, and Campfire waits until you come to it. The Campfire screen (the flame button, or
press **B** in-game) is your group's home: it shows whether you're connected, who's around the
fire right now and who's away ("seen 2h ago"), who's hosting, and always offers the one action
that makes sense next. In-game, a small badge appears in the corner for a few seconds whenever
something changes - who you're hosting for, whose camp you're at, direct connection or relay -
then fades out of your way. You'll also get a quiet popup (with a little campfire crackle) when a
friend joins or leaves, when someone opens the world, when hosting passes to someone, and when
you're next in line to host.

Your world is treated as irreplaceable, because it is: every handoff keeps your previous local
copy as a backup, save uploads retry through flaky connections, and a bad download can never
damage the copy you already have.

## The coordinator

Your group needs one reachable coordinator - a tiny Node.js server any one of you (or a cheap VPS)
can run. It uses almost no resources since it never simulates the world; it just does bookkeeping
and relaying. See [coordinator/coordinator/DEPLOY.md](coordinator/coordinator/DEPLOY.md) for the
three ways to host it, ranked easiest first.

Anyone who knows your invite can join your world and download its save - treat invites like a
house key and only share them with your actual friends.

## License

CC0-1.0 - do whatever you like with it.
