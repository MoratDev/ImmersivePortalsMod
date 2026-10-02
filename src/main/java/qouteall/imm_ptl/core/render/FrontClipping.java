package qouteall.imm_ptl.core.render;

import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

public class FrontClipping {
    private static final Minecraft client = Minecraft.getInstance();
    private static double[] activeClipPlaneEquationBeforeModelView;
    private static double[] activeClipPlaneAfterModelView;
    
    public static boolean isClippingEnabled = false;
    
    public static final double ADJUSTMENT = 0.01;
    
    public static void disableClipping() {
        if (IPGlobal.enableClippingMechanism) {
            if (isClippingEnabled) {
                GL11.glDisable(GL11.GL_CLIP_PLANE0);
                isClippingEnabled = false;
            }
        }
    }
    
    private static void enableClipping() {
        if (IPGlobal.enableClippingMechanism) {
            if (!isClippingEnabled) {
                GL11.glEnable(GL11.GL_CLIP_PLANE0);
                isClippingEnabled = true;
            }
        }
    }
    
    public static void updateInnerClipping(PoseStack matrixStack) {
        Matrix4f modelView = matrixStack.last().pose();
        updateInnerClipping(modelView);
    }
    
    public static void updateInnerClipping(Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            setupInnerClipping(
                PortalRendering.getActiveClippingPlane(),
                modelView, 0
            );
        }
        else {
            disableClipping();
        }
    }
    
    // NOTE the actual clipping plane is related to current model view matrix
    public static void setupInnerClipping(
        Plane clipping, Matrix4f modelView, double adjustment
    ) {
        if (!IPCGlobal.useFrontClipping) {
            return;
        }
        
        // Note: the normal of plane points to the non-clipped side
        
        if (clipping != null) {
            activeClipPlaneEquationBeforeModelView =
                getClipEquationInner(clipping.pos(), clipping.normal(), adjustment);
            activeClipPlaneAfterModelView =
                transformClipEquation(activeClipPlaneEquationBeforeModelView, modelView);
            
            enableClipping();
        }
        else {
            activeClipPlaneEquationBeforeModelView = null;
            disableClipping();
        }
    }
    
    private static double[] transformClipEquation(
        double[] equation, Matrix4f modelView
    ) {
        Vector4f eq =
            new Vector4f((float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]);
        Matrix4f m = new Matrix4f(modelView);
        m.invert();
        m.transpose();
        m.transform(eq);
        return new double[]{eq.x(), eq.y(), eq.z(), eq.w()};
    }
    
    private static double[] getClipEquationInner(
        Vec3 clippingPoint, Vec3 clippingDirection, double correction
    ) {
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        
        Vec3 planeNormal = clippingDirection;
        
        Vec3 portalPos = clippingPoint
            .add(planeNormal.scale(correction))
            .subtract(cameraPos);
        
        //equation: planeNormal * p + c > 0
        //-planeNormal * portalCenter = c
        double c = planeNormal.scale(-1).dot(portalPos);
        
        return new double[]{
            planeNormal.x, planeNormal.y, planeNormal.z, c
        };
    }
    
    public static void setupOuterClipping(PoseStack matrixStack, Portal portal) {
        if (!IPCGlobal.useFrontClipping) {
            return;
        }
        
        double[] clipEquationOuter = getClipEquationOuter(portal);
        
        if (clipEquationOuter != null) {
            activeClipPlaneEquationBeforeModelView = clipEquationOuter;
            activeClipPlaneAfterModelView = transformClipEquation(
                activeClipPlaneEquationBeforeModelView, matrixStack.last().pose()
            );
            enableClipping();
        }
        else {
            activeClipPlaneEquationBeforeModelView = null;
            disableClipping();
        }
    }
    
    // "double @Nullable []" is weird...
    private static double @Nullable [] getClipEquationOuter(Portal portal) {
        @Nullable Plane outerClipping = portal.getPortalShape()
            .getOuterClipping(portal.getThisSideState());
        
        if (outerClipping == null) {
            return null;
        }
        
        Vec3 planeNormal = outerClipping.normal();
        
        Vec3 cameraPos = client.gameRenderer.getMainCamera().getPosition();
        
        Vec3 portalPos = outerClipping.pos()
            .subtract(cameraPos);
        
        //equation: planeNormal * p + c > 0
        //-planeNormal * portalCenter = c
        double c = planeNormal.scale(-1).dot(portalPos);
        
        return new double[]{
            planeNormal.x, planeNormal.y, planeNormal.z, c
        };
    }
    
    public static double[] getActiveClipPlaneEquationBeforeModelView() {
        return activeClipPlaneEquationBeforeModelView;
    }
    
    public static double[] getActiveClipPlaneEquationAfterModelView() {
        return activeClipPlaneAfterModelView;
    }
    
    /**
     * Whether the portal view area is being drawn. See {@link ViewAreaRenderer}
     */
    public static boolean isDrawingPortalArea = false;

    /**
     * Called before every draw of a shader program that has the clipping equation uniform.
     * (The transformed shaders: terrain, entity, particle and portal area.)
     * <p>
     * Before 1.21.5 the uniform was updated when setting the shader in RenderSystem,
     * when rendering a terrain layer and when rendering the portal area.
     * Outside of these it was reset to not clip.
     * The clipping is only enabled during these renderings, so it's the same to follow
     * {@link #isClippingEnabled}.
     */
    public static void loadClippingEquation(Uniform clippingEquationUniform) {
        if (!IPGlobal.enableClippingMechanism) {
            return;
        }

        // with Iris, only the portal area is clipped by this uniform.
        // (the terrain is clipped by the uniform in Sodium's shader interface)
        boolean shouldClip = isClippingEnabled
            && activeClipPlaneEquationBeforeModelView != null
            && (isDrawingPortalArea || !IrisInterface.invoker.isIrisPresent());

        if (shouldClip) {
            double[] equation = activeClipPlaneEquationBeforeModelView;
            clippingEquationUniform.set(
                (float) equation[0], (float) equation[1],
                (float) equation[2], (float) equation[3]
            );
        }
        else {
            clippingEquationUniform.set(0f, 0f, 0f, 1f);
        }
    }
}
