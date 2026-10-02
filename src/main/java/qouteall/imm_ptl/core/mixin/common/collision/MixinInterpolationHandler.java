package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Before 1.21.5 this was done by injecting into lerpTo of LivingEntity and AbstractMinecart.
 * Since 1.21.5 the entity position interpolation goes through {@link InterpolationHandler}.
 */
@Mixin(InterpolationHandler.class)
public abstract class MixinInterpolationHandler {
    @Shadow
    @Final
    private Entity entity;

    @Shadow
    public abstract void cancel();

    @Inject(
        method = "interpolateTo",
        at = @At("RETURN")
    )
    private void onInterpolateTo(Vec3 pos, float yRot, float xRot, CallbackInfo ci) {
        if (!entity.level().isClientSide()) {
            return;
        }

        if (!(entity instanceof LivingEntity) && !(entity instanceof AbstractMinecart)) {
            return;
        }

        // for debugging
        if (!IPGlobal.allowClientEntityPosInterpolation) {
            entity.setPos(pos);
            entity.setYRot(yRot);
            entity.setXRot(xRot);
            cancel();
            return;
        }

        if (!(entity instanceof LivingEntity)) {
            return;
        }

        // avoid entity position interpolate when crossing portal to the same dimension
        Portal collidingPortal = ((IEEntity) entity).ip_getCollidingPortal();
        if (collidingPortal != null) {
            if (entity.position().distanceToSqr(pos) > 4) {
                McHelper.setPosAndLastTickPos(
                    entity,
                    pos,
                    pos.subtract(McHelper.getWorldVelocity(entity))
                );
                McHelper.updateBoundingBox(entity);
                entity.setYRot(yRot);
                entity.setXRot(xRot);
                // the handler remembers the position before the jump.
                // without cancelling, it would add the jump to the interpolation target
                cancel();
            }
        }
    }
}
