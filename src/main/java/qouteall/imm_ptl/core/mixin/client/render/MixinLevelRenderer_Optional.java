package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

// avoid crashing with sodium
// the overwrite has priority of 1000
@Mixin(value = LevelRenderer.class, priority = 1100)
public class MixinLevelRenderer_Optional {
    @Shadow
    private ViewArea viewArea;
    
    @Shadow
    @Final
    private Minecraft minecraft;
    
    //avoid translucent sort while rendering portal
    // In 1.21.1 the translucent sort was in renderSectionLayer.
    // In 1.21.2+ it's scheduled in this method, which is called from compileSections.
    @Inject(
        method = "scheduleTranslucentSectionResort",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void onScheduleTranslucentSectionResort(Vec3 cameraPosition, CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            ci.cancel();
        }
    }
    
    //the camera position is used for translucent sort
    //avoid messing it
    @Redirect(
        method = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;setCameraPosition(Lnet/minecraft/world/phys/Vec3;)V"
        ),
        require = 0
    )
    private void onSetChunkBuilderCameraPosition(
        SectionRenderDispatcher chunkBuilder, Vec3 cameraPosition
    ) {
        if (PortalRendering.isRendering()) {
            if (minecraft.level.dimension() == RenderStates.originalPlayerDimension) {
                return;
            }
        }
        chunkBuilder.setCameraPosition(cameraPosition);
    }
    
    // Before 1.21.5 there was an injection in renderSectionLayer that updates the clipping uniform.
    // Now the uniform is updated before every draw. See MixinGlProgram.

    // In 1.21.1 setupRender used the player position to update ViewArea
    // and it was redirected to the camera position here during portal rendering.
    // Since 1.21.2 vanilla uses the camera position, so the 3 redirects are not needed.
}
