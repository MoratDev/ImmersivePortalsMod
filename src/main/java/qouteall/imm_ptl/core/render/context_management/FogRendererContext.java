package qouteall.imm_ptl.core.render.context_management;

import java.lang.invoke.MethodHandles;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IECamera;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link FogRenderer}
 * {@link qouteall.imm_ptl.core.mixin.client.multiworld_awareness.MixinFogRenderer}
 */
@SuppressWarnings("SpellCheckingInspection")
public class FogRendererContext {
    public float red;
    public float green;
    public float blue;
    public int targetBiomeFog = -1;
    public int previousBiomeFog = -1;
    public long biomeChangedTime = -1L;
    
    public static Consumer<FogRendererContext> copyContextFromObject;
    public static Consumer<FogRendererContext> copyContextToObject;
    public static Supplier<Vec3> getCurrentFogColor;
    
    public static StaticFieldsSwappingManager<FogRendererContext> swappingManager;
    
    /**
     * Called from the static initializer that the mixin adds into {@link FogRenderer}.
     */
    public static void init() {
        swappingManager = new StaticFieldsSwappingManager<>(
            copyContextFromObject, copyContextToObject, false,
            FogRendererContext::new
        );
    }

    /**
     * {@link #swappingManager} is created when {@link FogRenderer} gets initialized.
     * Since 1.21.2 vanilla first uses FogRenderer when rendering the world,
     * which is later than the first use of the fog context.
     * (Referencing the class object does not initialize a class.)
     */
    public static void ensureInitialized() {
        if (swappingManager == null) {
            try {
                MethodHandles.lookup().ensureInitialized(FogRenderer.class);
            }
            catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            if (swappingManager == null) {
                throw new IllegalStateException(
                    "FogRendererContext is not initialized. The mixin of FogRenderer is not applied."
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
            FogRenderer.computeFogColor(
                newCamera,
                RenderStates.getPartialTick(),
                destWorld,
                client.options.getEffectiveRenderDistance(),
                client.gameRenderer.getDarkenWorldAmount(RenderStates.getPartialTick())
            );

            Vec3 result = getCurrentFogColor.get();

            return result;
        }
        finally {
            swappingManager.popSwapping();
            client.level = oldWorld;
            
            Profiler.get().pop();
        }
    }
    
    public static void onPlayerTeleport(ResourceKey<Level> from, ResourceKey<Level> to) {
        ensureInitialized();
        swappingManager.updateOuterDimensionAndChangeContext(to);
    }
    
}
