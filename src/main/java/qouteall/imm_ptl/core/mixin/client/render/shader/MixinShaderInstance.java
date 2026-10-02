package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.CompiledShaderProgram;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.util.List;

@Mixin(CompiledShaderProgram.class)
public abstract class MixinShaderInstance implements IEShader {

    @Shadow
    @Final
    private int programId;

    @Shadow
    @Final
    private List<Uniform> uniforms;

    @Unique
    private @Nullable Uniform ip_clippingEquation;

    /**
     * In 1.21.1 the uniform was added by shader name
     * ({@link qouteall.imm_ptl.core.render.ShaderCodeTransformation} tells whether the shader is transformed).
     * Since 1.21.2 one shader source is shared by many shader programs
     * (for example core/terrain and core/entity),
     * so just check whether the linked program has the uniform.
     */
    @Inject(
        method = "setupUniforms",
        at = @At("RETURN")
    )
    private void onSetupUniforms(CallbackInfo ci) {
        int location = Uniform.glGetUniformLocation(programId, "iportal_ClippingEquation");
        if (location != -1) {
            ip_clippingEquation = new Uniform("iportal_ClippingEquation", Uniform.UT_FLOAT4, 4);
            ip_clippingEquation.setLocation(location);
            // not clipping by default
            ip_clippingEquation.set(0f, 0f, 0f, 1f);
            // make it uploaded in CompiledShaderProgram#apply
            uniforms.add(ip_clippingEquation);
        }
    }

    @Nullable
    @Override
    public Uniform ip_getClippingEquationUniform() {
        return ip_clippingEquation;
    }
}
