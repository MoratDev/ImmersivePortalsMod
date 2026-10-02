package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.util.function.BiFunction;

/**
 * Before 1.21.5 the shader code was transformed in CompiledShader.compile.
 */
@Mixin(GlDevice.class)
public class MixinGlDevice_Shader {

    // the function takes the shader id and shader type and gives the shader source
    @WrapOperation(
        method = "compileShader",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            remap = false
        )
    )
    private Object wrapGetShaderSource(
        BiFunction<Object, Object, Object> shaderSource, Object id, Object type,
        Operation<Object> original
    ) {
        Object source = original.call(shaderSource, id, type);

        if (source instanceof String code
            && id instanceof ResourceLocation resourceLocation
            && type instanceof ShaderType shaderType
        ) {
            return ShaderCodeTransformation.transform(shaderType, resourceLocation.toString(), code);
        }

        return source;
    }
}
