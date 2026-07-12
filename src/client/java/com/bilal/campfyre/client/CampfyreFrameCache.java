package com.bilal.campfyre.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

// Fully independent of the hosting/relay/migration machinery in the rest of
// this package - it only reads MinecraftClient's own live state, never
// CampfyreClient's. A tiny, exact 1-in-1,000,000-per-real-second chance
// while actually in a world (not paused, not in a menu) that every
// currently-rendered mob freezes and locks onto the local viewer for 10
// seconds. LivingEntityRendererMixin is the other half - it only checks
// isActive() here and does the actual look-at work per rendered mob.
//
// Trigger design, each point chosen specifically to keep the odds exactly
// what they claim to be and nothing else:
// - Counts client TICKS, not wall-clock time, to define "one second" (20
//   ticks at vanilla's fixed 20 TPS) - ticks only advance while the client
//   is actually ticking, so there's no wall-clock/tick-rate mismatch to
//   reason about and nothing to "catch up" after a stall.
// - Resets the partial-second counter to 0 whenever not eligible (no world,
//   no player, or a screen/menu open) instead of letting it carry over -
//   time spent paused or in a menu never counts toward a roll, keeping the
//   boundary unambiguous.
// - Never rolls again while already active (see the early return in tick())
//   - re-rolling mid-effect could only ever extend the 10 seconds, which
//     would make the real odds of "this lasting longer than 10s" higher
//     than advertised. Guaranteed impossible instead of just made unlikely.
public final class CampfyreFrameCache {

    private static final int TICKS_PER_ROLL = 20;
    private static final int ROLL_DENOMINATOR = 1_000_000;
    private static final int EFFECT_DURATION_TICKS = 200;

    private static final java.util.Random RNG = new java.util.Random();

    private static int tickCounter = 0;
    private static int ticksRemaining = 0;

    private CampfyreFrameCache() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CampfyreFrameCache::tick);
    }

    private static void tick(MinecraftClient client) {
        // Checked BEFORE the active-countdown branch below: a disconnect,
        // quit-to-title, or host migration into an unrelated world/dimension
        // mid-effect used to leave ticksRemaining counting down regardless -
        // this is a static, session-agnostic field, so an active roll would
        // carry straight into whatever the player reconnects to next and
        // force every newly-rendered mob THERE to stare too, for however
        // many ticks were left. Self-healing within 10s either way, but
        // there's no reason to let it leak across a session boundary at all.
        if (client.world == null || client.player == null) {
            tickCounter = 0;
            ticksRemaining = 0;
            return;
        }
        if (ticksRemaining > 0) {
            ticksRemaining--;
            return;
        }
        if (client.currentScreen != null) {
            tickCounter = 0;
            return;
        }
        if (++tickCounter < TICKS_PER_ROLL) return;
        tickCounter = 0;
        if (RNG.nextInt(ROLL_DENOMINATOR) == 0) {
            ticksRemaining = EFFECT_DURATION_TICKS;
        }
    }

    /** Whether every rendered mob should currently be locked onto the local viewer. */
    public static boolean isActive() {
        return ticksRemaining > 0;
    }
}
