buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Only used by the opt-in `obfuscatedJar` task below - never touches
        // the default build/runClient/runServer path.
        classpath("com.guardsquare:proguard-gradle:7.4.2")
    }
}

plugins {
    id("net.fabricmc.fabric-loom-remap")
    `maven-publish`
}

// Suffixed with the Minecraft version so each target's jar in build/libs/ is
// distinguishable by filename; fabric.mod.json's own "version" field stays
// the plain mod.version (see processResources below) - only the jar
// filename needs disambiguating across versions, not the metadata itself.
version = "${property("mod.version")}+${stonecutter.current.version}"
group = "${property("mod.group")}"
base.archivesName.set(property("mod.id") as String)

repositories {
    mavenCentral()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("campfyre") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runs {
        named("client") {
            programArg("--username")
            programArg("Player1Dev")
            // Real installs mint a fresh, unguessable group id on first run (see
            // CampfyreClient.loadConfig/mintNewGroupId). Our two dev-test
            // clients need to land in the SAME group instead, so they can test
            // host migration against each other - this property makes the mod
            // skip minting and reuse the same shared id both dev runs have
            // always used, without changing what a real player gets by default.
            property("campfyre.sharedDevGroupId", "dev-test-group")
        }

        create("client2") {
            environment("client")
            source(sourceSets["client"])
            configName = "Minecraft Client 2"
            runDir = "run2"
            programArg("--username")
            programArg("Player2Dev")
            property("campfyre.sharedDevGroupId", "dev-test-group")
        }
    }
}

dependencies {
    // To change the versions see stonecutter.properties.toml
    minecraft("com.mojang:minecraft:${stonecutter.current.version}")
    mappings("net.fabricmc:yarn:${sc.properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")

    // Fabric API. This is technically optional, but you probably want it anyway.
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"] as String}")

    // UPnP IGD client (port mapping + external IP lookup) - lets a host expose
    // their LAN Minecraft port directly to the internet without manually
    // touching router settings, so friends can connect straight to them
    // instead of always tunneling through the coordinator relay. Client-only:
    // this is only ever attempted from CampfyreClient, never from server code.
    // Tiny, dependency-free, focused purely on IGD discovery/port mapping/
    // external IP (verified against the real jar on Maven Central - not guessed).
    "clientImplementation"("org.bitlet:weupnp:0.1.4")
    // Bundles weupnp's classes directly into our built mod jar (Loom's
    // Jar-in-Jar) - without this, a real distributed jar would crash with
    // NoClassDefFoundError the first time UPnP mapping is attempted, since
    // plain (non-mod) dependencies aren't on a player's classpath otherwise.
    include("org.bitlet:weupnp:0.1.4")
}

// 17 through 1.20.4, 21 from 1.20.5 onward - see deps.java in stonecutter.properties.toml.
val javaVersion = (sc.properties["deps.java"] as String).toInt()
val mixinCompatibilityLevel = "JAVA_$javaVersion"

tasks.processResources {
    val modVersion = project.property("mod.version") as String
    val minecraftDependency = sc.properties["mod.mc_compat"] as String
    inputs.property("version", modVersion)
    inputs.property("minecraftDependency", minecraftDependency)
    inputs.property("mixinCompatibilityLevel", mixinCompatibilityLevel)

    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to modVersion, "minecraftDependency" to minecraftDependency))
    }
    filesMatching("*.mixins.json") {
        expand(mapOf("mixinCompatibilityLevel" to mixinCompatibilityLevel))
    }
}

tasks.named<Copy>(sourceSets["client"].processResourcesTaskName) {
    val compatibilityLevel = mixinCompatibilityLevel
    inputs.property("mixinCompatibilityLevel", compatibilityLevel)

    filesMatching("*.mixins.json") {
        expand(mapOf("mixinCompatibilityLevel" to compatibilityLevel))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
    // Opt-in only (`./gradlew build -PstripDebug`) - default dev builds
    // (runClient/runClient2/runServer) must stay fully debuggable. Compiling
    // with no LocalVariableTable/LineNumberTable/SourceFile already
    // meaningfully degrades a decompiler's output (locals become
    // var1/var2/arg0, no source-line mapping) for a real distributed jar,
    // at zero runtime risk - unlike the ProGuard task below, this can't
    // break anything since it only omits debug metadata Java itself never
    // needs to run.
    if (project.hasProperty("stripDebug")) {
        options.debugOptions.debugLevel = "none"
    }
}

// Optional, explicit release-hardening task - never part of the default
// `build`/`runClient` graph. Renames the mod's own classes/fields/methods
// (everything Fabric Loader and Mixin don't need to find by exact name -
// see proguard-rules.pro) to raise the bar against casual decompiling.
// Deliberately NOT verified by an actual game launch (this dev environment
// can't drive one) - run `./gradlew obfuscatedJar`, then smoke-test the
// output jar with two real players before ever distributing it.
tasks.register<proguard.gradle.ProGuardTask>("obfuscatedJar") {
    dependsOn(tasks.named("remapJar"))
    injars(tasks.named("remapJar").get().outputs.files.singleFile)
    outjars(layout.buildDirectory.file("libs/${project.name}-${project.version}-obfuscated.jar"))

    // Java 17 has no rt.jar to point at - the JDK's own modules stand in
    // for it, plus the mod's full compile classpath (remapped Minecraft,
    // Fabric API/Loader, weupnp) so ProGuard can resolve every reference
    // it's tracing without those becoming "not found" warnings.
    libraryjars(
        mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
        "${System.getProperty("java.home")}/jmods/java.base.jmod"
    )
    // The client source set's compile classpath already includes everything
    // main's does (Loom's splitEnvironmentSourceSets wiring) - passing both
    // separately double-lists any dependency common to both (e.g.
    // org.jetbrains:annotations), which ProGuard rejects outright as a
    // duplicate libraryjars entry rather than silently deduplicating.
    libraryjars(sourceSets["client"].compileClasspath)

    configuration(file("proguard-rules.pro"))
}

java {
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()

    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
}

tasks.jar {
    val modId = project.property("mod.id") as String
    inputs.property("modId", modId)

    from("LICENSE") {
        rename { "${it}_$modId" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
    }
}
