package qouteall.imm_ptl.core.mixin.client.render.framebuffer;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.ducks.IEGlTexture;
import qouteall.imm_ptl.core.render.DepthStencilTextures;

import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH32F_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV;

/**
 * Before 1.21.5 the depth texture format was changed in MixinRenderTarget and MixinMainTarget.
 * Now the texture is created here.
 */
@Mixin(GlDevice.class)
public class MixinGlDevice_Stencil {

    // createTexture implements a method of a non-obfuscated interface
    @ModifyArgs(
        method = "createTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/TextureFormat;III)Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V"
        ),
        remap = false
    )
    private void modifyTexImage2D(Args args) {
        if (DepthStencilTextures.isCreatingDepthStencilTexture()) {
            args.set(2, IPCGlobal.useSeparatedStencilFormat ? GL_DEPTH32F_STENCIL8 : GL_DEPTH24_STENCIL8);
            args.set(6, ARBFramebufferObject.GL_DEPTH_STENCIL);
            args.set(7, IPCGlobal.useSeparatedStencilFormat ? GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL30C.GL_UNSIGNED_INT_24_8);
        }
    }

    @Inject(
        method = "createTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/TextureFormat;III)Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("RETURN"),
        remap = false
    )
    private void onTextureCreated(
        String label, TextureFormat format, int width, int height, int mipLevels,
        CallbackInfoReturnable<GpuTexture> cir
    ) {
        if (DepthStencilTextures.isCreatingDepthStencilTexture()) {
            ((IEGlTexture) cir.getReturnValue()).ip_setHasStencil(true);
        }
    }
}
