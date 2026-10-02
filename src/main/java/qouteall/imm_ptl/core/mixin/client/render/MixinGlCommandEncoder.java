package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.lwjgl.opengl.GL11;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.IPRenderPipelines;

@Mixin(GlCommandEncoder.class)
public class MixinGlCommandEncoder {
    @Shadow
    @Nullable
    private GlProgram lastProgram;

    /**
     * It's called before every draw. When it returns true the shader program is bound.
     * Before 1.21.6 the clipping equation was updated in GlProgram#setDefaultUniforms.
     */
    @Inject(
        method = "trySetup",
        at = @At("RETURN")
    )
    private void onTrySetup(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && lastProgram != null) {
            int location = ((IEShader) lastProgram).ip_getClippingEquationLocation();
            if (location != -1) {
                FrontClipping.loadClippingEquation(location);
            }
        }
    }

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
