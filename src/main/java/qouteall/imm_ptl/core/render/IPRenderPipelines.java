package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.McHelper;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Since 1.21.5, the shader and the OpenGL states (depth test, depth mask, color mask, blending, face culling)
 * are specified by {@link RenderPipeline}. Changing these OpenGL states before drawing has no effect.
 * <p>
 * The other OpenGL states that this mod uses (stencil test, clip plane, depth clamp, cull face, depth range)
 * are not managed by vanilla. They are still changed by directly calling OpenGL.
 */
public class IPRenderPipelines {

    /**
     * Vanilla doesn't have the "always pass" depth function with depth test enabled
     * (which is required for overwriting depth).
     * These pipelines are declared with the default depth function.
     * MixinGlCommandEncoder changes it into GL_ALWAYS.
     */
    private static final Set<RenderPipeline> DEPTH_ALWAYS_PIPELINES =
        Collections.newSetFromMap(new IdentityHashMap<>());

    public static boolean isDepthAlwaysPipeline(RenderPipeline pipeline) {
        return DEPTH_ALWAYS_PIPELINES.contains(pipeline);
    }

    public enum DepthMode {
        /**
         * The normal depth test.
         */
        LEQUAL,
        /**
         * The depth test always passes. It can write depth.
         */
        ALWAYS,
        /**
         * No depth test. Cannot write depth.
         */
        DISABLED
    }

    private record StateKey(
        boolean cull, boolean writeColor, boolean writeDepth, DepthMode depthMode
    ) {
        String describe() {
            return (cull ? "cull" : "nocull") + "_"
                + (writeColor ? "color" : "nocolor") + "_"
                + (writeDepth ? "depth" : "nodepth") + "_"
                + depthMode.name().toLowerCase();
        }

        RenderPipeline.Builder apply(RenderPipeline.Builder builder) {
            return builder
                .withCull(cull)
                .withColorWrite(writeColor)
                .withDepthWrite(writeDepth)
                .withDepthTestFunction(
                    depthMode == DepthMode.DISABLED ?
                        DepthTestFunction.NO_DEPTH_TEST : DepthTestFunction.LEQUAL_DEPTH_TEST
                );
        }

        RenderPipeline register(RenderPipeline pipeline) {
            if (depthMode == DepthMode.ALWAYS) {
                DEPTH_ALWAYS_PIPELINES.add(pipeline);
            }
            return pipeline;
        }
    }

    private static ResourceLocation id(String path) {
        return McHelper.newResourceLocation("immersive_portals", path);
    }

    private static final Map<StateKey, RenderPipeline> PORTAL_AREA_PIPELINES = new HashMap<>();

    /**
     * For drawing the view area of portal. The mesh is in triangles.
     * Before 1.21.5 the blend mode was specified by RenderSystem.blendFunc.
     */
    public static RenderPipeline getPortalAreaPipeline(
        boolean cull, boolean writeColor, boolean writeDepth, DepthMode depthMode
    ) {
        StateKey key = new StateKey(cull, writeColor, writeDepth, depthMode);
        return PORTAL_AREA_PIPELINES.computeIfAbsent(key, k -> k.register(k.apply(
            RenderPipeline.builder(RenderPipelines.MATRICES_SNIPPET)
                .withLocation(id("pipeline/portal_area_" + k.describe()))
                .withVertexShader(id("core/portal_area"))
                .withFragmentShader(id("core/portal_area"))
                .withBlend(new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA))
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
        ).build()));
    }

    /**
     * Draws the color of another framebuffer in the portal view area.
     */
    public static final RenderPipeline PORTAL_DRAW_FB_IN_AREA =
        RenderPipeline.builder(RenderPipelines.MATRICES_SNIPPET)
            .withLocation(id("pipeline/portal_draw_fb_in_area"))
            .withVertexShader(id("core/portal_draw_fb_in_area"))
            .withFragmentShader(id("core/portal_draw_fb_in_area"))
            .withSampler("DiffuseSampler")
            .withUniform("w", UniformType.FLOAT)
            .withUniform("h", UniformType.FLOAT)
            .withBlend(new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA))
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
            .build();

    private static final Map<StateKey, RenderPipeline> SCREEN_TRIANGLE_PIPELINES = new HashMap<>();

    /**
     * For drawing triangles that cover the whole screen, using the vanilla position color shader.
     */
    public static RenderPipeline getScreenTrianglePipeline(
        boolean writeColor, boolean writeDepth, DepthMode depthMode
    ) {
        StateKey key = new StateKey(false, writeColor, writeDepth, depthMode);
        return SCREEN_TRIANGLE_PIPELINES.computeIfAbsent(key, k -> k.register(k.apply(
            RenderPipeline.builder(RenderPipelines.MATRICES_COLOR_SNIPPET)
                .withLocation(id("pipeline/screen_triangle_" + k.describe()))
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
        ).build()));
    }

    private record BlitKey(boolean useAlphaBlend, boolean modifyAlpha) {}

    private static final Map<BlitKey, RenderPipeline> BLIT_PIPELINES = new HashMap<>();

    /**
     * For drawing a whole framebuffer onto another.
     * Uses the quad vertex buffer in {@link RenderSystem#getQuadVertexBuffer()}
     */
    public static RenderPipeline getBlitPipeline(boolean useAlphaBlend, boolean modifyAlpha) {
        BlitKey key = new BlitKey(useAlphaBlend, modifyAlpha);
        return BLIT_PIPELINES.computeIfAbsent(key, k -> {
            RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(id(
                    "pipeline/blit_" + (k.useAlphaBlend ? "blend" : "noblend")
                        + (k.modifyAlpha ? "_alpha" : "_noalpha")
                ))
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withColorWrite(true, k.modifyAlpha)
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS);

            if (k.useAlphaBlend) {
                // this is used for rendering a FB onto screen when the FB contains translucent things
                // the FB should initialize with zero color and zero alpha
                // MC's default blend func is: color = srcColor * srcAlpha + dstColor * (1-srcAlpha)
                // then the FB's rendered content would be fbColor = contentColor * contentAlpha
                // we want the roughtly same effect of rendering the translucent thing directly onto current FB, so we want:
                // color = contentColor * contentAlpha + dstColor * (1-contentAlpha)
                // color = fbColor * 1 + dstColor * (1-contentAlpha)
                builder
                    .withVertexShader("core/blit_screen")
                    .withFragmentShader("core/blit_screen")
                    .withSampler("InSampler")
                    .withBlend(new BlendFunction(
                        SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA,
                        SourceFactor.ZERO, DestFactor.ONE
                    ));
            }
            else {
                // the fragment shader outputs alpha 1
                builder
                    .withVertexShader(id("core/blit_screen_noblend"))
                    .withFragmentShader(id("core/blit_screen_noblend"))
                    .withSampler("DiffuseSampler")
                    .withoutBlend();
            }

            return builder.build();
        });
    }

    /**
     * Draw the mesh to a render target, similar to RenderType#draw.
     * It uses the model view matrix, projection matrix and shader color in {@link RenderSystem}.
     * The mesh will be closed.
     */
    public static void draw(
        RenderPipeline pipeline, RenderTarget target, MeshData mesh,
        @Nullable Consumer<RenderPass> passSetup
    ) {
        try (mesh) {
            GpuBuffer vertexBuffer =
                pipeline.getVertexFormat().uploadImmediateVertexBuffer(mesh.vertexBuffer());

            GpuBuffer indexBuffer;
            VertexFormat.IndexType indexType;
            if (mesh.indexBuffer() == null) {
                RenderSystem.AutoStorageIndexBuffer sequentialBuffer =
                    RenderSystem.getSequentialBuffer(mesh.drawState().mode());
                indexBuffer = sequentialBuffer.getBuffer(mesh.drawState().indexCount());
                indexType = sequentialBuffer.type();
            }
            else {
                indexBuffer = pipeline.getVertexFormat().uploadImmediateIndexBuffer(mesh.indexBuffer());
                indexType = mesh.drawState().indexType();
            }

            try (RenderPass renderPass = createRenderPass(target)) {
                renderPass.setPipeline(pipeline);
                renderPass.setVertexBuffer(0, vertexBuffer);
                renderPass.setIndexBuffer(indexBuffer, indexType);
                if (passSetup != null) {
                    passSetup.accept(renderPass);
                }
                renderPass.drawIndexed(0, mesh.drawState().indexCount());
            }
        }
    }

    /**
     * The render pass that draws to the render target, without clearing.
     * The depth texture is always attached (the stencil is in the depth texture).
     */
    public static RenderPass createRenderPass(RenderTarget target) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            target.getColorTexture(), OptionalInt.empty(),
            target.useDepth ? target.getDepthTexture() : null, OptionalDouble.empty()
        );
    }
}
