package qouteall.imm_ptl.core.ducks;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;

public interface IEGameRenderer {
    void ip_setLightmapTextureManager(LightTexture manager);

    boolean ip_getDoRenderHand();

    // Since 1.21.6 vanilla doesn't have the switch of rendering hand.
    void ip_setDoRenderHand(boolean cond);

    void ip_setCamera(Camera camera);

    void ip_setIsRenderingPanorama(boolean cond);

    FogRenderer ip_getFogRenderer();

    PerspectiveProjectionMatrixBuffer ip_getLevelProjectionMatrixBuffer();
}
