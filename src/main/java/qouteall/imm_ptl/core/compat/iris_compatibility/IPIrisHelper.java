package qouteall.imm_ptl.core.compat.iris_compatibility;

import qouteall.imm_ptl.core.render.MyRenderHelper;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT;

public class IPIrisHelper {
    
    public static void copyDepthStencil(
        RenderTarget from, RenderTarget to,
        boolean copyDepth, boolean copyStencil
    ) {
        int mask = 0;
        
        if (copyDepth) {
            if (copyStencil) {
                mask = GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT;
            }
            else {
                mask = GL_DEPTH_BUFFER_BIT;
            }
        }
        else {
            if (copyStencil) {
                mask = GL_STENCIL_BUFFER_BIT;
            }
            else {
                throw new RuntimeException();
            }
        }
        
        blitFramebuffer(from, to, from.width, from.height, to.width, to.height, mask);
    }
    
    /**
     * Since 1.21.5 the render target doesn't own a framebuffer object.
     * It uses the framebuffer objects that vanilla uses for drawing to the render target.
     * The binding goes through GlStateManager because it caches the bound framebuffers.
     */
    public static void blitFramebuffer(
        RenderTarget from, RenderTarget to,
        int fromWidth, int fromHeight, int toWidth, int toHeight,
        int mask
    ) {
        GlStateManager._disableScissorTest();
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, MyRenderHelper.getFramebufferId(from));
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, MyRenderHelper.getFramebufferId(to));

        GL30.glBlitFramebuffer(
            0, 0, fromWidth, fromHeight,
            0, 0, toWidth, toHeight,
            mask, GL_NEAREST
        );

        MyRenderHelper.unbindGlFramebuffer();
    }

    private static boolean isCopyImageSubDataSupported() {
        return GL.getCapabilities().glCopyImageSubData != 0;
    }
    
    public static void newCopyDepthStencil(
        RenderTarget from, RenderTarget to
    ) {
        GL43C.glCopyImageSubData(
            MyRenderHelper.getDepthTextureId(from),
            GL43C.GL_TEXTURE_2D,
            0,
            0,
            0,
            0,
            MyRenderHelper.getDepthTextureId(to),
            GL43C.GL_TEXTURE_2D,
            0,
            0,
            0,
            0,
            from.width,
            from.height,
            1
        );
    }
    
    public static void copyColor(
        RenderTarget from, RenderTarget to
    ) {
        GL43C.glCopyImageSubData(
            MyRenderHelper.getColorTextureId(from),
            GL43C.GL_TEXTURE_2D,
            0,
            0,
            0,
            0,
            MyRenderHelper.getColorTextureId(to),
            GL43C.GL_TEXTURE_2D,
            0,
            0,
            0,
            0,
            from.width,
            from.height,
            1
        );
    }
    
}
