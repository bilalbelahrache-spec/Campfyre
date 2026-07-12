# Rules for the optional, explicit `obfuscatedJar` Gradle task - NOT part of
# the default `build`/`runClient` tasks, and NOT verified by an actual game
# launch (this environment can't drive a full Minecraft client). Run
# `./gradlew obfuscatedJar`, then smoke-test the result with two real
# players (see root CLAUDE.md's runClient/runClient2 pattern, pointing a
# real install's mods folder at the obfuscated jar instead) before ever
# distributing it.

# Renaming only - no shrinking, inlining, or control-flow obfuscation.
# Heavily mangled/packed Minecraft mod jars are a known false-positive
# trigger for antivirus and marketplace (CurseForge/Modrinth) malware
# scanners; staying to plain identifier renaming keeps that risk low.
-dontshrink
-dontoptimize
-dontwarn **

-keepattributes *Annotation*,InnerClasses,Signature,RuntimeVisible*,EnclosingMethod

# Fabric Loader finds both entrypoints purely by the fully-qualified class
# name listed in fabric.mod.json - renaming either breaks the mod at load
# time with a ClassNotFoundException.
-keep class com.bilal.campfyre.Campfyre { *; }
-keep class com.bilal.campfyre.client.CampfyreClient { *; }

# Same story for Mixin: every class here is referenced by exact name from
# campfyre.mixins.json / campfyre.client.mixins.json, and Mixin's own
# annotation processing (refmaps) resolves @Inject/@Shadow/etc. targets
# against these classes' real (unrenamed) structure. Keep the whole
# subpackage, not just the mixin classes themselves - renaming a shared
# field/method they use elsewhere in the mod could break the same binding.
-keep class com.bilal.campfyre.mixin.** { *; }
-keep class com.bilal.campfyre.client.mixin.** { *; }

# Bundled via Loom's Jar-in-Jar (see build.gradle's `include`) - this is a
# real published third-party library shipped inside the mod jar as-is, not
# our own code; nothing here should ever be renamed.
-keep class org.bitlet.weupnp.** { *; }
