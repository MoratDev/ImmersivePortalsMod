package qouteall.imm_ptl.core.mixin.common;

import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class MixinLivingEntity {
    // setLastHurtByPlayer doesn't accept null since 1.21.5
    @Shadow
    protected EntityReference<Player> lastHurtByPlayer;
    
    @Inject(method = "Lnet/minecraft/world/entity/LivingEntity;tick()V", at = @At("RETURN"))
    private void onTickEnded(CallbackInfo ci) {
        LivingEntity this_ = (LivingEntity) (Object) this;
        if (this_.getLastHurtByMob() != null) {
            if (this_.getLastHurtByMob().level() != this_.level()) {
            	this_.setLastHurtByMob(null);
            }
        }
        if (this_.getLastHurtMob() != null) {
            if (this_.getLastHurtMob().level() != this_.level()) {
            	lastHurtByPlayer = null;
            }
        }
    }
}
