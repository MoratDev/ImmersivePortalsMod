package qouteall.imm_ptl.core.mixin.client.multiworld_awareness;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IEFogRenderer;
import qouteall.imm_ptl.core.render.IPFogUniform;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;

import java.nio.ByteBuffer;

/**
 * Since 1.21.6 the fog renderer is an object owned by GameRenderer (it was a static class)
 * and the fog is a uniform buffer.
 * <p>
 * Vanilla has one fog uniform buffer per frame, because it renders the world once per frame.
 * With portals the world is rendered many times per frame, with different fog.
 * Overwriting the buffer is not safe (it's persistently mapped, the earlier draw calls
 * may not have been executed by the GPU), and the outer world rendering continues to use its fog
 * after rendering the portals.
 * So every world rendering gets its own slice of a per-frame uniform storage.
 */
@Mixin(value = FogRenderer.class, priority = 1100)
public abstract class MixinFogRenderer implements IEFogRenderer {
    @Shadow
    private static boolean fogEnabled;

    @Shadow
    protected abstract Vector4f computeFogColor(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount, boolean isFoggy
    );

    @Unique
    private @Nullable DynamicUniformStorage<IPFogUniform> ip_fogStorage;

    @Unique
    private @Nullable IPFogUniform ip_pendingFog;

    @Unique
    private @Nullable GpuBufferSlice ip_currentFog;

    // In 1.21.1 the fog color was in vanilla static fields.
    // Since 1.21.2 the fog color is returned from computeFogColor and passed around,
    // so track the last computed fog color.
    @Inject(method = "computeFogColor", at = @At("RETURN"))
    private void onComputeFogColor(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount, boolean isFoggy,
        CallbackInfoReturnable<Vector4f> cir
    ) {
        Vector4f fogColor = cir.getReturnValue();
        FogRendererContext.currentFogRed = fogColor.x;
        FogRendererContext.currentFogGreen = fogColor.y;
        FogRendererContext.currentFogBlue = fogColor.z;
    }

    // In 1.21.1 it modified the arguments of setShaderFogStart and setShaderFogEnd.
    // In 1.21.2 ~ 1.21.5 it modified the FogParameters passed to RenderSystem.setShaderFog.
    // Since 1.21.6 the fog distances are in FogData and then written into the uniform buffer.
    // It's modified before mapping the buffer, so that Sodium (which reads the FogData
    // before the buffer is written) also gets the modified values.
    @Inject(
        method = "setupFog",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;",
            remap = false
        )
    )
    private void onFogDataReady(
        CallbackInfoReturnable<Vector4f> cir, @Local FogData fogData
    ) {
        fogData.environmentalStart = MyRenderHelper.transformFogDistance(fogData.environmentalStart);
        fogData.environmentalEnd = MyRenderHelper.transformFogDistance(fogData.environmentalEnd);
        fogData.renderDistanceStart = MyRenderHelper.transformFogDistance(fogData.renderDistanceStart);
        fogData.renderDistanceEnd = MyRenderHelper.transformFogDistance(fogData.renderDistanceEnd);
        fogData.skyEnd = MyRenderHelper.transformFogDistance(fogData.skyEnd);
        fogData.cloudEnd = MyRenderHelper.transformFogDistance(fogData.cloudEnd);
    }

    // keep what vanilla writes into its fog uniform buffer
    @WrapOperation(
        method = "setupFog",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"
        )
    )
    private void wrapUpdateBuffer(
        FogRenderer instance, ByteBuffer buffer, int position, Vector4f fogColor,
        float environmentalStart, float environmentalEnd,
        float renderDistanceStart, float renderDistanceEnd,
        float skyEnd, float cloudEnd,
        Operation<Void> original
    ) {
        original.call(
            instance, buffer, position, fogColor,
            environmentalStart, environmentalEnd,
            renderDistanceStart, renderDistanceEnd,
            skyEnd, cloudEnd
        );

        ip_pendingFog = new IPFogUniform(
            new Vector4f(fogColor),
            environmentalStart, environmentalEnd,
            renderDistanceStart, renderDistanceEnd,
            skyEnd, cloudEnd
        );
    }

    // write it after vanilla unmaps its own buffer
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void onSetupFogEnd(CallbackInfoReturnable<Vector4f> cir) {
        if (ip_pendingFog != null) {
            if (ip_fogStorage == null) {
                ip_fogStorage = new DynamicUniformStorage<>(
                    "Immersive Portals Fog UBO", FogRenderer.FOG_UBO_SIZE, 8
                );
            }
            ip_currentFog = ip_fogStorage.writeUniform(ip_pendingFog);
            ip_pendingFog = null;
        }
    }

    @Inject(method = "getBuffer", at = @At("RETURN"), cancellable = true)
    private void onGetBuffer(
        FogRenderer.FogMode fogMode, CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        if (fogMode == FogRenderer.FogMode.WORLD && fogEnabled && ip_currentFog != null) {
            cir.setReturnValue(ip_currentFog);
        }
    }

    @Inject(method = "endFrame", at = @At("HEAD"))
    private void onEndFrame(CallbackInfo ci) {
        // the slices become invalid
        ip_currentFog = null;
        if (ip_fogStorage != null) {
            ip_fogStorage.endFrame();
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void onClose(CallbackInfo ci) {
        ip_currentFog = null;
        if (ip_fogStorage != null) {
            ip_fogStorage.close();
            ip_fogStorage = null;
        }
    }

    @Override
    public @Nullable GpuBufferSlice ip_getCurrentFog() {
        return ip_currentFog;
    }

    @Override
    public void ip_setCurrentFog(@Nullable GpuBufferSlice fog) {
        ip_currentFog = fog;
    }

    @Override
    public Vector4f ip_computeFogColor(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount, boolean isFoggy
    ) {
        return computeFogColor(
            camera, partialTick, level, renderDistance, darkenWorldAmount, isFoggy
        );
    }
}
