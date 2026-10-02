package qouteall.imm_ptl.core.render;

import net.minecraft.world.entity.Entity;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.ProjectionType;
import net.minecraft.util.profiling.Profiler;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.ducks.IEFogRenderer;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.mixin.client.render.IERenderSystem;
import qouteall.imm_ptl.core.mixin.client.render.IESectionRenderDispatcher;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.Stack;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public class MyGameRenderer {
    public static final Minecraft client = Minecraft.getInstance();
    
    private static final LimitedLogger limitedLogger = new LimitedLogger(10);
    
//    public static final int MAX_SECONDARY_BUFFER_NUM = 2;
    
    // portal rendering and outer world rendering uses different buffer builder storages
    private static Stack<RenderBuffers> secondaryRenderBuffers = new Stack<>();
    private static int usingRenderBuffersObjectNum = 0;
    
    // the vanilla visibility sections discovery code is multithreaded
    // when the player teleports through a portal, on the first frame it will not work normally
    // so use IP's non-multi-threaded algorithm at the first frame
    public static int vanillaTerrainSetupOverride = 0;
    
    public static boolean enablePortalCaveCulling = true;
    
    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            secondaryRenderBuffers.clear();
        });
    }
    
    @Nullable
    private static RenderBuffers acquireRenderBuffersObject() {
//        if (usingRenderBuffersObjectNum >= MAX_SECONDARY_BUFFER_NUM) {
//            return null;
//        }
        usingRenderBuffersObjectNum++;
        
        if (secondaryRenderBuffers.isEmpty()) {
            return new RenderBuffers(0);
        }
        else {
            return secondaryRenderBuffers.pop();
        }
    }
    
    private static void returnRenderBuffersObject(RenderBuffers renderBuffers) {
        usingRenderBuffersObjectNum--;
        secondaryRenderBuffers.push(renderBuffers);
    }
    
    public static void renderWorldNew(
        WorldRenderInfo worldRenderInfo,
        Consumer<Runnable> invokeWrapper
    ) {
        WorldRenderInfo.pushRenderInfo(worldRenderInfo);
        
        switchAndRenderTheWorld(
            worldRenderInfo.world,
            worldRenderInfo.cameraPos,
            worldRenderInfo.cameraPos,
            invokeWrapper,
            worldRenderInfo.renderDistance,
            worldRenderInfo.doRenderHand
        );
        
        WorldRenderInfo.popRenderInfo();
    }
    
    private static void switchAndRenderTheWorld(
        ClientLevel newWorld,
        Vec3 thisTickCameraPos,
        Vec3 lastTickCameraPos,
        Consumer<Runnable> invokeWrapper,
        int renderDistance,
        boolean doRenderHand
    ) {
        if (!enablePortalCaveCulling) {
            client.smartCull = false;
        }
        
        if (!PortalRendering.shouldEnableSodiumCaveCulling()) {
            client.smartCull = false;
        }
        
        ResourceKey<Level> newDimension = newWorld.dimension();
        
        LevelRenderer worldRenderer = ClientWorldLoader.getWorldRenderer(newDimension);
        
        CHelper.checkGlError();
        
        IEGameRenderer ieGameRenderer = (IEGameRenderer) client.gameRenderer;
        DimensionRenderHelper helper =
            ClientWorldLoader.getDimensionRenderHelper(newDimension);
        Camera newCamera = new Camera();
        
        // store old state
        ClientLevel oldWorld = client.level;
        LevelRenderer oldWorldRenderer = client.levelRenderer;
        LightTexture oldLightmap = client.gameRenderer.lightTexture();
        boolean oldNoClip = client.player.noPhysics;
        boolean oldDoRenderHand = ieGameRenderer.ip_getDoRenderHand();
        ObjectArrayList<SectionRenderDispatcher.RenderSection> oldChunkInfoList =
            ((IEWorldRenderer) oldWorldRenderer).portal_getChunkInfoList();
        HitResult oldCrosshairTarget = client.hitResult;
        Camera oldCamera = client.gameRenderer.getMainCamera();
        RenderBuffers oldRenderBuffers = ((IEWorldRenderer) worldRenderer).ip_getRenderBuffers();
        RenderBuffers oldClientRenderBuffers = client.renderBuffers();
        SectionBufferBuilderPack oldSectionRenderDispatcherFixedBuffers =
            ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                .ip_getFixedBuffers();
        Frustum oldFrustum = ((IEWorldRenderer) worldRenderer).portal_getFrustum();
        
        // the projection matrix contains view bobbing.
        // the view bobbing is related with scale
        // Since 1.21.6 the projection matrix is in a uniform buffer.
        // Rendering the portal content overwrites the buffer of level projection matrix.
        Matrix4f oldProjectionMatrix = new Matrix4f(MyRenderHelper.getLevelProjectionMatrix());
        GpuBufferSlice oldProjectionMatrixBuffer = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldProjectionType = RenderSystem.getProjectionType();

        // Since 1.21.6 the fog is in uniform buffer.
        // Every world rendering has its own fog buffer slice (see MixinFogRenderer).
        IEFogRenderer ieFogRenderer = (IEFogRenderer) ieGameRenderer.ip_getFogRenderer();
        GpuBufferSlice oldFog = ieFogRenderer.ip_getCurrentFog();
        GpuBufferSlice oldShaderFog = RenderSystem.getShaderFog();
        GpuBufferSlice oldShaderLights = RenderSystem.getShaderLights();
        Matrix4fStack oldModelViewStack = IERenderSystem.ip_getModelViewStack();

        // In 1.21.2+ the visible entity list is a field of LevelRenderer.
        // The outer world rendering and the portal rendering may use the same LevelRenderer.
        List<Entity> oldVisibleEntities = ((IEWorldRenderer) worldRenderer).ip_getVisibleEntities();
        ((IEWorldRenderer) worldRenderer).ip_setVisibleEntities(new ArrayList<>());

        ObjectArrayList<SectionRenderDispatcher.RenderSection> newChunkInfoList =
            VisibleSectionDiscovery.takeList();
        ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(newChunkInfoList);
        
        Object irisPipeline = IrisInterface.invoker.getPipeline(worldRenderer);
        
        // switch (note: it will no longer switch the world that client player is in )
        ((IEMinecraftClient) client).ip_setWorldRenderer(worldRenderer);
        client.level = newWorld;
        ieGameRenderer.ip_setLightmapTextureManager(helper.lightmapTexture);
        
        client.getBlockEntityRenderDispatcher().level = newWorld;
        client.player.noPhysics = true;
        ieGameRenderer.ip_setDoRenderHand(doRenderHand);
        
        FogRendererContext.swappingManager.pushSwapping(newDimension);
        ((IEParticleManager) client.particleEngine).ip_setWorld(newWorld);
        if (BlockManipulationClient.remotePointedDim == newDimension) {
            client.hitResult = BlockManipulationClient.remoteHitResult;
        }
        if (!PortalRendering.shouldRenderHitResult()) {
            client.hitResult = null;
        }
        ieGameRenderer.ip_setCamera(newCamera);
        
        RenderBuffers newRenderBuffers = null;
        if (IPGlobal.useSecondaryEntityVertexConsumer) {
            newRenderBuffers = acquireRenderBuffersObject();
            if (newRenderBuffers != null) {
                ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(newRenderBuffers);
                ((IEMinecraftClient) client).ip_setRenderBuffers(newRenderBuffers);
                
                /*
                  the vanilla buffer pack may be used by {@link net.minecraft.client.renderer.MultiBufferSource.BufferSource}
                  The BufferSource does not always immediately finish building.
                  Reusing that may cause "Already Building" error in Buffer Builder when doing main-thread chunk rebuilding.
                  This does not occur in vanilla because vanilla does main-thread chunk rebuilding before entity rendering. With portal rendering it could do chunk rebuilding after some entity rendering.
                 */
                ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                    .ip_setFixedBuffers(newRenderBuffers.fixedBufferPack());
            }
            else{
                // draw the content in the buffers,
                // to avoid messing with content in the portals
                // TODO it may draw with wrong stencil func here
                client.renderBuffers().bufferSource().endBatch();
            }
        }
        
        Object newSodiumContext = SodiumInterface.invoker.createNewContext(renderDistance);
        SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);
        
        // In 1.21.1 it set the transparency post chain of world renderer to null here.
        // Now it's done in MixinLevelRenderer (getTransparencyChain).

        // In 1.21.2+ there is no separate model view matrix to apply. It's the top of the stack.
        IERenderSystem.ip_setModelViewStack(new Matrix4fStack(16));

        IrisInterface.invoker.setPipeline(worldRenderer, null);
        
        //update lightmap
        if (!RenderStates.isDimensionRendered(newDimension)) {
            helper.lightmapTexture.updateLightTexture(0);
        }

        // Since 1.21.6 the diffuse light directions of level are in a uniform buffer
        // that vanilla updates when the client world changes.
        resetDiffuseLighting();
        
        //invoke rendering
        invokeWrapper.accept(() -> {
            Profiler.get().push("render_portal_content");
            client.gameRenderer.renderLevel(
                client.getDeltaTracker()
            );
            Profiler.get().pop();
        });
        
        SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);
        
        //recover
        
        ((IEMinecraftClient) client).ip_setWorldRenderer(oldWorldRenderer);
        client.level = oldWorld;
        ieGameRenderer.ip_setLightmapTextureManager(oldLightmap);
        client.getBlockEntityRenderDispatcher().level = oldWorld;
        client.player.noPhysics = oldNoClip;
        ieGameRenderer.ip_setDoRenderHand(oldDoRenderHand);
        
        ((IEParticleManager) client.particleEngine).ip_setWorld(oldWorld);
        client.hitResult = oldCrosshairTarget;
        ieGameRenderer.ip_setCamera(oldCamera);
        
        ((IEWorldRenderer) worldRenderer).ip_setVisibleEntities(oldVisibleEntities);

        FogRendererContext.swappingManager.popSwapping();
        
        ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(oldChunkInfoList);
        VisibleSectionDiscovery.returnList(newChunkInfoList);
        
        ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(oldRenderBuffers);
        ((IEMinecraftClient) client).ip_setRenderBuffers(oldClientRenderBuffers);
        ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
            .ip_setFixedBuffers(oldSectionRenderDispatcherFixedBuffers);
        if (newRenderBuffers != null) {
            returnRenderBuffersObject(newRenderBuffers);
        }
        
        ((IEWorldRenderer) worldRenderer).portal_setFrustum(oldFrustum);
        
        ieGameRenderer.ip_getLevelProjectionMatrixBuffer().getBuffer(oldProjectionMatrix);
        MyRenderHelper.setLevelProjectionMatrix(oldProjectionMatrix);
        if (oldProjectionMatrixBuffer != null) {
            RenderSystem.setProjectionMatrix(oldProjectionMatrixBuffer, oldProjectionType);
        }
        IERenderSystem.ip_setModelViewStack(oldModelViewStack);

        ieFogRenderer.ip_setCurrentFog(oldFog);
        if (oldShaderFog != null) {
            RenderSystem.setShaderFog(oldShaderFog);
        }

        resetDiffuseLighting();
        if (oldShaderLights != null) {
            RenderSystem.setShaderLights(oldShaderLights);
        }

        IrisInterface.invoker.setPipeline(worldRenderer, irisPipeline);
        
        client.getEntityRenderDispatcher()
            .prepare(
                client.level,
                oldCamera,
                client.crosshairPickEntity
            );
        
        CHelper.checkGlError();
        
        client.smartCull = true;
    }
    
    /**
     * In 1.21.1 it recomputed the fog by FogRenderer.setupFog() and FogRenderer.levelFogColor().
     * Since 1.21.2 the fog is computed once per world rendering and passed to the render passes.
     * After rendering portal content, the shader fog becomes the fog of portal content.
     * It needs to be reset to the fog of the outer world.
     *
     * @param outerFog the fog uniform buffer that vanilla computed for the world rendering that's going on
     */
    public static void resetFogState(GpuBufferSlice outerFog) {
        RenderSystem.setShaderFog(outerFog);
    }

    /**
     * Since 1.21.2 vanilla does not store fog color in static fields.
     * This refreshes the fog color tracked by {@link FogRendererContext}.
     */
    public static void updateFogColor() {
        FogRendererContext.computeFogColor(
            client.gameRenderer.getMainCamera(), client.level
        );
    }

    /**
     * {@link net.minecraft.client.renderer.GameRenderer#setLevel}
     * Since 1.21.6 vanilla only updates the light directions of level when the client world changes.
     * When rendering portals the dimension that's being rendered changes.
     */
    @IPVanillaCopy
    public static void resetDiffuseLighting() {
        ClientLevel world = client.level;
        assert world != null;
        Lighting lighting = client.gameRenderer.getLighting();
        lighting.updateLevel(world.effects().constantAmbientLight());
        lighting.setupFor(Lighting.Entry.LEVEL);
    }


}
