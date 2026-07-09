package com.bilal.campfire.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

// The docked title-screen button is great until you're actually IN the world
// - then checking who's around the fire, grabbing the invite for one more
// friend, or seeing why the coordinator dot went red meant quitting to the
// title screen. One key (B by default, rebindable in vanilla's own Controls
// screen under a "Campfire" category) opens the same status/setup screens
// from gameplay. Key presses only register during gameplay (vanilla routes
// keys to the open Screen otherwise), so this can't fire while typing in
// chat or another GUI.
final class CampfireKeybinds {

    private CampfireKeybinds() {
    }

    static void register(CampfireClient mod) {
        KeyBinding openScreen = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.campfire.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.campfire"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openScreen.wasPressed()) {
                if (client.currentScreen != null) continue; // belt & braces - shouldn't happen mid-screen
                // Same routing as the docked title-screen button: the list
                // when there's a choice of campfires to make, setup when
                // there's nothing yet, status otherwise.
                String groupId = mod.getGroupId();
                boolean noActive = groupId == null || groupId.isBlank();
                if (mod.hasMultipleCampfires() || (noActive && !mod.getCampfires().isEmpty())) {
                    client.setScreen(new CampfireListScreen(mod, null));
                } else if (noActive) {
                    client.setScreen(new CampfireSetupScreen(mod, null));
                } else {
                    client.setScreen(new CampfireStatusScreen(mod, null));
                }
            }
        });
    }
}
