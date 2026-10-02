package qouteall.imm_ptl.core.mixin.client.multiworld_awareness;

import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;

/**
 * Before 1.21.6 these static fields were in FogRenderer.
 */
@Mixin(WaterFogEnvironment.class)
public class MixinWaterFogEnvironment {
    @Shadow
    private static int targetBiomeFog = -1;
    @Shadow
    private static int previousBiomeFog = -1;
    @Shadow
    private static long biomeChangedTime = -1L;

    static {
        FogRendererContext.copyContextFromObject = context -> {
            FogRendererContext.currentFogRed = context.red;
            FogRendererContext.currentFogGreen = context.green;
            FogRendererContext.currentFogBlue = context.blue;
            targetBiomeFog = context.targetBiomeFog;
            previousBiomeFog = context.previousBiomeFog;
            biomeChangedTime = context.biomeChangedTime;
        };

        FogRendererContext.copyContextToObject = context -> {
            context.red = FogRendererContext.currentFogRed;
            context.green = FogRendererContext.currentFogGreen;
            context.blue = FogRendererContext.currentFogBlue;
            context.targetBiomeFog = targetBiomeFog;
            context.previousBiomeFog = previousBiomeFog;
            context.biomeChangedTime = biomeChangedTime;
        };

        FogRendererContext.getCurrentFogColor = () -> new Vec3(
            FogRendererContext.currentFogRed,
            FogRendererContext.currentFogGreen,
            FogRendererContext.currentFogBlue
        );

        FogRendererContext.init();
    }
}
