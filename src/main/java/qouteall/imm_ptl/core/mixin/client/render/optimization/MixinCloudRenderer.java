package qouteall.imm_ptl.core.mixin.client.render.optimization;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.render.context_management.CloudContext;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.ArrayList;
import java.util.List;

// Optimize cloud rendering by storing the context and
// avoiding rebuild the cloud mesh every time.
// When rendering portals, the same dimension is rendered from different camera positions in one frame.
// Vanilla rebuilds the cloud mesh when the camera moves to another cloud cell.
// In 1.21.1 this was a mixin to LevelRenderer. Since 1.21.2 the cloud rendering is in CloudRenderer.
// Since 1.21.6 the cloud mesh is in a texel buffer and the cloud info is in a uniform buffer.
@Mixin(CloudRenderer.class)
public abstract class MixinCloudRenderer {
    @Shadow
    @Final
    private static int UBO_SIZE;

    @Shadow
    private boolean needsRebuild;

    @Shadow
    private int prevCellX;

    @Shadow
    private int prevCellZ;

    @Shadow
    private CloudRenderer.RelativeCameraPos prevRelativeCameraPos;

    @Shadow
    @Nullable
    private CloudStatus prevType;

    @Shadow
    @Nullable
    private CloudRenderer.TextureData texture;

    @Shadow
    private int quadCount;

    @Shadow
    @Final
    private MappableRingBuffer ubo;

    // it's created when rendering
    @Shadow
    @Nullable
    private MappableRingBuffer utb;

    @Shadow
    private static int getSizeForCloudDistance(int cloudDistance) {
        throw new RuntimeException();
    }

    /**
     * Vanilla writes the cloud info (color and offset) into one uniform buffer every time
     * it renders the clouds, as it renders the clouds once per frame.
     * With portals, the clouds of one dimension can be rendered many times per frame
     * with different offsets.
     * Overwriting the persistently mapped buffer while the earlier draw calls may not have been
     * executed by GPU is not safe. So the extra renderings in a frame use extra buffers.
     */
    @Unique
    private final List<MappableRingBuffer> ip_extraUbos = new ArrayList<>();

    @Unique
    private int ip_renderCountInFrame = 0;

    @Unique
    private @Nullable MappableRingBuffer ip_currentUbo = null;

    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void onBeginRenderClouds(
        int cloudColor, CloudStatus cloudStatus, float cloudHeight,
        Vec3 cameraPosition, float ticks, CallbackInfo ci
    ) {
        int index = ip_renderCountInFrame;
        ip_renderCountInFrame++;
        if (index == 0) {
            ip_currentUbo = null;
        }
        else {
            while (ip_extraUbos.size() < index) {
                // the same as the vanilla one
                ip_extraUbos.add(new MappableRingBuffer(() -> "Cloud UBO (portal)", 130, UBO_SIZE));
            }
            ip_currentUbo = ip_extraUbos.get(index - 1);
        }

        if (RenderStates.getRenderedPortalNum() == 0) {
            return;
        }

        if (IPGlobal.cloudOptimization) {
            ip_onBeginCloudRendering(cloudStatus, cloudHeight, cameraPosition, ticks);
        }
    }

    @Redirect(
        method = "render",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/CloudRenderer;ubo:Lnet/minecraft/client/renderer/MappableRingBuffer;",
            opcode = Opcodes.GETFIELD
        )
    )
    private MappableRingBuffer redirectGetUbo(CloudRenderer instance) {
        return ip_currentUbo != null ? ip_currentUbo : ubo;
    }

    @Inject(
        method = "render",
        at = @At("RETURN")
    )
    private void onEndRenderClouds(
        int cloudColor, CloudStatus cloudStatus, float cloudHeight,
        Vec3 cameraPosition, float ticks, CallbackInfo ci
    ) {
        ip_currentUbo = null;

        if (RenderStates.getRenderedPortalNum() == 0) {
            return;
        }

        if (IPGlobal.cloudOptimization) {
            ip_onEndCloudRendering();
        }
    }

    @Inject(method = "endFrame", at = @At("RETURN"))
    private void onEndFrame(CallbackInfo ci) {
        // only the buffers that were used in this frame
        int usedExtraUbos = Math.min(ip_extraUbos.size(), Math.max(0, ip_renderCountInFrame - 1));
        for (int i = 0; i < usedExtraUbos; i++) {
            ip_extraUbos.get(i).rotate();
        }
        ip_renderCountInFrame = 0;
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void onClose(CallbackInfo ci) {
        for (MappableRingBuffer extraUbo : ip_extraUbos) {
            extraUbo.close();
        }
        ip_extraUbos.clear();
    }

    @Unique
    private void ip_yieldCloudContext(CloudContext context) {
        ClientLevel level = Minecraft.getInstance().level;
        assert level != null;

        context.cellX = prevCellX;
        context.cellZ = prevCellZ;
        context.relativeCameraPos = prevRelativeCameraPos.ordinal();
        context.cloudStatus = prevType;
        context.dimension = level.dimension();
        context.cloudsBuffer = utb;
        context.cloudsQuadCount = quadCount;

        // the buffer is now owned by the context
        // vanilla will create a new buffer when rendering
        utb = null;
        quadCount = 0;
        needsRebuild = true;
    }

    @Unique
    private void ip_loadCloudContext(CloudContext context) {
        // this buffer does not belong to any context
        if (utb != null) {
            utb.close();
        }

        prevCellX = context.cellX;
        prevCellZ = context.cellZ;
        prevRelativeCameraPos = CloudRenderer.RelativeCameraPos.values()[context.relativeCameraPos];
        prevType = context.cloudStatus;
        utb = context.cloudsBuffer;
        quadCount = context.cloudsQuadCount;

        needsRebuild = false;
    }

    /**
     * {@link CloudRenderer#render}
     */
    @IPVanillaCopy
    @Unique
    private void ip_onBeginCloudRendering(
        CloudStatus cloudStatus, float cloudHeight, Vec3 cameraPosition, float ticks
    ) {
        if (texture == null) {
            return;
        }

        // the size of the mesh buffer depends on the cloud distance
        int cloudRange = Math.min(Minecraft.getInstance().options.cloudRange().get(), 128) * 16;
        int requiredBufferSize = getSizeForCloudDistance(Mth.ceil((float) cloudRange / 12.0F));

        float f = (float) ((double) cloudHeight - cameraPosition.y);
        float g = f + 4.0F;
        CloudRenderer.RelativeCameraPos relativeCameraPos;
        if (g < 0.0F) {
            relativeCameraPos = CloudRenderer.RelativeCameraPos.ABOVE_CLOUDS;
        }
        else if (f > 0.0F) {
            relativeCameraPos = CloudRenderer.RelativeCameraPos.BELOW_CLOUDS;
        }
        else {
            relativeCameraPos = CloudRenderer.RelativeCameraPos.INSIDE_CLOUDS;
        }

        double d = cameraPosition.x + (double) (ticks * 0.030000001F);
        double e = cameraPosition.z + 3.96F;
        double h = (double) texture.width() * 12.0;
        double i = (double) texture.height() * 12.0;
        d -= (double) Mth.floor(d / h) * h;
        e -= (double) Mth.floor(e / i) * i;
        int cellX = Mth.floor(d / 12.0);
        int cellZ = Mth.floor(e / 12.0);

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        @Nullable CloudContext context = CloudContext.findAndTakeContext(
            cellX, cellZ, relativeCameraPos.ordinal(), cloudStatus, level.dimension()
        );

        if (context != null) {
            if (context.cloudsBuffer != null && context.cloudsBuffer.size() == requiredBufferSize) {
                ip_loadCloudContext(context);
            }
            else {
                // the cloud distance changed
                context.dispose();
            }
        }
    }

    @Unique
    private void ip_onEndCloudRendering() {
        if (texture == null) {
            return;
        }

        if (!needsRebuild && utb != null) {
            final CloudContext newContext = new CloudContext();
            ip_yieldCloudContext(newContext);

            CloudContext.appendContext(newContext);
        }
    }
}
