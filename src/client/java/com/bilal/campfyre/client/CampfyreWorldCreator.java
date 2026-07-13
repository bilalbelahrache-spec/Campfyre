package com.bilal.campfyre.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
//? if <1.21.2 {
import net.minecraft.registry.DynamicRegistryManager;
//?}
//? if >=1.21.2 {
/*import net.minecraft.registry.RegistryWrapper;
*///?}
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;

import java.util.function.Function;

/**
 * Version-compat shim around {@link IntegratedServerLoader}'s world-open/
 * world-create entry points. Kept to a stable external contract (world name
 * in, world open or exception out) rather than a shared parameter list,
 * since the internal shape (a Screen parameter vs. a completion Runnable)
 * has actually changed between adjacent Minecraft versions in this range,
 * not just at the boundaries originally expected - see CampfyreClient's
 * startWorldGuarded/createManagedWorld.
 */
final class CampfyreWorldCreator {
    private CampfyreWorldCreator() {
    }

    /** Reopens an already-existing managed world by name. */
    static void start(MinecraftClient client, Screen parent, String worldName) {
        //? if <1.20.3 {
        client.createIntegratedServerLoader().start(parent, worldName);
        //?}
        //? if >=1.20.3 {
        /*client.createIntegratedServerLoader().start(worldName, () -> {
        });
        *///?}
    }

    /** Creates a brand-new managed world and opens it. */
    //? if <1.20.3 {
    static void createAndStart(
            MinecraftClient client,
            String worldName,
            LevelInfo levelInfo,
            GeneratorOptions generatorOptions,
            Function<DynamicRegistryManager, DimensionOptionsRegistryHolder> dimensionsFactory
    ) {
        client.createIntegratedServerLoader().createAndStart(worldName, levelInfo, generatorOptions, dimensionsFactory);
    }
    //?}
    //? if >=1.20.3 <1.21.2 {
    /*static void createAndStart(
            MinecraftClient client,
            String worldName,
            LevelInfo levelInfo,
            GeneratorOptions generatorOptions,
            Function<DynamicRegistryManager, DimensionOptionsRegistryHolder> dimensionsFactory
    ) {
        client.createIntegratedServerLoader().createAndStart(worldName, levelInfo, generatorOptions, dimensionsFactory, null);
    }
    *///?}
    //? if >=1.21.2 {
    /*static void createAndStart(
            MinecraftClient client,
            String worldName,
            LevelInfo levelInfo,
            GeneratorOptions generatorOptions,
            Function<RegistryWrapper.WrapperLookup, DimensionOptionsRegistryHolder> dimensionsFactory
    ) {
        client.createIntegratedServerLoader().createAndStart(worldName, levelInfo, generatorOptions, dimensionsFactory, null);
    }
    *///?}
}
