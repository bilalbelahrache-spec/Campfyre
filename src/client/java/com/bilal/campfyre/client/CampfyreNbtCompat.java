package com.bilal.campfyre.client;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
//? if =1.20.3 {
/*import net.minecraft.nbt.NbtTagSizeTracker;
*///?}
//? if >=1.20.4 {
/*import net.minecraft.nbt.NbtSizeTracker;
*///?}

import java.io.File;
import java.io.IOException;

/**
 * Version-compat shim for level.dat/playerdata NBT I/O. NbtIo dropped its
 * File-based readCompressed/writeCompressed overloads in favor of Path (plus
 * a required size-tracker arg on reads) at 1.20.3, and the size-tracker class
 * itself was then renamed NbtTagSizeTracker -> NbtSizeTracker at 1.20.4 - both
 * isolated here rather than at each of the 5 call sites in CampfyreClient's
 * swapPlayerData/readWorldDisplayName, since a wrong read here silently
 * corrupts a host's inventory (see src/CLAUDE.md's own warning about
 * readPlayerUuid).
 */
final class CampfyreNbtCompat {
    private CampfyreNbtCompat() {
    }

    static NbtCompound readCompressed(File file) throws IOException {
        //? if <1.20.3 {
        return NbtIo.readCompressed(file);
        //?}
        //? if =1.20.3 {
        /*return NbtIo.readCompressed(file.toPath(), NbtTagSizeTracker.ofUnlimitedBytes());
        *///?}
        //? if >=1.20.4 {
        /*return NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());
        *///?}
    }

    static void writeCompressed(NbtCompound nbt, File file) throws IOException {
        //? if <1.20.3 {
        NbtIo.writeCompressed(nbt, file);
        //?}
        //? if >=1.20.3 {
        /*NbtIo.writeCompressed(nbt, file.toPath());
        *///?}
    }
}
