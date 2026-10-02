package qouteall.imm_ptl.core.compat.mixin.sodium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.Helper;

@Mixin(value = RenderRegion.class, remap = false)
public class MixinSodiumRenderRegion {
    @Shadow
    @Final
    private ChunkRenderList renderList;
    
    @Unique
    private @Nullable ObjectArrayList<ChunkRenderList> chunkRenderListsForPortalRendering = null;
    
    /**
     * @author qouteall
     * @reason With ImmPtl, the world rendering process is as follows:
     * 1. render solid things
     * 2. render portal recursively (will increase frame counter)
     * 3. render transparent things
     * When rendering the world in portal (to-same-world portal),
     * the frame counter increases, then in
     * {@link SortedRenderLists.Builder#add(RenderSection)} it will reset the ChunkRenderList,
     * which makes upcoming transparent block rendering in outer world to break.
     * So use separate ChunkRenderList for each portal rendering layer.
     */
    @Overwrite
    public ChunkRenderList getRenderList() {
        if (!PortalRendering.isRendering()) {
            return renderList;
        }
        
        RenderRegion this_ = (RenderRegion) (Object) this;
        
        if (chunkRenderListsForPortalRendering == null) {
            chunkRenderListsForPortalRendering = new ObjectArrayList<>();
        }
        
        int layer = PortalRendering.getPortalLayer();
        int index = layer - 1;
        ChunkRenderList result = Helper.arrayListComputeIfAbsent(
            chunkRenderListsForPortalRendering,
            index,
            () -> new ChunkRenderList(this_)
        );
        
        return result;
    }

    /**
     * Since Sodium 0.7 the render region caches the draw batch of every terrain render pass.
     * The cached batches are filled from the render list.
     * As the render lists are separate for each portal rendering layer,
     * the cached batches also need to be separate.
     */
    @Shadow
    @Final
    private Map<TerrainRenderPass, MultiDrawBatch> cachedBatches;

    @Unique
    private @Nullable ObjectArrayList<Map<TerrainRenderPass, MultiDrawBatch>> ip_cachedBatchesForPortalRendering = null;

    @Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true)
    private void onGetCachedBatch(TerrainRenderPass pass, CallbackInfoReturnable<MultiDrawBatch> cir) {
        if (!PortalRendering.isRendering()) {
            return;
        }

        if (ip_cachedBatchesForPortalRendering == null) {
            ip_cachedBatchesForPortalRendering = new ObjectArrayList<>();
        }

        int index = PortalRendering.getPortalLayer() - 1;
        Map<TerrainRenderPass, MultiDrawBatch> batches = Helper.arrayListComputeIfAbsent(
            ip_cachedBatchesForPortalRendering,
            index,
            Reference2ReferenceOpenHashMap::new
        );

        MultiDrawBatch batch = batches.get(pass);
        if (batch == null) {
            // the same as RenderRegion#getCachedBatch
            batch = new MultiDrawBatch(ModelQuadFacing.COUNT * 256 + 1);
            batches.put(pass, batch);
        }
        cir.setReturnValue(batch);
    }

    @Inject(method = "clearAllCachedBatches", at = @At("RETURN"))
    private void onClearAllCachedBatches(CallbackInfo ci) {
        if (ip_cachedBatchesForPortalRendering != null) {
            for (Map<TerrainRenderPass, MultiDrawBatch> batches : ip_cachedBatchesForPortalRendering) {
                // the list has null elements for the portal layers that have not been rendered
                if (batches == null) {
                    continue;
                }
                for (MultiDrawBatch batch : batches.values()) {
                    batch.clear();
                }
            }
        }
    }

    @Inject(method = "clearCachedBatchFor", at = @At("RETURN"))
    private void onClearCachedBatchFor(TerrainRenderPass pass, CallbackInfo ci) {
        if (ip_cachedBatchesForPortalRendering != null) {
            for (Map<TerrainRenderPass, MultiDrawBatch> batches : ip_cachedBatchesForPortalRendering) {
                if (batches == null) {
                    continue;
                }
                MultiDrawBatch batch = batches.get(pass);
                if (batch != null) {
                    batch.clear();
                }
            }
        }
    }

    @Inject(method = "delete", at = @At("RETURN"))
    private void onDelete(CommandList commandList, CallbackInfo ci) {
        if (ip_cachedBatchesForPortalRendering != null) {
            for (Map<TerrainRenderPass, MultiDrawBatch> batches : ip_cachedBatchesForPortalRendering) {
                // the list has null elements for the portal layers that have not been rendered
                if (batches == null) {
                    continue;
                }
                for (MultiDrawBatch batch : batches.values()) {
                    batch.delete();
                }
            }
            ip_cachedBatchesForPortalRendering = null;
        }
    }
}
