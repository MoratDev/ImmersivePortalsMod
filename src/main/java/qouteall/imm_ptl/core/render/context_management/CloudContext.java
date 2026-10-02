package qouteall.imm_ptl.core.render.context_management;

import net.minecraft.client.CloudStatus;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.q_misc_util.Helper;

import java.util.ArrayList;

/**
 * {@link net.minecraft.client.render.WorldRenderer#renderClouds(MatrixStack, float, double, double, double)}
 */
public class CloudContext {
    
    //keys
    // In 1.21.1 the keys were the cloud block x y z and the cloud color.
    // Since 1.21.2 the cloud mesh depends on the cloud cell x z,
    // whether the camera is above/inside/below the clouds, and the cloud status.
    // The cloud color is applied when drawing.
    public int cellX = 0;
    public int cellZ = 0;
    // the ordinal of CloudRenderer.RelativeCameraPos
    public int relativeCameraPos = 0;
    public CloudStatus cloudStatus = null;
    public ResourceKey<Level> dimension = null;

    public VertexBuffer cloudsBuffer = null;
    public boolean cloudsBufferEmpty = false;
    
    public static final ArrayList<CloudContext> contexts = new ArrayList<>();
    
    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(CloudContext::cleanup);
        ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.register(dim -> cleanup());
    }
    
    public CloudContext() {
    
    }
    
    private static void cleanup() {
        for (CloudContext context : contexts) {
            context.dispose();
        }
        contexts.clear();
    }
    
    public void dispose() {
        if (cloudsBuffer != null) {
            cloudsBuffer.close();
            cloudsBuffer = null;
        }
    }
    
    @Nullable
    public static CloudContext findAndTakeContext(
        int cellX, int cellZ, int relativeCameraPos,
        CloudStatus cloudStatus, ResourceKey<Level> dimension
    ) {
        int i = Helper.indexOf(contexts, c ->
            c.cellX == cellX &&
                c.cellZ == cellZ &&
                c.relativeCameraPos == relativeCameraPos &&
                c.cloudStatus == cloudStatus &&
                c.dimension == dimension
        );
        
        if (i == -1) {
            return null;
        }
        
        CloudContext result = contexts.get(i);
        contexts.remove(i);
        
        return result;
    }
    
    public static void appendContext(CloudContext context) {
        contexts.add(context);
        
        if (contexts.size() > 15) {
            contexts.remove(0).dispose();
        }
    }
}
