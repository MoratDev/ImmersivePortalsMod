package qouteall.imm_ptl.core.render.context_management;

import java.lang.invoke.MethodHandles;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.ducks.IEFogRenderer;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import net.minecraft.core.BlockPos;
import org.joml.Vector4f;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link FogRenderer}
 * {@link qouteall.imm_ptl.core.mixin.client.multiworld_awareness.MixinFogRenderer}
 * {@link qouteall.imm_ptl.core.mixin.client.multiworld_awareness.MixinWaterFogEnvironment}
 */
@SuppressWarnings("SpellCheckingInspection")
public class FogRendererContext {
    public float red;
    public float green;
    public float blue;
    public int targetBiomeFog = -1;
    public int previousBiomeFog = -1;
    public long biomeChangedTime = -1L;

    // The last computed fog color. Tracked in MixinFogRenderer.
    public static float currentFogRed;
    public static float currentFogGreen;
    public static float currentFogBlue;

    public static Consumer<FogRendererContext> copyContextFromObject;
    public static Consumer<FogRendererContext> copyContextToObject;
    public static Supplier<Vec3> getCurrentFogColor;
    
    public static StaticFieldsSwappingManager<FogRendererContext> swappingManager;
    
    /**
     * Called from the static initializer that the mixin adds into {@link WaterFogEnvironment}.
     */
    public static void init() {
        swappingManager = new StaticFieldsSwappingManager<>(
            copyContextFromObject, copyContextToObject, false,
            FogRendererContext::new
        );
    }

    /**
     * {@link #swappingManager} is created when {@link WaterFogEnvironment} gets initialized.
     * (Since 1.21.6 it's initialized when GameRenderer creates the FogRenderer.)
     * (Referencing the class object does not initialize a class.)
     */
    public static void ensureInitialized() {
        if (swappingManager == null) {
            try {
                MethodHandles.lookup().ensureInitialized(WaterFogEnvironment.class);
            }
            catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            if (swappingManager == null) {
                throw new IllegalStateException(
                    "FogRendererContext is not initialized. The mixin of WaterFogEnvironment is not applied."
                );
            }
        }
    }

    public static void update() {
        ensureInitialized();
        swappingManager.setOuterDimension(RenderStates.originalPlayerDimension);
        swappingManager.resetChecks();
        if (ClientWorldLoader.getIsInitialized()) {
            ClientWorldLoader.getClientWorlds().forEach(world -> {
                ResourceKey<Level> dimension = world.dimension();
                swappingManager.contextMap.computeIfAbsent(
                    dimension,
                    k -> new StaticFieldsSwappingManager.ContextRecord<>(
                        dimension,
                        new FogRendererContext(),
                        dimension != RenderStates.originalPlayerDimension
                    )
                );
            });
        }
    }
    
    public static Vec3 getFogColorOf(
        ClientLevel destWorld, Vec3 pos
    ) {
        ensureInitialized();
        Minecraft client = Minecraft.getInstance();
        
        Profiler.get().push("get_fog_color");
        
        ClientLevel oldWorld = client.level;
        
        ResourceKey<Level> newWorldKey = destWorld.dimension();
        
        swappingManager.contextMap.computeIfAbsent(
            newWorldKey,
            k -> new StaticFieldsSwappingManager.ContextRecord<>(
                k, new FogRendererContext(), true
            )
        );
        
        swappingManager.pushSwapping(newWorldKey);
        client.level = destWorld;
        
        Camera newCamera = new Camera();
        ((IECamera) newCamera).portal_setPos(pos);
        ((IECamera) newCamera).portal_setFocusedEntity(client.cameraEntity);
        
        try {
            // In 1.21.2+ FogRenderer does not store the fog color in static fields.
            // The fog color is tracked in MixinFogRenderer.
            computeFogColor(newCamera, destWorld);

            Vec3 result = getCurrentFogColor.get();

            return result;
        }
        finally {
            swappingManager.popSwapping();
            client.level = oldWorld;
            
            Profiler.get().pop();
        }
    }
    
    /**
     * Compute the fog color in the same way as GameRenderer#renderLevel.
     * It refreshes the tracked fog color.
     */
    public static Vector4f computeFogColor(Camera camera, ClientLevel world) {
        Minecraft client = Minecraft.getInstance();
        BlockPos blockPos = camera.getBlockPosition();
        boolean isFoggy = world.effects().isFoggyAt(blockPos.getX(), blockPos.getZ())
            || client.gui.getBossOverlay().shouldCreateWorldFog();
        float partialTick = RenderStates.getPartialTick();

        return ((IEFogRenderer) ((IEGameRenderer) client.gameRenderer).ip_getFogRenderer())
            .ip_computeFogColor(
                camera, partialTick, world,
                client.options.getEffectiveRenderDistance(),
                client.gameRenderer.getDarkenWorldAmount(partialTick),
                isFoggy
            );
    }

    public static void onPlayerTeleport(ResourceKey<Level> from, ResourceKey<Level> to) {
        ensureInitialized();
        swappingManager.updateOuterDimensionAndChangeContext(to);
    }
    
}
