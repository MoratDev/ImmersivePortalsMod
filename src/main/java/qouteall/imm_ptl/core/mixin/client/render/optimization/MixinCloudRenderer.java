package qouteall.imm_ptl.core.mixin.client.render.optimization;

import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.render.context_management.CloudContext;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

// Optimize cloud rendering by storing the context and
// avoiding rebuild the cloud mesh every time.
// When rendering portals, the same dimension is rendered from different camera positions in one frame.
// Vanilla rebuilds the cloud mesh when the camera moves to another cloud cell.
// In 1.21.1 this was a mixin to LevelRenderer. Since 1.21.2 the cloud rendering is in CloudRenderer.
@Mixin(CloudRenderer.class)
public abstract class MixinCloudRenderer {
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
    @Final
    @Mutable
    private VertexBuffer vertexBuffer;

    @Shadow
    private boolean vertexBufferEmpty;

    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void onBeginRenderClouds(
        int cloudColor, CloudStatus cloudStatus, float cloudHeight,
        Matrix4f frustumMatrix, Matrix4f projectionMatrix,
        Vec3 cameraPosition, float ticks, CallbackInfo ci
    ) {
        if (RenderStates.getRenderedPortalNum() == 0) {
            return;
        }

        if (IPGlobal.cloudOptimization) {
            ip_onBeginCloudRendering(cloudStatus, cloudHeight, cameraPosition, ticks);
        }
    }

    @Inject(
        method = "render",
        at = @At("RETURN")
    )
    private void onEndRenderClouds(
        int cloudColor, CloudStatus cloudStatus, float cloudHeight,
        Matrix4f frustumMatrix, Matrix4f projectionMatrix,
        Vec3 cameraPosition, float ticks, CallbackInfo ci
    ) {
        if (RenderStates.getRenderedPortalNum() == 0) {
            return;
        }

        if (IPGlobal.cloudOptimization) {
            ip_onEndCloudRendering();
        }
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
        context.cloudsBuffer = vertexBuffer;
        context.cloudsBufferEmpty = vertexBufferEmpty;

        // the buffer is now owned by the context
        vertexBuffer = new VertexBuffer(BufferUsage.STATIC_WRITE);
        needsRebuild = true;
    }

    @Unique
    private void ip_loadCloudContext(CloudContext context) {
        // this buffer does not belong to any context
        vertexBuffer.close();

        prevCellX = context.cellX;
        prevCellZ = context.cellZ;
        prevRelativeCameraPos = CloudRenderer.RelativeCameraPos.values()[context.relativeCameraPos];
        prevType = context.cloudStatus;
        vertexBuffer = context.cloudsBuffer;
        vertexBufferEmpty = context.cloudsBufferEmpty;

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
            ip_loadCloudContext(context);
        }
    }

    @Unique
    private void ip_onEndCloudRendering() {
        if (texture == null) {
            return;
        }

        if (!needsRebuild) {
            final CloudContext newContext = new CloudContext();
            ip_yieldCloudContext(newContext);

            CloudContext.appendContext(newContext);
        }
    }
}
