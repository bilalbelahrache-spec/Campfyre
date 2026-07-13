# Campfyre

**Share one Minecraft world with your whole friend group - no server rental, no port forwarding, no one stuck being "the host."**

Campfyre is a Fabric client mod for Minecraft, supporting every release from 1.20.1 through
1.21.11. Your group's world lives with whoever is playing: when the current host quits, the save
automatically hands off to whoever's next, and everyone else reconnects to them without touching a
thing.

## How it works

- One small always-on **coordinator** server (a "phone book" - it never runs Minecraft) tracks
  whose turn it is to host and ferries the world save between players.
- When the host quits mid-session, their save uploads automatically, the next player in line
  downloads it and becomes the new host, and friends reconnect to them - all hands-free.
- Any other time, hosting is one click: **Open the World** on the Campfyre screen always fetches
  your group's newest save first (and skips the download entirely when your copy is already the
  latest). It doesn't matter who hosted last or how long ago - nothing to plan in the group chat.
- Friends connect the fastest way their networks allow, automatically: **direct** via UPnP port
  mapping, **direct** via TCP hole-punching if UPnP isn't available, or **relayed** through the
  coordinator as a last resort.

## Quick start

Nothing to set up first - every fresh install already points at a free, always-on shared
coordinator, so there's no server to rent or config file to edit before you start.

1. Install [Fabric Loader](https://fabricmc.net/use/) and
   [Fabric API](https://modrinth.com/mod/fabric-api) matching your Minecraft version (anything from
   1.20.1 through 1.21.11 works), then drop the Campfyre jar built for that same version into your
   `mods` folder.
2. On the title screen, click the **Campfyre** flame button (next to the language button).
3. **One person** clicks *Light a New Campfyre* and *Light It* - that's it, no address to type
   unless you want one (see "Running your own coordinator" below). You'll get an invite like
   `HYHHAU4STA@campfyre-coordinator.example.workers.dev` - copy it and send it to your friends.
4. **Everyone else** clicks *Join a Campfyre* and pastes the invite. That's it - the invite carries
   everything needed, no config editing.
5. The creator clicks **Create the Shared World** on the Campfyre screen, names the world whatever
   they like (the name travels with the save to every future host), and it builds and opens in one
   click. When someone's hosting, everyone else's Campfyre screen offers **Join _name_'s World**.

Launching Minecraft never opens anything by itself - the game always starts fresh at the normal
title screen, and Campfyre waits until you come to it. The Campfyre screen (the flame button, or
press **B** in-game) is your group's home: it shows whether you're connected, who's around the
fire right now and who's away ("seen 2h ago"), who's hosting, and always offers the one action
that makes sense next. In-game, a small badge appears in the corner for a few seconds whenever
something changes - who you're hosting for, whose camp you're at, direct connection or relay -
then fades out of your way. You'll also get a quiet popup (with a little campfyre crackle) when a
friend joins or leaves, when someone opens the world, when hosting passes to someone, and when
you're next in line to host.

Your world is treated as irreplaceable, because it is: every handoff keeps your previous local
copy as a backup, save uploads retry through flaky connections, and a bad download can never
damage the copy you already have.

## Running your own coordinator

The coordinator is just a "phone book" - it never runs Minecraft or simulates the world, it only
tracks whose turn it is to host and ferries the save + game traffic between you. You don't need to
run one yourself; every install already uses a free shared coordinator by default. Some groups
prefer to run their own anyway - more control, no reliance on a server you don't own, useful if
your group is especially large or active. If that's you, whoever clicks *Light a New Campfyre* can
type a different coordinator address before creating the group, and that address travels with the
invite to everyone else automatically - nobody else has to configure anything. See
[coordinator/coordinator/DEPLOY.md](coordinator/coordinator/DEPLOY.md) for self-hosting options.

Anyone who knows your invite can join your world and download its save - treat invites like a
house key and only share them with your actual friends.

## Odds and ends

If every mob around you suddenly stops and stares straight at you for about 10 seconds, that's not
a bug - it's a deliberate, extremely rare (roughly 1-in-a-million-per-second-of-play) easter egg.
It only affects rendering on your own screen and clears itself; nothing to do but enjoy it.

## Found a bug? Have a question?

This is my first mod ever, so please go in expecting rough edges - and tell me about anything
that breaks or feels off. Open an issue on
[GitHub](https://github.com/bilalbelahrache-spec/host-migration/issues), or reach out on Discord:
**@treekh**.

## License

CC0-1.0 - do whatever you like with it.
