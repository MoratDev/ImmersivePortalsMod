package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ducks.IEGlTexture;

import java.util.function.Supplier;

/**
 * Since 1.21.5 the textures of {@link RenderTarget} are created by the GPU device
 * and vanilla only has a depth format without stencil.
 * <p>
 * When a render target has stencil enabled
 * (see {@link qouteall.imm_ptl.core.ducks.IEFrameBuffer}),
 * its depth texture is created as a depth-stencil texture,
 * and the framebuffer objects that use this depth texture also attach it as stencil.
 * <p>
 * See MixinRenderTarget, MixinMainTarget, MixinGlDevice_Stencil and MixinGlTexture.
 */
public class DepthStencilTextures {
    private static boolean isCreatingDepthStencilTexture = false;

    /**
     * @return Whether the texture that's being created should be a depth-stencil texture.
     */
    public static boolean isCreatingDepthStencilTexture() {
        return isCreatingDepthStencilTexture;
    }

    /**
     * The depth texture created by the supplier will be a depth-stencil texture.
     */
    public static <T> T createDepthStencilTexture(Supplier<T> textureCreation) {
        Validate.isTrue(!isCreatingDepthStencilTexture);
        isCreatingDepthStencilTexture = true;
        try {
            return textureCreation.get();
        }
        finally {
            isCreatingDepthStencilTexture = false;
        }
    }

    public static boolean hasStencil(@Nullable GpuTexture texture) {
        return texture instanceof GlTexture && ((IEGlTexture) texture).ip_hasStencil();
    }
}
