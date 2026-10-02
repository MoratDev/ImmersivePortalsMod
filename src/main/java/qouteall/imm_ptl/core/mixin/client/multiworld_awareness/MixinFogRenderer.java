package qouteall.imm_ptl.core.mixin.client.multiworld_awareness;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Unique;
import org.joml.Vector4f;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;

@Mixin(value = FogRenderer.class, priority = 1100)
public class MixinFogRenderer {
    // In 1.21.1 these 3 were vanilla static fields.
    // Since 1.21.2 the fog color is returned from computeFogColor and passed around,
    // so track the last computed fog color here.
    @Unique
    private static float ip_fogRed;
    @Unique
    private static float ip_fogGreen;
    @Unique
    private static float ip_fogBlue;

    @Shadow
    private static int targetBiomeFog = -1;
    @Shadow
    private static int previousBiomeFog = -1;
    @Shadow
    private static long biomeChangedTime = -1L;

    @Inject(method = "computeFogColor", at = @At("RETURN"))
    private static void onComputeFogColor(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount,
        CallbackInfoReturnable<Vector4f> cir
    ) {
        Vector4f fogColor = cir.getReturnValue();
        ip_fogRed = fogColor.x;
        ip_fogGreen = fogColor.y;
        ip_fogBlue = fogColor.z;
    }

    static {
        FogRendererContext.copyContextFromObject = context -> {
            ip_fogRed = context.red;
            ip_fogGreen = context.green;
            ip_fogBlue = context.blue;
            targetBiomeFog = context.targetBiomeFog;
            previousBiomeFog = context.previousBiomeFog;
            biomeChangedTime = context.biomeChangedTime;
        };

        FogRendererContext.copyContextToObject = context -> {
            context.red = ip_fogRed;
            context.green = ip_fogGreen;
            context.blue = ip_fogBlue;
            context.targetBiomeFog = targetBiomeFog;
            context.previousBiomeFog = previousBiomeFog;
            context.biomeChangedTime = biomeChangedTime;
        };

        FogRendererContext.getCurrentFogColor =
            () -> new Vec3(ip_fogRed, ip_fogGreen, ip_fogBlue);

        FogRendererContext.init();
    }
}
