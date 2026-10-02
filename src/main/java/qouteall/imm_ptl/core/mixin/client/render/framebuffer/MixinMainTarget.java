package qouteall.imm_ptl.core.mixin.client.render.framebuffer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ducks.IEFrameBuffer;
import qouteall.imm_ptl.core.render.DepthStencilTextures;

import java.util.function.Supplier;

@Mixin(MainTarget.class)
public abstract class MixinMainTarget {

    // Before 1.21.5 it modified the arguments of glTexImage2D and glFramebufferTexture2D here.
    // Now the texture is created by the GPU device. See DepthStencilTextures
    // Note: the main target only uses this when it's constructed.
    // When it's resized, RenderTarget#createBuffers is used.
    @WrapOperation(
        method = "allocateDepthAttachment",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            remap = false
        )
    )
    private GpuTexture wrapCreateDepthTexture(
        GpuDevice device, Supplier<String> label, int usage, TextureFormat format,
        int width, int height, int depthOrLayers, int mipLevels,
        Operation<GpuTexture> original
    ) {
        boolean isStencilBufferEnabled = ((IEFrameBuffer) this).ip_getIsStencilBufferEnabled();

        if (isStencilBufferEnabled) {
            return DepthStencilTextures.createDepthStencilTexture(
                () -> original.call(device, label, usage, format, width, height, depthOrLayers, mipLevels)
            );
        }

        return original.call(device, label, usage, format, width, height, depthOrLayers, mipLevels);
    }
}
