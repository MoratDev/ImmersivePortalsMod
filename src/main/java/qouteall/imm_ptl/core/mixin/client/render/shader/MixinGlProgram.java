package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEShader;

/**
 * Before 1.21.5 this was a mixin of CompiledShaderProgram.
 * <p>
 * Since 1.21.6 vanilla only manages uniform blocks, texel buffers and samplers.
 * The clipping equation is still a plain uniform. Its location is kept here
 * and it's uploaded by directly calling OpenGL in MixinGlCommandEncoder.
 */
@Mixin(GlProgram.class)
public abstract class MixinGlProgram implements IEShader {
    @Unique
    private static final String IP_CLIPPING_EQUATION = "iportal_ClippingEquation";

    @Shadow
    @Final
    private int programId;

    @Unique
    private int ip_clippingEquationLocation = -1;

    /**
     * One shader source is shared by many shader programs
     * (for example core/terrain and core/entity),
     * so just check whether the linked program has the uniform
     * ({@link qouteall.imm_ptl.core.render.ShaderCodeTransformation} adds it into the shader code).
     */
    @Inject(
        method = "setupUniforms",
        at = @At("RETURN")
    )
    private void onSetupUniforms(CallbackInfo ci) {
        ip_clippingEquationLocation =
            GlStateManager._glGetUniformLocation(programId, IP_CLIPPING_EQUATION);
    }

    @Override
    public int ip_getClippingEquationLocation() {
        return ip_clippingEquationLocation;
    }
}
