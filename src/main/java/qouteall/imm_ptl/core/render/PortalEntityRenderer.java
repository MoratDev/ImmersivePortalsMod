package qouteall.imm_ptl.core.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.mc_utils.WireRenderingHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Environment(EnvType.CLIENT)
public class PortalEntityRenderer extends EntityRenderer<Portal, PortalEntityRenderer.PortalRenderState> {

    /**
     * Since 1.21.2 the entity renderer renders a render state instead of the entity.
     * The portal rendering needs the portal entity itself, so the render state refers to the portal.
     * The render state is extracted right before rendering, on the render thread.
     */
    public static class PortalRenderState extends EntityRenderState {
        public Portal portal;
    }

    public PortalEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public PortalRenderState createRenderState() {
        return new PortalRenderState();
    }

    @Override
    public void extractRenderState(Portal portal, PortalRenderState renderState, float partialTick) {
        super.extractRenderState(portal, renderState, partialTick);
        renderState.portal = portal;
    }

    @Override
    public void render(
        PortalRenderState renderState,
        PoseStack matrixStack,
        MultiBufferSource bufferSource,
        int light
    ) {
        Portal portal = renderState.portal;
        
        IPCGlobal.renderer.renderPortalInEntityRenderer(portal);
        
        if (OverlayRendering.shouldRenderOverlay(portal)) {
            OverlayRendering.onRenderPortalEntity(portal, matrixStack, bufferSource);
        }
    
        if (IPGlobal.debugRenderPortalShapeMesh && !PortalRendering.isRendering()) {
            VertexConsumer lineVertexConsumer = bufferSource.getBuffer(RenderType.lines());
            WireRenderingHelper.renderPortalShapeMeshDebug(
                matrixStack, lineVertexConsumer, portal
            );
        }
        
        super.render(renderState, matrixStack, bufferSource, light);
    }

    
}
