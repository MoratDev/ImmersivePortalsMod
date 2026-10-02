package qouteall.imm_ptl.core.mixin.common.collision;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.level.entity.UniquelyIdentifyable;
import net.minecraft.world.level.entity.UUIDLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Projectile.class)
public abstract class MixinProjectile extends MixinEntity {
    
    // make it recognize the owner in another dimension
    // In 1.21.2 ~ 1.21.5 the lookup was in findOwner.
    // Since 1.21.6 the owner is an EntityReference that's resolved in getOwner.
    @WrapOperation(
        method = "getOwner",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/EntityReference;get(Lnet/minecraft/world/entity/EntityReference;Lnet/minecraft/world/level/entity/UUIDLookup;Ljava/lang/Class;)Lnet/minecraft/world/level/entity/UniquelyIdentifyable;"
        )
    )
    private UniquelyIdentifyable wrapGetOwner(
        EntityReference<?> reference, UUIDLookup<?> uuidLookup, Class<?> entityClass,
        Operation<UniquelyIdentifyable> original
    ) {
        UniquelyIdentifyable result = original.call(reference, uuidLookup, entityClass);
        if (result != null || reference == null) {
            return result;
        }

        if (uuidLookup instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            for (ServerLevel world : server.getAllLevels()) {
                if (world != serverLevel) {
                    result = original.call(reference, world, entityClass);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

//    @Shadow
//    public abstract void onHit(HitResult hitResult);
//
//    @Inject(method = "Lnet/minecraft/world/entity/projectile/Projectile;onHit(Lnet/minecraft/world/phys/HitResult;)V", at = @At(value = "HEAD"), cancellable = true)
//    protected void onHit(HitResult hitResult, CallbackInfo ci) {
//        Entity this_ = (Entity) (Object) this;
//        if (hitResult instanceof BlockHitResult) {
//            Block hittingBlock = this_.level().getBlockState(((BlockHitResult) hitResult).getBlockPos()).getBlock();
//            if (hitResult.getType() == HitResult.Type.BLOCK &&
//                hittingBlock == PortalPlaceholderBlock.instance
//            ) {
//                ci.cancel();
//            }
//        }
//    }
//
    
}