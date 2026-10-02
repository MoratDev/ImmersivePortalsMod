package qouteall.imm_ptl.gametest;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * Drives a real client: creates a world, spawns portals, looks through them,
 * walks through them and takes screenshots.
 * Then it does the portal part again with a dedicated server,
 * because a singleplayer world does not serialize the packets.
 * <p>
 * Run with {@code gradlew runClientGameTest}.
 * The screenshots are in {@code build/run/clientGameTest/screenshots}.
 * <p>
 * The world is the superflat world that the test framework creates. Its surface is at y = -60.
 */
public class ImmPtlClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double GROUND_Y = -60;

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        // Mixins are applied when the target class loads.
        // This loads all target classes, so that every mixin of every loaded config gets applied and checked.
        LOGGER.info("[ImmPtlTest] auditing mixins");
        // on the client thread, because some classes access Minecraft.getInstance() in static initializer
        context.runOnClient(client -> MixinEnvironment.getCurrentEnvironment().audit());
        LOGGER.info("[ImmPtlTest] mixin audit finished");

        // the cloud rendering with portals has its own code path
        context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.FANCY));

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();

            waitForChunks(context, 3);

            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");

            testItems(context, server);
            testSameDimensionPortal(context, server, "sp_");
            testCrossDimensionPortal(context, server, "sp_");
            testOtherRenderModes(context, server);
            testEntityTeleportation(context, server);
            testCloudsThroughPortal(context, server);
            testRespawn(context, server);
            testMirror(context, server);
            testNetherPortalGeneration(context, server);
            testWrappingCommand(context, server);
            testAlternateDimensions(context, server);
        }

        if (isEulaAccepted()) {
            Properties serverProperties = new Properties();
            serverProperties.setProperty("online-mode", "false");
            serverProperties.setProperty("spawn-protection", "0");
            serverProperties.setProperty("view-distance", "6");

            try (TestDedicatedServerContext server = context.worldBuilder().createServer(serverProperties)) {
                try (TestServerConnection connection = server.connect()) {
                    waitForChunks(context, 3);

                    server.runCommand("gamemode creative @a");
                    server.runCommand("time set noon");

                    testSameDimensionPortal(context, server, "dedicated_");
                    testCrossDimensionPortal(context, server, "dedicated_");
                }
            }
        }
        else {
            LOGGER.warn(
                "[ImmPtlTest] SKIPPED the dedicated server part. " +
                    "The dedicated server needs you to accept the Minecraft EULA. " +
                    "To run that part, create the file eula.txt with the content eula=true in {}",
                FabricLoader.getInstance().getGameDir().toAbsolutePath()
            );
        }

        if (!failures.isEmpty()) {
            throw new AssertionError(
                "Immersive Portals game test failures:\n" + String.join("\n", failures)
            );
        }

        LOGGER.info("[ImmPtlTest] ALL PASSED");
    }

    /**
     * The test does not accept the EULA for you.
     */
    private static boolean isEulaAccepted() {
        Path eulaFile = FabricLoader.getInstance().getGameDir().resolve("eula.txt");
        try {
            return Files.exists(eulaFile) &&
                Files.readString(eulaFile).replace(" ", "").contains("eula=true");
        }
        catch (IOException e) {
            return false;
        }
    }

    /**
     * The test framework's waitForChunksRender() checks vanilla's client chunk storage.
     * Immersive Portals replaces the client chunk storage, so check the chunks around the player here.
     */
    private static void waitForChunks(ClientGameTestContext context, int radius) {
        context.waitFor(client -> {
            if (client.player == null || client.level == null) {
                return false;
            }
            ChunkPos center = client.player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (client.level.getChunkSource().getChunk(
                        center.x + dx, center.z + dz, ChunkStatus.FULL, false
                    ) == null) {
                        return false;
                    }
                }
            }
            return true;
        }, 600);
        // let the sections compile
        context.waitTicks(40);
    }

    private void check(boolean condition, String message) {
        if (condition) {
            LOGGER.info("[ImmPtlTest] PASS {}", message);
        }
        else {
            LOGGER.error("[ImmPtlTest] FAIL {}", message);
            failures.add(message);
        }
    }

    private static ServerPlayer getPlayer(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static Vec3 serverPlayerPos(TestServerContext server) {
        return server.computeOnServer(s -> getPlayer(s).position());
    }

    private static ResourceKey<Level> serverPlayerDim(TestServerContext server) {
        return server.computeOnServer(s -> getPlayer(s).level().dimension());
    }

    private static ResourceKey<Level> clientPlayerDim(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player.level().dimension());
    }

    private static Vec3 clientPlayerPos(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player.position());
    }

    private static int countEntities(
        TestServerContext server, ResourceKey<Level> dim, Predicate<Entity> predicate
    ) {
        return server.computeOnServer(s -> {
            int count = 0;
            for (Entity e : s.getLevel(dim).getAllEntities()) {
                if (predicate.test(e)) {
                    count++;
                }
            }
            return count;
        });
    }

    /**
     * Spawns a portal whose normal points to +z (it's visible and enterable from the +z side).
     */
    private static void spawnPortal(
        TestServerContext server, ResourceKey<Level> fromDim, Vec3 origin,
        ResourceKey<Level> toDim, Vec3 destination,
        double width, double height, boolean biWay
    ) {
        server.runOnServer(s -> {
            ServerLevel world = s.getLevel(fromDim);
            Portal portal = Portal.ENTITY_TYPE.create(world, EntitySpawnReason.COMMAND);
            portal.setOriginPos(origin);
            portal.setDestinationDimension(toDim);
            portal.setDestination(destination);
            portal.setOrientationAndSize(
                new Vec3(1, 0, 0), new Vec3(0, 1, 0), width, height
            );
            McHelper.spawnServerEntity(portal);
            if (biWay) {
                PortalManipulation.completeBiWayPortal(portal, Portal.ENTITY_TYPE);
            }
        });
    }

    private void testItems(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("give @a immersive_portals:portal_wand");
        server.runCommand("give @a immersive_portals:command_stick");
        server.runCommand("give @a immersive_portals:portal_helper");
        context.waitTicks(10);

        int itemCount = server.computeOnServer(s -> {
            int count = 0;
            for (int i = 0; i < 9; i++) {
                if (!getPlayer(s).getInventory().getItem(i).isEmpty()) {
                    count++;
                }
            }
            return count;
        });
        check(itemCount == 3, "the 3 mod items can be given (got " + itemCount + ")");

        context.takeScreenshot("items_in_hotbar");

        server.runCommand("clear @a");
    }

    private void testSameDimensionPortal(ClientGameTestContext context, TestServerContext server, String prefix) {
        // a marker that can only be seen through the portal
        server.runCommand("forceload add 96 -16 111 15");
        server.runCommand("fill 98 -60 0 102 -56 0 minecraft:gold_block");
        server.runCommand("fill 99 -60 0 101 -57 0 minecraft:diamond_block");

        // the player stands at z=8.5 looking north (to -z). the portal is at z=5 and faces +z
        server.runCommand("tp @p 0.5 -60 8.5 180 0");
        spawnPortal(
            server, Level.OVERWORLD, new Vec3(0.5, GROUND_Y + 1.5, 5),
            Level.OVERWORLD, new Vec3(100.5, GROUND_Y + 1.5, 5),
            2, 3, true
        );

        context.waitTicks(60);
        waitForChunks(context, 3);
        context.waitTicks(20);

        int renderedPortals = context.computeOnClient(client -> RenderStates.lastPortalRenderInfos.size());
        check(renderedPortals >= 1, prefix + "portal in the same dimension is rendered (rendered portals: " + renderedPortals + ")");
        context.takeScreenshot(prefix + "same_dimension_portal");

        // walk through it
        context.getInput().holdKeyFor(options -> options.keyUp, 30);
        context.waitTicks(10);

        Vec3 serverPos = serverPlayerPos(server);
        Vec3 clientPos = clientPlayerPos(context);
        check(Math.abs(serverPos.x - 100.5) < 3, prefix + "server player teleported through the portal (server pos " + serverPos + ")");
        check(Math.abs(clientPos.x - 100.5) < 3, prefix + "client player teleported through the portal (client pos " + clientPos + ")");
        check(serverPos.distanceTo(clientPos) < 2, prefix + "client and server positions agree after teleportation");

        waitForChunks(context, 3);
        context.waitTicks(20);
        context.takeScreenshot(prefix + "after_same_dimension_teleport");
    }

    private void testCrossDimensionPortal(ClientGameTestContext context, TestServerContext server, String prefix) {
        // make a room in the nether
        server.runCommand("execute in minecraft:the_nether run forceload add -16 -16 15 31");
        context.waitTicks(40);
        server.runCommand("execute in minecraft:the_nether run fill -6 69 -2 6 69 14 minecraft:glowstone");
        server.runCommand("execute in minecraft:the_nether run fill -6 70 -2 6 76 14 minecraft:air");
        server.runCommand("execute in minecraft:the_nether run fill -2 70 -1 2 73 -1 minecraft:emerald_block");

        server.runCommand("tp @p 0.5 -60 28.5 180 0");
        spawnPortal(
            server, Level.OVERWORLD, new Vec3(0.5, GROUND_Y + 1.5, 25),
            Level.NETHER, new Vec3(0.5, 71.5, 5),
            2, 3, true
        );

        context.waitTicks(100);
        context.takeScreenshot(prefix + "cross_dimension_portal");

        check(serverPlayerDim(server) == Level.OVERWORLD, prefix + "player is in overworld before walking");

        context.getInput().holdKeyFor(options -> options.keyUp, 30);
        context.waitTicks(20);

        check(serverPlayerDim(server) == Level.NETHER, prefix + "server player moved to the nether (is in " + serverPlayerDim(server).location() + ")");
        check(clientPlayerDim(context) == Level.NETHER, prefix + "client player moved to the nether (is in " + clientPlayerDim(context).location() + ")");
        Vec3 serverPos = serverPlayerPos(server);
        Vec3 clientPos = clientPlayerPos(context);
        check(serverPos.distanceTo(clientPos) < 2, prefix + "client and server positions agree in the nether (" + serverPos + " vs " + clientPos + ")");
        check(Math.abs(serverPos.y - 70) < 2, prefix + "player is on the floor of the nether room (y " + serverPos.y + ")");

        context.waitTicks(40);
        context.takeScreenshot(prefix + "in_nether_after_teleport");

        // look back at the overworld through the reverse portal.
        // it's a vanilla teleport within the nether, it goes through the position sync packet
        server.runCommand("execute in minecraft:the_nether run tp @p 0.5 70 1.5 0 0");
        context.waitTicks(60);
        Vec3 posAfterTp = clientPlayerPos(context);
        check(posAfterTp.distanceTo(new Vec3(0.5, 70, 1.5)) < 0.5, prefix + "the tp command in the nether moves the client player (client pos " + posAfterTp + ")");
        context.takeScreenshot(prefix + "looking_back_at_overworld");

        // walk back
        context.getInput().holdKeyFor(options -> options.keyUp, 40);
        context.waitTicks(20);
        check(serverPlayerDim(server) == Level.OVERWORLD, prefix + "server player walked back to the overworld (is in " + serverPlayerDim(server).location() + ")");
        check(clientPlayerDim(context) == Level.OVERWORLD, prefix + "client player walked back to the overworld (is in " + clientPlayerDim(context).location() + ")");

        context.waitTicks(40);
        context.takeScreenshot(prefix + "back_in_overworld");

        // the vanilla cross-dimension teleport
        server.runCommand("execute in minecraft:the_nether run tp @p 0.5 70 8.5 180 0");
        context.waitTicks(60);
        check(serverPlayerDim(server) == Level.NETHER, prefix + "the tp command across dimensions moves the server player (is in " + serverPlayerDim(server).location() + ")");
        check(clientPlayerDim(context) == Level.NETHER, prefix + "the tp command across dimensions moves the client player (is in " + clientPlayerDim(context).location() + ")");
        Vec3 clientPosInNether = clientPlayerPos(context);
        check(clientPosInNether.distanceTo(new Vec3(0.5, 70, 8.5)) < 0.5, prefix + "client position is right after the tp command across dimensions (client pos " + clientPosInNether + ")");
        context.takeScreenshot(prefix + "after_tp_command_to_nether");

        server.runCommand("execute in minecraft:overworld run tp @p 0.5 -60 60.5 0 0");
        context.waitTicks(60);
        check(clientPlayerDim(context) == Level.OVERWORLD, prefix + "the tp command back to overworld works (client is in " + clientPlayerDim(context).location() + ")");
    }

    /**
     * The default portal renderer uses the stencil buffer.
     * There is also the "compatibility" renderer that renders the portal content into another framebuffer
     * (it uses another one of the mod's shaders) and the "debug" renderer.
     * And vanilla has the fabulous graphics mode that uses more render targets.
     * It looks at the portals that the previous tests created.
     */
    private void testOtherRenderModes(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("tp @p 0.5 -60 28.5 180 0");
        context.waitTicks(40);
        
        context.runOnClient(client -> IPGlobal.renderMode = IPGlobal.RenderMode.compatibility);
        context.waitTicks(40);
        int rendered = context.computeOnClient(client -> RenderStates.lastPortalRenderInfos.size());
        check(rendered >= 1, "the compatibility renderer renders the portal (rendered portals: " + rendered + ")");
        context.takeScreenshot("render_mode_compatibility");
        
        context.runOnClient(client -> IPGlobal.renderMode = IPGlobal.RenderMode.debug);
        context.waitTicks(20);
        context.takeScreenshot("render_mode_debug");
        
        context.runOnClient(client -> IPGlobal.renderMode = IPGlobal.RenderMode.normal);
        context.waitTicks(20);
        
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            // Iris does not support the fabulous graphics mode and turns it off
            LOGGER.info("[ImmPtlTest] SKIPPED the fabulous graphics mode part because Iris is present");
            return;
        }
        
        context.runOnClient(client -> {
            client.options.graphicsMode().set(GraphicsStatus.FABULOUS);
            client.levelRenderer.allChanged();
        });
        context.waitTicks(80);
        rendered = context.computeOnClient(client -> RenderStates.lastPortalRenderInfos.size());
        check(rendered >= 1, "the portal is rendered in fabulous graphics mode (rendered portals: " + rendered + ")");
        boolean stillFabulous = context.computeOnClient(client -> client.options.graphicsMode().get() == GraphicsStatus.FABULOUS);
        check(stillFabulous, "the fabulous graphics mode stays enabled (vanilla turns it off when its shader fails)");
        context.takeScreenshot("fabulous_graphics");
        
        context.runOnClient(client -> {
            client.options.graphicsMode().set(GraphicsStatus.FANCY);
            client.levelRenderer.allChanged();
        });
        context.waitTicks(60);
    }
    
    /**
     * Non-player entities should go through portals too.
     * It uses the portals that the previous tests created.
     */
    private void testEntityTeleportation(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("tp @p 4.5 -60 12.5 160 0");

        // through the portal at z=5 that goes to x=100 in the same dimension
        server.runCommand("summon minecraft:arrow 0.5 -58.5 7.5 {NoGravity:1b,Motion:[0.0d,0.0d,-0.5d]}");
        // through the portal at z=25 that goes to the nether
        server.runCommand("summon minecraft:arrow 0.5 -58.5 27.5 {NoGravity:1b,Motion:[0.0d,0.0d,-0.5d]}");

        context.waitTicks(40);

        int arrowsNearDestination = countEntities(
            server, Level.OVERWORLD,
            e -> e instanceof AbstractArrow && Math.abs(e.getX() - 100.5) < 3
        );
        check(arrowsNearDestination == 1, "an arrow goes through the portal in the same dimension (arrows near destination: " + arrowsNearDestination + ")");

        int arrowsInNether = countEntities(server, Level.NETHER, e -> e instanceof AbstractArrow);
        check(arrowsInNether == 1, "an arrow goes through the portal to the nether (arrows in nether: " + arrowsInNether + ")");

        server.runCommand("execute in minecraft:overworld run kill @e[type=minecraft:arrow]");
        server.runCommand("execute in minecraft:the_nether run kill @e[type=minecraft:arrow]");
    }

    /**
     * The portal view is rendered from another camera position, where the cloud mesh is different.
     * There is a cache of cloud meshes for that.
     */
    private void testCloudsThroughPortal(ClientGameTestContext context, TestServerContext server) {
        // the clouds are at y=192
        server.runCommand("fill -3 169 2 3 169 12 minecraft:glass");
        server.runCommand("forceload add 296 -16 311 15");
        server.runCommand("fill 297 169 2 303 169 12 minecraft:glass");
        server.runCommand("tp @p 0.5 170 9.5 180 -25");
        spawnPortal(
            server, Level.OVERWORLD, new Vec3(0.5, 172, 5),
            Level.OVERWORLD, new Vec3(300.5, 172, 5),
            3, 4, false
        );
        
        context.waitTicks(80);
        
        int renderedPortals = context.computeOnClient(client -> RenderStates.lastPortalRenderInfos.size());
        check(renderedPortals >= 1, "the portal in the sky is rendered (rendered portals: " + renderedPortals + ")");
        context.takeScreenshot("clouds_through_portal");
        
        server.runCommand("kill @e[type=immersive_portals:portal,distance=..20]");
        context.waitTicks(10);
    }
    
    /**
     * Dying in the nether and respawning in the overworld.
     * The respawn changes the player's dimension by the vanilla way.
     */
    private void testRespawn(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("gamemode survival @a");
        server.runCommand("execute in minecraft:the_nether run tp @p 0.5 70 8.5 180 0");
        context.waitTicks(60);
        check(clientPlayerDim(context) == Level.NETHER, "before dying, the client player is in the nether");
        
        server.runCommand("kill @p");
        context.waitFor(client -> client.screen instanceof DeathScreen, 200);
        context.waitTicks(30);
        context.runOnClient(client -> {
            client.player.respawn();
            client.setScreen(null);
        });
        context.waitTicks(80);
        
        check(serverPlayerDim(server) == Level.OVERWORLD, "the server player respawned in the overworld (is in " + serverPlayerDim(server).location() + ")");
        check(clientPlayerDim(context) == Level.OVERWORLD, "the client player respawned in the overworld (is in " + clientPlayerDim(context).location() + ")");
        boolean alive = context.computeOnClient(client -> client.player.isAlive());
        check(alive, "the client player is alive after respawning");
        Vec3 serverPos = serverPlayerPos(server);
        Vec3 clientPos = clientPlayerPos(context);
        check(serverPos.distanceTo(clientPos) < 2, "client and server positions agree after respawning (" + serverPos + " vs " + clientPos + ")");
        
        server.runCommand("gamemode creative @a");
        context.waitTicks(10);
        context.takeScreenshot("after_respawn");
    }
    
    private void testMirror(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("fill -3 -60 -44 3 -55 -44 minecraft:white_stained_glass");
        server.runCommand("fill 2 -60 -40 2 -58 -40 minecraft:redstone_block");
        server.runCommand("tp @p 0.5 -60 -39.5 180 0");

        server.runOnServer(s -> {
            ServerLevel world = s.getLevel(Level.OVERWORLD);
            Mirror mirror = Mirror.ENTITY_TYPE.create(world, EntitySpawnReason.COMMAND);
            mirror.setOriginPos(new Vec3(0.5, GROUND_Y + 2.5, -43.0 + 0.01));
            mirror.setDestinationDimension(Level.OVERWORLD);
            mirror.setDestination(new Vec3(0.5, GROUND_Y + 2.5, -43.0 + 0.01));
            mirror.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 5, 5);
            McHelper.spawnServerEntity(mirror);
        });

        context.waitTicks(60);
        context.takeScreenshot("mirror");

        int mirrors = countEntities(server, Level.OVERWORLD, e -> e instanceof Mirror);
        check(mirrors == 1, "mirror entity exists on server (count " + mirrors + ")");
    }

    /**
     * Lighting an obsidian frame should generate the mod's see-through nether portal
     * instead of the vanilla one (the default nether portal mode).
     */
    private void testNetherPortalGeneration(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("tp @p 40.5 -60 -34.5 180 0");
        // 4x5 obsidian frame in the x-y plane at z = -40, inner area 2x3.
        // its bottom row is in the ground, so that the player can walk into it
        server.runCommand("fill 39 -61 -40 42 -57 -40 minecraft:obsidian");
        server.runCommand("fill 40 -60 -40 41 -58 -40 minecraft:air");
        context.waitTicks(5);
        server.runCommand("setblock 40 -60 -40 minecraft:fire");

        int waited = 0;
        int portals = 0;
        while (waited < 600) {
            context.waitTicks(20);
            waited += 20;
            portals = countEntities(server, Level.OVERWORLD, e -> e instanceof NetherPortalEntity);
            if (portals > 0) {
                break;
            }
        }

        check(portals > 0, "lighting an obsidian frame generates the nether portal entities (count " + portals + ", waited " + waited + " ticks)");

        context.waitTicks(100);
        context.takeScreenshot("generated_nether_portal");

        context.getInput().holdKeyFor(options -> options.keyUp, 40);
        context.waitTicks(20);
        check(serverPlayerDim(server) == Level.NETHER, "walking into the generated nether portal goes to the nether (is in " + serverPlayerDim(server).location() + ")");
        check(clientPlayerDim(context) == Level.NETHER, "client is in the nether after walking into the generated nether portal (is in " + clientPlayerDim(context).location() + ")");

        context.waitTicks(40);
        context.takeScreenshot("through_generated_nether_portal");

        // go back, so that the next test begins in the overworld
        server.runCommand("execute in minecraft:overworld run tp @p 0.5 -60 60.5 0 0");
        context.waitTicks(40);
        check(clientPlayerDim(context) == Level.OVERWORLD, "the tp command back from the generated portal's destination works (client is in " + clientPlayerDim(context).location() + ")");
    }

    /**
     * Adds the mod's special dimensions while the player is online (by the DimLib command)
     * and goes into them. It tests the chunk generators and the dynamic dimension sync.
     */
    private void testAlternateDimensions(ClientGameTestContext context, TestServerContext server) {
        for (String template : new String[]{"skyland", "chaos", "bright_void"}) {
            String dimId = "iptest:" + template;
            server.runCommand("dims add_dimension \"" + dimId + "\" " + template);
            context.waitTicks(20);
            
            boolean exists = server.computeOnServer(s -> {
                for (ServerLevel level : s.getAllLevels()) {
                    if (level.dimension().location().toString().equals(dimId)) {
                        return true;
                    }
                }
                return false;
            });
            check(exists, "the dimension " + dimId + " is added from the template " + template);
            if (!exists) {
                continue;
            }
            
            // these dimensions may have nothing below. make a platform so that the player does not fall into the void
            server.runCommand("execute in " + dimId + " run forceload add 0 0");
            context.waitTicks(40);
            server.runCommand("execute in " + dimId + " run fill -2 129 -2 2 129 2 minecraft:glass");
            server.runCommand("execute in " + dimId + " run fill -2 130 -2 2 132 2 minecraft:air");
            server.runCommand("execute in " + dimId + " run tp @p 0.5 130 0.5 0 40");
            context.waitTicks(120);
            
            String clientDim = clientPlayerDim(context).location().toString();
            check(clientDim.equals(dimId), "the client player is in " + dimId + " (is in " + clientDim + ")");
            String serverDim = serverPlayerDim(server).location().toString();
            check(serverDim.equals(dimId), "the server player is in " + dimId + " (is in " + serverDim + ")");
            context.takeScreenshot("alternate_dimension_" + template);
        }
        
        server.runCommand("execute in minecraft:overworld run tp @p 0.5 -60 60.5 0 0");
        context.waitTicks(60);
        check(clientPlayerDim(context) == Level.OVERWORLD, "back to the overworld from the alternate dimensions (client is in " + clientPlayerDim(context).location() + ")");
    }
    
    /**
     * The command creates 6 portals that wrap a box region.
     */
    private void testWrappingCommand(ClientGameTestContext context, TestServerContext server) {
        int before = countEntities(server, Level.OVERWORLD, e -> e instanceof Portal);
        server.runCommand("execute as @p in minecraft:overworld run portal create_small_inward_wrapping -30 -60 80 -20 -50 90");
        context.waitTicks(20);
        int after = countEntities(server, Level.OVERWORLD, e -> e instanceof Portal);
        check(after - before == 6, "the command create_small_inward_wrapping creates 6 portals (created " + (after - before) + ")");

        server.runCommand("tp @p -25 -60 85 0 0");
        context.waitTicks(60);
        context.takeScreenshot("inside_inward_wrapping_box");
    }
}
