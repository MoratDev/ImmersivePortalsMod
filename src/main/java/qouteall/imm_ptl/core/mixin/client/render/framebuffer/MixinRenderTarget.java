package qouteall.imm_ptl.core.mixin.client.render.framebuffer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ducks.IEFrameBuffer;
import qouteall.imm_ptl.core.render.DepthStencilTextures;

import java.util.function.Supplier;

@Mixin(RenderTarget.class)
public abstract class MixinRenderTarget implements IEFrameBuffer {

    @Unique
    private boolean isStencilBufferEnabled = false;

    @Shadow
    public int width;
    @Shadow
    public int height;

    @Shadow
    protected @Nullable GpuTexture colorTexture;

    @Shadow
    protected @Nullable GpuTexture depthTexture;

    @Shadow
    public abstract void resize(int width, int height);

    // Before 1.21.5 it modified the arguments of glTexImage2D and glFramebufferTexture2D here.
    // Now the texture is created by the GPU device. See DepthStencilTextures
    @WrapOperation(
        method = "createBuffers",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/TextureFormat;III)Lcom/mojang/blaze3d/textures/GpuTexture;",
            remap = false
        )
    )
    private GpuTexture wrapCreateTexture(
        GpuDevice device, Supplier<String> label, TextureFormat format,
        int width, int height, int mipLevels,
        Operation<GpuTexture> original
    ) {
        if (isStencilBufferEnabled && format == TextureFormat.DEPTH32) {
            return DepthStencilTextures.createDepthStencilTexture(
                () -> original.call(device, label, format, width, height, mipLevels)
            );
        }

        return original.call(device, label, format, width, height, mipLevels);
    }

    /**
     * Vanilla copies the depth by blitting the framebuffer, which requires the same depth format.
     * Before 1.21.5 the result was just not checked.
     * Now vanilla throws exception when the blit gives an OpenGL error.
     * <p>
     * This is used by the render targets of fabulous graphics, which copy the depth from the main target.
     * When they copy the depth, they have only been cleared.
     * So make this render target to have the same depth format as the source.
     */
    @Inject(
        method = "copyDepthFrom",
        at = @At("HEAD")
    )
    private void onCopyDepthFrom(RenderTarget otherTarget, CallbackInfo ci) {
        boolean otherHasStencil = DepthStencilTextures.hasStencil(otherTarget.getDepthTexture());
        boolean thisHasStencil = DepthStencilTextures.hasStencil(depthTexture);

        if (depthTexture != null && otherTarget.getDepthTexture() != null
            && otherHasStencil != thisHasStencil
        ) {
            isStencilBufferEnabled = otherHasStencil;
            resize(width, height);

            if (colorTexture != null) {
                // it was cleared with transparent black
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(colorTexture, 0);
            }
        }
    }

    @Inject(
        method = "copyDepthFrom",
        at = @At("RETURN")
    )
    private void onCopiedDepthFrom(RenderTarget framebuffer, CallbackInfo ci) {
        CHelper.checkGlError();
    }

    @Override
    public boolean ip_getIsStencilBufferEnabled() {
        return isStencilBufferEnabled;
    }

    @Override
    public void ip_setIsStencilBufferEnabledAndReload(boolean cond) {
        if (isStencilBufferEnabled != cond) {
            isStencilBufferEnabled = cond;
            resize(width, height);
        }
    }
}
