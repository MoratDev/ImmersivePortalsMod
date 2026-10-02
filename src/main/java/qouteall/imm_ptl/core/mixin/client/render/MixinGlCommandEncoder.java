package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import qouteall.imm_ptl.core.render.IPRenderPipelines;

@Mixin(GlCommandEncoder.class)
public class MixinGlCommandEncoder {

    /**
     * Vanilla's depth test functions don't include "always pass".
     * It's needed for overwriting the depth of portal area.
     * (Disabling depth test also disables writing depth.)
     */
    @ModifyArg(
        method = "applyPipelineState",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_depthFunc(I)V",
            remap = false
        )
    )
    private int modifyDepthFunc(int depthFunc, @Local(argsOnly = true) RenderPipeline pipeline) {
        if (IPRenderPipelines.isDepthAlwaysPipeline(pipeline)) {
            return GL11.GL_ALWAYS;
        }
        return depthFunc;
    }
}
