package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.stream.IntStream;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_FRONT;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glReadPixels;

@SuppressWarnings("resource")
public class MyRenderHelper {

    public static final Minecraft client = Minecraft.getInstance();

    // Before 1.21.5 the shader programs were registered here.
    // Now the shaders are referenced by the render pipelines. See IPRenderPipelines.
    // The shader files are in assets/immersive_portals/shaders/core/

    public static void init() {
        IPGlobal.PRE_GAME_RENDER_EVENT.register(MyRenderHelper::resetBoundTarget);
    }

    /**
     * Before 1.21.5, a framebuffer was bound by RenderTarget#bindWrite and the draw calls drew to it.
     * Since 1.21.5 there is no bound framebuffer. Every render pass specifies its target.
     * <p>
     * The portal renderers were written around binding framebuffers.
     * This keeps track of the render target that this mod draws to.
     * It only affects this mod's own drawing (portal area, screen triangle, framebuffer drawing).
     * The vanilla rendering goes to the main render target of {@link Minecraft}.
     */
    @Nullable
    private static RenderTarget boundTarget = null;

    /**
     * The replacement of RenderTarget#bindWrite for this mod's own drawing.
     */
    public static void bindWrite(RenderTarget target) {
        boundTarget = target;
    }

    /**
     * Make this mod's own drawing go to the main render target.
     */
    public static void resetBoundTarget() {
        boundTarget = null;
    }

    /**
     * @return The render target that this mod's own drawing goes to.
     */
    public static RenderTarget getBoundTarget() {
        if (boundTarget != null && boundTarget.getColorTexture() != null) {
            return boundTarget;
        }
        return client.getMainRenderTarget();
    }

    /**
     * @return The OpenGL framebuffer object that vanilla uses to draw to the render target.
     */
    public static int getFramebufferId(RenderTarget target) {
        GlTexture colorTexture = (GlTexture) Objects.requireNonNull(
            target.getColorTexture(), "The render target has no color texture"
        );
        GlDevice device = (GlDevice) RenderSystem.getDevice();
        return colorTexture.getFbo(
            device.directStateAccess(),
            target.useDepth ? target.getDepthTexture() : null
        );
    }

    public static int getColorTextureId(RenderTarget target) {
        return ((GlTexture) Objects.requireNonNull(target.getColorTexture())).glId();
    }

    public static int getDepthTextureId(RenderTarget target) {
        return ((GlTexture) Objects.requireNonNull(target.getDepthTexture())).glId();
    }

    /**
     * Bind the framebuffer object for directly calling OpenGL (clearing stencil, blitting).
     * The vanilla render passes bind their own framebuffer and unbind when finishes,
     * so this binding doesn't affect normal drawing.
     */
    public static void bindGlFramebuffer(RenderTarget target) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, getFramebufferId(target));
    }

    public static void unbindGlFramebuffer() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public static void clearStencil(RenderTarget target) {
        bindGlFramebuffer(target);
        GlStateManager._disableScissorTest();
        GL11.glStencilMask(0xFF);
        GL11.glClearStencil(0);
        GlStateManager._clear(GL_STENCIL_BUFFER_BIT);
        unbindGlFramebuffer();
    }

    public static void clearColorAndDepth(
        RenderTarget target, float r, float g, float b, float a
    ) {
        if (target.getDepthTexture() == null) {
            clearColor(target, r, g, b, a);
            return;
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            Objects.requireNonNull(target.getColorTexture()), ARGB.colorFromFloat(a, r, g, b),
            target.getDepthTexture(), 1.0
        );
    }

    public static void clearColor(
        RenderTarget target, float r, float g, float b, float a
    ) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
            Objects.requireNonNull(target.getColorTexture()), ARGB.colorFromFloat(a, r, g, b)
        );
    }

    /**
     * Since 1.21.6 the projection matrix in {@link RenderSystem} is a uniform buffer
     * and the matrix cannot be read back.
     * This is the projection matrix that vanilla uses for the world rendering that's going on.
     * It's tracked in MixinGameRenderer.
     */
    private static Matrix4f levelProjectionMatrix = new Matrix4f();

    public static Matrix4f getLevelProjectionMatrix() {
        return levelProjectionMatrix;
    }

    public static void setLevelProjectionMatrix(Matrix4f matrix) {
        levelProjectionMatrix = new Matrix4f(matrix);
    }

    /**
     * The uniform buffer for the projection matrix of this mod's own drawing.
     * (Any matrix can be put into it. It's not limited to perspective projection.)
     */
    @Nullable
    private static PerspectiveProjectionMatrixBuffer ownProjectionMatrixBuffer;

    private static GpuBufferSlice uploadOwnProjectionMatrix(Matrix4f projectionMatrix) {
        if (ownProjectionMatrixBuffer == null) {
            ownProjectionMatrixBuffer = new PerspectiveProjectionMatrixBuffer("immersive portals");
        }
        return ownProjectionMatrixBuffer.getBuffer(projectionMatrix);
    }

    /**
     * The vanilla shader programs always use the model view matrix and projection matrix
     * in {@link RenderSystem}. Temporarily change them.
     */
    public static void withMatrices(
        Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Runnable func
    ) {
        GpuBufferSlice oldProjectionMatrix = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldProjectionType = RenderSystem.getProjectionType();

        Matrix4f newModelView = new Matrix4f(modelViewMatrix);
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(newModelView);
        RenderSystem.setProjectionMatrix(
            uploadOwnProjectionMatrix(projectionMatrix), oldProjectionType
        );

        try {
            func.run();
        }
        finally {
            modelViewStack.popMatrix();
            if (oldProjectionMatrix != null) {
                RenderSystem.setProjectionMatrix(oldProjectionMatrix, oldProjectionType);
            }
        }
    }

    /**
     * Draws the color of the framebuffer in the portal view area, into the bound target.
     */
    @SuppressWarnings("SuspiciousNameCombination")
    public static void drawPortalAreaWithFramebuffer(
        Portal portal,
        RenderTarget textureProvider,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix
    ) {
        RenderTarget target = getBoundTarget();
        Validate.isTrue(target != textureProvider, "cannot draw a framebuffer to itself");

        MeshData mesh = ViewAreaRenderer.buildPortalViewAreaMesh(
            Vec3.ZERO,//fog
            portal,
            CHelper.getCurrentCameraPos(),
            RenderStates.getPartialTick()
        );

        if (mesh == null) {
            return;
        }

        withMatrices(modelViewMatrix, projectionMatrix, () -> {
            IPRenderPipelines.draw(
                IPRenderPipelines.PORTAL_DRAW_FB_IN_AREA, target, mesh,
                renderPass -> {
                    // the shader gets the size from the texture
                    renderPass.bindSampler("DiffuseSampler", textureProvider.getColorTextureView());
                }
            );
        });
    }

    public static void renderScreenTriangle() {
        renderScreenTriangle(255, 255, 255, 255);
    }

    public static void renderScreenTriangle(Vec3 color) {
        renderScreenTriangle(
            color, true, true, IPRenderPipelines.DepthMode.LEQUAL
        );
    }

    public static void renderScreenTriangle(
        Vec3 color,
        boolean writeColor, boolean writeDepth, IPRenderPipelines.DepthMode depthMode
    ) {
        renderScreenTriangle(
            (int) (color.x * 255),
            (int) (color.y * 255),
            (int) (color.z * 255),
            255,
            writeColor, writeDepth, depthMode
        );
    }

    public static void renderScreenTriangle(int r, int g, int b, int a) {
        renderScreenTriangle(r, g, b, a, true, true, IPRenderPipelines.DepthMode.LEQUAL);
    }

    /**
     * Draw triangles that cover the whole bound target.
     * Before 1.21.5 the color mask, depth mask and depth function were changed by OpenGL calls
     * before calling this. Now they are in render pipeline.
     */
    public static void renderScreenTriangle(
        int r, int g, int b, int a,
        boolean writeColor, boolean writeDepth, IPRenderPipelines.DepthMode depthMode
    ) {
        RenderPipeline pipeline = IPRenderPipelines.getScreenTrianglePipeline(
            writeColor, writeDepth, depthMode
        );

        BufferBuilder bufferBuilder = Tesselator.getInstance()
            .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        bufferBuilder.addVertex(1, -1, 0).setColor(r, g, b, a);
        bufferBuilder.addVertex(1, 1, 0).setColor(r, g, b, a);
        bufferBuilder.addVertex(-1, 1, 0).setColor(r, g, b, a);

        bufferBuilder.addVertex(-1, 1, 0).setColor(r, g, b, a);
        bufferBuilder.addVertex(-1, -1, 0).setColor(r, g, b, a);
        bufferBuilder.addVertex(1, -1, 0).setColor(r, g, b, a);

        MeshData mesh = bufferBuilder.buildOrThrow();

        // it draws with identity matrices and without color modulation
        Matrix4f identityMatrix = new Matrix4f();

        withMatrices(identityMatrix, identityMatrix, () -> {
            IPRenderPipelines.draw(pipeline, getBoundTarget(), mesh, null);
        });
    }

    /**
     * Draws the framebuffer's color to the whole bound target.
     */
    public static void drawScreenFrameBuffer(
        RenderTarget textureProvider,
        boolean doUseAlphaBlend,
        boolean doEnableModifyAlpha
    ) {
        int x = 0;
        int y = 0;

        int viewportWidth = textureProvider.viewWidth;
        int viewportHeight = textureProvider.viewHeight;

        drawFramebufferWithCoordinatesAndDimensions(
            textureProvider, doUseAlphaBlend, doEnableModifyAlpha,
            x, y, viewportWidth, viewportHeight
        );
    }

    public static void drawFramebuffer(
            RenderTarget textureProvider, boolean doUseAlphaBlend, boolean doEnableModifyAlpha,
            float xMin, float xMax, float yMin, float yMax
    ) {
        drawFramebufferWithCoordinatesAndDimensions(
                textureProvider,
                doUseAlphaBlend, doEnableModifyAlpha,
                0, 0,
                client.getWindow().getWidth(),
                client.getWindow().getHeight()
        );
    }

    public static void drawFramebufferWithViewport(
            RenderTarget textureProvider, boolean doUseAlphaBlend, boolean doEnableModifyAlpha,
            float left, float right, float bottom, float up,
            int viewportWidth, int viewportHeight
    ) {
        drawFramebufferWithCoordinatesAndDimensions(
                textureProvider,
                doUseAlphaBlend, doEnableModifyAlpha,
                0, 0,
                viewportWidth, viewportHeight
        );
    }

    public static void drawFramebufferWithBounds(
        RenderTarget textureProvider, boolean doUseAlphaBlend, boolean doEnableModifyAlpha,
        int xMin, int xMax, int yMin, int yMax
    ) {

        drawFramebufferWithCoordinatesAndDimensions(
            textureProvider,
            doUseAlphaBlend, doEnableModifyAlpha,
            xMin, yMin,
            Mth.abs(xMax - xMin),
            Mth.abs(yMax - yMin)
        );
    }

    /**
     * Draws the framebuffer's color into a viewport of the bound target.
     * {@link RenderTarget#blitAndBlendToTexture}
     */
    @SuppressWarnings("resource")
    @IPVanillaCopy
    public static void drawFramebufferWithCoordinatesAndDimensions(
        RenderTarget textureProvider, boolean doUseAlphaBlend, boolean doEnableModifyAlpha,
        int x, int y, int viewportWidth, int viewportHeight
    ) {
        CHelper.checkGlError();

        RenderTarget target = getBoundTarget();
        Validate.isTrue(target != textureProvider, "cannot draw a framebuffer to itself");

        // the blend mode, depth and color mask are in the pipeline
        RenderPipeline pipeline = IPRenderPipelines.getBlitPipeline(
            doUseAlphaBlend, doEnableModifyAlpha
        );

        RenderSystem.AutoStorageIndexBuffer sequentialBuffer =
            RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = sequentialBuffer.getBuffer(6);
        GpuBuffer vertexBuffer = RenderSystem.getQuadVertexBuffer();

        try (RenderPass renderPass = IPRenderPipelines.createRenderPass(target)) {
            // creating the render pass sets the viewport to the whole target
            GlStateManager._viewport(
                x, textureProvider.viewHeight - viewportHeight - y, viewportWidth, viewportHeight
            );

            renderPass.setPipeline(pipeline);
            renderPass.setVertexBuffer(0, vertexBuffer);
            renderPass.setIndexBuffer(indexBuffer, sequentialBuffer.type());
            // the sampler of vanilla blit_screen shader is named InSampler since 1.21.2
            renderPass.bindSampler(
                doUseAlphaBlend ? "InSampler" : "DiffuseSampler",
                textureProvider.getColorTextureView()
            );
            renderPass.drawIndexed(0, 0, 6, 1);
        }

        CHelper.checkGlError();
    }

    // it will remove the light sections that are marked to be removed
    // if not, light data will cause minor memory leak
    // and wrongly remove the light data when the chunks get reloaded to client
    // this should not run before world rendering or the smooth lighting may become abnormal in section edge
    public static void lateUpdateLight() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        
        ClientWorldLoader.getClientWorlds().forEach(world -> {
            if (!RenderStates.isDimensionRendered(world.dimension())) {
                world.getChunkSource().getLightEngine().runLightUpdates();
            }
        });
    }
    
    /**
     * If we don't do this
     * the future created in {@link SectionRenderDispatcher#uploadSectionLayer}
     * may never complete
     */
    public static void earlyRemoteUpload() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        
        ClientWorldLoader.WORLD_RENDERER_MAP.forEach((dim, worldRenderer) -> {
            Validate.notNull(client.level, "client.level is null");
            if (client.level.dimension() != dim) {
                worldRenderer.getSectionRenderDispatcher().uploadAllPendingUploads();
            }
        });
    }
    
    public static void applyMirrorFaceCulling() {
        glCullFace(GL_FRONT);
    }
    
    public static void recoverFaceCulling() {
        glCullFace(GL_BACK);
    }
    
    public static void clearAlphaTo1(RenderTarget mcFrameBuffer) {
        bindGlFramebuffer(mcFrameBuffer);
        GlStateManager._disableScissorTest();
        GlStateManager._colorMask(false, false, false, true);
        GL11.glClearColor(0, 0, 0, 1.0f);
        GlStateManager._clear(GL_COLOR_BUFFER_BIT);
        GlStateManager._colorMask(true, true, true, true);
        unbindGlFramebuffer();
    }
    
    public static void restoreViewPort() {
        Minecraft client = Minecraft.getInstance();
        GlStateManager._viewport(
            0,
            0,
            client.getWindow().getWidth(),
            client.getWindow().getHeight()
        );
    }
    
    public static float transformFogDistance(float value) {
        if (!WorldRenderInfo.isFogEnabled()) {
            return value * 23333;
        }
        
        // just disable fog for fuse-view portals for now
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            
            if (renderingPortal.isFuseView()) {
                return value * 23333;
            }
        }
        
        // as non-fuse-view portals does not apply scale transformation to modelview,
        // there is no need to transform fog distance (both with and without sodium)
        
        return value;
    }
    
    private static boolean debugEnabled = false;
    
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public static void debugFramebufferDepth() {
        if (!debugEnabled) {
            return;
        }
        debugEnabled = false;
        
        int width = client.getMainRenderTarget().width;
        int height = client.getMainRenderTarget().height;
        
        
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        
        FloatBuffer floatBuffer = directBuffer.asFloatBuffer();

        bindGlFramebuffer(client.getMainRenderTarget());
        glReadPixels(
            0, 0, width, height,
            GL_DEPTH_COMPONENT, GL_FLOAT, floatBuffer
        );
        unbindGlFramebuffer();
        
        float[] data = new float[width * height];
        
        floatBuffer.rewind();
        floatBuffer.get(data);
        
        float maxValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).max().getAsDouble();
        float minValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).min().getAsDouble();
        
        byte[] grayData = new byte[width * height];
        for (int i = 0; i < data.length; i++) {
            float datum = data[i];
            
            datum = (datum - minValue) / (maxValue - minValue);
            
            grayData[i] = (byte) (datum * 255);
        }
        
        BufferedImage bufferedImage =
            new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        
        bufferedImage.setData(
            Raster.createRaster(
                bufferedImage.getSampleModel(),
                new DataBufferByte(grayData, grayData.length), new Point()
            )
        );
        
        System.out.println("oops");
    }
    
    public static void debugFramebufferColorRed() {
        if (!debugEnabled) {
            return;
        }
        debugEnabled = false;
        
        int width = client.getMainRenderTarget().width;
        int height = client.getMainRenderTarget().height;
        
        
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        
        FloatBuffer floatBuffer = directBuffer.asFloatBuffer();

        bindGlFramebuffer(client.getMainRenderTarget());
        glReadPixels(
            0, 0, width, height,
            GL_RED, GL_FLOAT, floatBuffer
        );
        unbindGlFramebuffer();
        
        float[] data = new float[width * height];
        
        floatBuffer.rewind();
        floatBuffer.get(data);
        
        float maxValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).max().getAsDouble();
        float minValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).min().getAsDouble();
        
        byte[] grayData = new byte[width * height];
        for (int i = 0; i < data.length; i++) {
            float datum = data[i];
            
            datum = (datum - minValue) / (maxValue - minValue);
            
            grayData[i] = (byte) (datum * 255);
        }
        
        BufferedImage bufferedImage =
            new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        
        bufferedImage.setData(
            Raster.createRaster(
                bufferedImage.getSampleModel(),
                new DataBufferByte(grayData, grayData.length), new Point()
            )
        );
        
        System.out.println("oops");
    }
}
