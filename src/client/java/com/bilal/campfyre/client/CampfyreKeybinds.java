package com.bilal.campfyre.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
//? if >=1.21.9 {
/*import net.minecraft.util.Identifier;
*///?}
import org.lwjgl.glfw.GLFW;

// The docked title-screen button is great until you're actually IN the world
// - then checking who's around the fire, grabbing the invite for one more
// friend, or seeing why the coordinator dot went red meant quitting to the
// title screen. One key (B by default, rebindable in vanilla's own Controls
// screen under a "Campfyre" category) opens the same status/setup screens
// from gameplay. Key presses only register during gameplay (vanilla routes
// keys to the open Screen otherwise), so this can't fire while typing in
// chat or another GUI.
final class CampfyreKeybinds {

    // KeyBinding.Category.create(Identifier) throws if the same identifier is
    // registered twice - CampfyreZoom binds a key under the same "Campfyre"
    // category, so both keybinding registrations share this ONE instance
    // rather than each calling .create() with the same id (hit as a real
    // startup crash: "Category 'campfyre:main' is already registered").
    //? if >=1.21.9 {
    /*static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("campfyre", "main"));
    *///?}

    private CampfyreKeybinds() {
    }

    static void register(CampfyreClient mod) {
        //? if <1.21.9 {
        KeyBinding openScreen = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.campfyre.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.campfyre"));
        //?}
        //? if >=1.21.9 {
        /*KeyBinding openScreen = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.campfyre.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
        *///?}

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openScreen.wasPressed()) {
                if (client.currentScreen != null) continue; // belt & braces - shouldn't happen mid-screen
                // Same routing as the docked title-screen button: the list
                // when there's a choice of campfyres to make, setup when
                // there's nothing yet, status otherwise.
                String groupId = mod.getGroupId();
                boolean noActive = groupId == null || groupId.isBlank();
                if (mod.hasMultipleCampfyres() || (noActive && !mod.getCampfyres().isEmpty())) {
                    client.setScreen(new CampfyreListScreen(mod, null));
                } else if (noActive) {
                    client.setScreen(new CampfyreSetupScreen(mod, null));
                } else {
                    client.setScreen(new CampfyreStatusScreen(mod, null));
                }
            }
        });
    }
}
