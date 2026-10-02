package qouteall.imm_ptl.core.mixin.client.render.framebuffer;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IEGlTexture;
import qouteall.imm_ptl.core.render.DepthStencilTextures;

@Mixin(GlTexture.class)
public class MixinGlTexture implements IEGlTexture {
    @Unique
    private boolean ip_hasStencil = false;

    @Unique
    private final IntOpenHashSet ip_fbosWithStencilAttached = new IntOpenHashSet();

    /**
     * This is the color texture. It caches the framebuffer objects by the depth texture.
     * Vanilla only attaches the depth texture as depth.
     * If the depth texture is a depth-stencil texture, also attach it as stencil.
     * (Before 1.21.5 the texture was attached as GL_DEPTH_STENCIL_ATTACHMENT. Attaching the same
     * texture as depth attachment and as stencil attachment is equivalent.)
     */
    @Inject(
        method = "getFbo",
        at = @At("RETURN")
    )
    private void onGetFbo(
        DirectStateAccess directStateAccess, @Nullable GpuTexture depthTexture,
        CallbackInfoReturnable<Integer> cir
    ) {
        if (!DepthStencilTextures.hasStencil(depthTexture)) {
            return;
        }

        int fbo = cir.getReturnValue();

        if (!ip_fbosWithStencilAttached.add(fbo)) {
            return;
        }

        int oldReadFbo = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        int oldDrawFbo = GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
            GL11.GL_TEXTURE_2D, ((GlTexture) depthTexture).glId(), 0
        );

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDrawFbo);
    }

    @Override
    public boolean ip_hasStencil() {
        return ip_hasStencil;
    }

    @Override
    public void ip_setHasStencil(boolean cond) {
        ip_hasStencil = cond;
    }
}
