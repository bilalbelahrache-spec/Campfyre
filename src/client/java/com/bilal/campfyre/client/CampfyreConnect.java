package com.bilal.campfyre.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
//? if >=1.20.5 {
/*import net.minecraft.client.network.CookieStorage;
import java.util.Map;
*///?}
//? if >=1.20.3 {
/*import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
*///?}
//? if <1.20.3 {
import net.minecraft.client.gui.screen.ConnectScreen;
//?}

/**
 * Version-compat shim for the mod's three connect call sites (direct-connect,
 * hole-punch-bridge, relay-reconnect): building the {@link ServerInfo} and
 * invoking {@code ConnectScreen.connect(...)} itself, whose signature grew a
 * {@code CookieStorage} argument at 1.20.5. Isolated here so a
 * Minecraft-version-driven signature change only needs fixing in one place
 * instead of three copies drifting out of sync - see CampfyreClient's
 * connectDirectly/bridgePunchedSocketAndConnect/reconnectThroughRelay.
 */
final class CampfyreConnect {
    private CampfyreConnect() {
    }

    static ServerInfo makeServerInfo(String name, String address) {
        //? if <1.20.2 {
        return new ServerInfo(name, address, false);
        //?}
        //? if >=1.20.2 {
        /*return new ServerInfo(name, address, ServerInfo.ServerType.OTHER);
        *///?}
    }

    static void connect(Screen parent, MinecraftClient client, ServerAddress address, ServerInfo info) {
        //? if <1.20.5 {
        ConnectScreen.connect(parent, client, address, info, false);
        //?}
        //? if >=1.20.5 <1.21.9 {
        /*ConnectScreen.connect(parent, client, address, info, false, new CookieStorage(Map.of()));
        *///?}
        //? if >=1.21.9 {
        /*ConnectScreen.connect(parent, client, address, info, false, new CookieStorage(Map.of(), Map.of(), false));
        *///?}
    }
}
