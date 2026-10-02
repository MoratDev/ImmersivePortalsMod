package qouteall.imm_ptl.core.render.renderer;

import qouteall.imm_ptl.core.render.IPRenderPipelines;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11.GL_EQUAL;
import static org.lwjgl.opengl.GL11.GL_INCR;
import static org.lwjgl.opengl.GL11.GL_KEEP;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_REPLACE;
import static org.lwjgl.opengl.GL11.GL_STENCIL_TEST;

public class RendererUsingStencil extends PortalRenderer {
    
    
    @Override
    public boolean replaceFrameBufferClearing() {
        boolean skipClearing = WorldRenderInfo.isRendering();
        if (skipClearing) {
            if (WorldRenderInfo.getTopRenderInfo().doRenderSky) {
                // does not write depth
                MyRenderHelper.renderScreenTriangle(
                    FogRendererContext.getCurrentFogColor.get(),
                    true, false, IPRenderPipelines.DepthMode.LEQUAL
                );
            }
        }
        return skipClearing;
    }
    
    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        doPortalRendering(modelView);
    }
    
    protected void doPortalRendering(Matrix4f modelView) {
        // NOTE Since 1.21.5 the depth test, depth mask, color mask, blending and face culling
        // are specified by render pipelines. See IPRenderPipelines.
        // Do not directly change them with OpenGL, because GlStateManager caches these states.
        // The stencil states are not managed by vanilla.

        Profiler.get().popPush("render_portal_total");
        renderPortals(modelView);
        if (PortalRendering.isRendering()) {
            setStencilStateForWorldRendering();
        }
        else {
            // don't do it in finishRendering()
            // as it will render outer world's transparent things later
            myFinishRendering();
        }
    }
    
    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);
        
        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
    
    @Override
    public void onHandRenderingEnded() {
        //nothing
    }
    
    @Override
    public void prepareRendering() {
        if (!IPPortingLibCompat.getIsStencilEnabled(client.getMainRenderTarget())) {
            IPPortingLibCompat.setIsStencilEnabled(client.getMainRenderTarget(), true);
            
            if (Minecraft.useShaderTransparency()) {
//                client.worldRenderer.reload();
            }
        }
        
        MyRenderHelper.bindWrite(client.getMainRenderTarget());

        MyRenderHelper.clearStencil(client.getMainRenderTarget());

        GL11.glEnable(GL_STENCIL_TEST);
        
    }
    
    @Override
    public void finishRendering() {
        //nothing
    }
    
    private void myFinishRendering() {
        GL11.glStencilFunc(GL_ALWAYS, 2333, 0xFF);
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        
        GL11.glDisable(GL_STENCIL_TEST);
    }
    
    protected void doRenderPortal(
        Portal portal,
        Matrix4f modelView
    ) {
        if (shouldSkipRenderingInsideFuseViewPortal(portal)) {
            return;
        }
        
        int outerPortalStencilValue = PortalRendering.getPortalLayer();
        
        Profiler.get().push("render_view_area");
        
        boolean anySamplePassed = PortalRenderInfo.renderAndDecideVisibility(portal, () -> {
            renderPortalViewAreaToStencil(portal, modelView);
        });
        
        Profiler.get().pop();
        
        if (!anySamplePassed) {
            setStencilStateForWorldRendering();
            return;
        }
        
        PortalRendering.pushPortalLayer(portal);
        
        int thisPortalStencilValue = outerPortalStencilValue + 1;
        
        if (!portal.isFuseView()) {
            Profiler.get().push("clear_depth_of_view_area");
            clearDepthOfThePortalViewArea(portal);
            Profiler.get().pop();
        }
        
        setStencilStateForWorldRendering();
        
        renderPortalContent(portal);
        
        PortalRendering.popPortalLayer();
        // pop portal layer before restoring depth, for clipping, see ViewAreaRenderer
        
        if (!portal.isFuseView()) {
            restoreDepthOfPortalViewArea(portal, modelView, thisPortalStencilValue);
        }
        
        clampStencilValue(outerPortalStencilValue);
    }
    
    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
        //nothing
    }
    
    private void renderPortalViewAreaToStencil(
        Portal portal, Matrix4f modelView
    ) {
        int outerPortalStencilValue = PortalRendering.getPortalLayer();
        
        //is the mask here different from the mask of glStencilMask?
        GL11.glStencilFunc(GL_EQUAL, outerPortalStencilValue, 0xFF);
        
        //if stencil and depth test pass, the data in stencil buffer will increase by 1
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        //NOTE about GL_INCR:
        //if multiple triangles occupy the same pixel and passed stencil and depth tests,
        //its stencil value will still increase by one
        
        GL11.glStencilMask(0xFF);
        
        // update it before pushing
        FrontClipping.updateInnerClipping(modelView);
        
        ViewAreaRenderer.renderPortalArea(
            portal, Vec3.ZERO,
            modelView,
            RenderSystem.getProjectionMatrix(),
            true, true,
            true, true
        );
    }
    
    private void clearDepthOfThePortalViewArea(
        Portal portal
    ) {
        setStencilStateForWorldRendering();

        //the pixel's depth will be 1, which is the furthest
        GL11.glDepthRange(1, 1);

        //do not manipulate color buffer
        //always passes depth test
        MyRenderHelper.renderScreenTriangle(
            255, 255, 255, 255,
            false, true, IPRenderPipelines.DepthMode.ALWAYS
        );

        //retrieve the state
        GL11.glDepthRange(0, 1);
    }
    
    protected void restoreDepthOfPortalViewArea(
        Portal portal, Matrix4f modelView,
        int portalStencilValue
    ) {
        setStencilLimitation(portalStencilValue);
        
        // always passes depth test
        ViewAreaRenderer.renderPortalArea(
            portal, Vec3.ZERO,
            modelView,
            RenderSystem.getProjectionMatrix(),
            false, false,
            true,
            true, // important: should clip, otherwise depth will be abnormal when viewing scale box from inside in portal
            IPRenderPipelines.DepthMode.ALWAYS
        );
    }
    
    public static void clampStencilValue(
        int maximumValue
    ) {
        //NOTE GL_GREATER means ref > stencil
        //GL_LESS means ref < stencil
        
        //pass if the stencil value is greater than the maximum value
        GL11.glStencilFunc(GL_LESS, maximumValue, 0xFF);
        
        //if stencil test passed, encode the stencil value
        GL11.glStencilOp(GL_KEEP, GL_REPLACE, GL_REPLACE);
        
        //do not manipulate the depth buffer
        //do not manipulate the color buffer
        //no depth test
        MyRenderHelper.renderScreenTriangle(
            255, 255, 255, 255,
            false, false, IPRenderPipelines.DepthMode.DISABLED
        );
    }
    
    private void setStencilStateForWorldRendering() {
        int thisPortalStencilValue = PortalRendering.getPortalLayer();
        
        setStencilLimitation(thisPortalStencilValue);
    }
    
    public static void setStencilLimitation(int stencilValue) {
        //draw content in the mask
        GL11.glStencilFunc(GL_EQUAL, stencilValue, 0xFF);
        
        //do not manipulate stencil buffer now
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    }
    
    public static boolean shouldSkipRenderingInsideFuseViewPortal(Portal portal) {
        if (!PortalRendering.isRendering()) {
            return false;
        }
        
        Portal renderingPortal = PortalRendering.getRenderingPortal();
        
        if (!renderingPortal.isFuseView()) {
            return false;
        }
        
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        
        Vec3 transformedCameraPos = portal
            .transformPoint(renderingPortal.transformPoint(cameraPos));
        
        // roughly test whether they are reverse portals
        return cameraPos.distanceToSqr(transformedCameraPos) < 0.1;
    }
}
