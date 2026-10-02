package qouteall.imm_ptl.core.platform_specific.mixin.common;

import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPPerServerInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.mc_utils.ServerTaskList;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGenManager;

@Mixin(ServerPlayer.class)
public class MixinServerPlayerEntity_MA {
    // update chunk visibility data
    // In 1.21.1 it injected both changeDimension(DimensionTransition)
    // and teleportTo(ServerLevel, double, double, double, float, float).
    // Since 1.21.2 both of them go through teleport(TeleportTransition)
    // (same-dimension teleport also goes through it).
    @Inject(
        method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        at = @At("HEAD")
    )
    private void onTeleportedByVanilla(
        TeleportTransition teleportTransition, CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ServerPlayer this_ = (ServerPlayer) (Object) this;

        if (this_.isRemoved()) {
            // vanilla does nothing in this case
            return;
        }

        if (this_.level() != teleportTransition.newLevel()) {
            onBeforeDimensionTravel(this_);
        }
    }
    
    private static void onBeforeDimensionTravel(ServerPlayer player) {
        CustomPortalGenManager customPortalGenManager =
            IPPerServerInfo.of(player.getServer()).customPortalGenManager;
        
        if (customPortalGenManager != null) {
            customPortalGenManager.onBeforeConventionalDimensionChange(player);
            ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);
            
            ServerTaskList.of(player.getServer()).addTask(() -> {
                customPortalGenManager.onAfterConventionalDimensionChange(player);
                return true;
            });
        }
    }
}
