package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.FogParameters;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.render.MyRenderHelper;

@Mixin(value = RenderSystem.class, remap = false)
public class MixinRenderSystem_Fog {
    // In 1.21.1 it modified the arguments of setShaderFogStart and setShaderFogEnd.
    // Since 1.21.2 the fog start and end are in FogParameters.
    @ModifyVariable(
        method = "setShaderFog", at = @At("HEAD"), argsOnly = true
    )
    private static FogParameters onSetShaderFog(FogParameters fog) {
        if (fog == FogParameters.NO_FOG) {
            return fog;
        }

        float newStart = MyRenderHelper.transformFogDistance(fog.start());
        float newEnd = MyRenderHelper.transformFogDistance(fog.end());

        if (newStart == fog.start() && newEnd == fog.end()) {
            return fog;
        }

        return new FogParameters(
            newStart, newEnd, fog.shape(),
            fog.red(), fog.green(), fog.blue(), fog.alpha()
        );
    }
}
