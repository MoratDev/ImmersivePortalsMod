package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.shaders.UniformType;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.FrontClipping;

import java.util.List;
import java.util.Map;

/**
 * Before 1.21.5 this was a mixin of CompiledShaderProgram.
 */
@Mixin(GlProgram.class)
public abstract class MixinGlProgram implements IEShader {
    @Unique
    private static final String IP_CLIPPING_EQUATION = "iportal_ClippingEquation";

    @Shadow
    @Final
    private int programId;

    @Shadow
    @Final
    private List<Uniform> uniforms;

    @Shadow
    @Final
    private Map<String, Uniform> uniformsByName;

    @Unique
    private @Nullable Uniform ip_clippingEquation;

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
        int location = Uniform.glGetUniformLocation(programId, IP_CLIPPING_EQUATION);
        if (location != -1) {
            // Vanilla also registers the uniforms that are not declared in the pipeline,
            // but it uses the uniform index as the location.
            Uniform uniform = uniformsByName.get(IP_CLIPPING_EQUATION);
            if (uniform == null) {
                uniform = new Uniform(IP_CLIPPING_EQUATION, UniformType.VEC4);
                // make it uploaded when drawing
                uniforms.add(uniform);
                uniformsByName.put(IP_CLIPPING_EQUATION, uniform);
            }
            uniform.setLocation(location);
            // not clipping by default
            uniform.set(0f, 0f, 0f, 1f);
            ip_clippingEquation = uniform;
        }
    }

    /**
     * It's called before every draw, before uploading the uniforms.
     * Before 1.21.5 the clipping equation was updated in RenderSystem.setShader.
     */
    @Inject(
        method = "setDefaultUniforms",
        at = @At("RETURN")
    )
    private void onSetDefaultUniforms(CallbackInfo ci) {
        if (ip_clippingEquation != null) {
            FrontClipping.loadClippingEquation(ip_clippingEquation);
        }
    }

    @Nullable
    @Override
    public Uniform ip_getClippingEquationUniform() {
        return ip_clippingEquation;
    }
}
