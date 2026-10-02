package qouteall.imm_ptl.core.ducks;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

public interface IEFogRenderer {
    /**
     * @return the fog uniform buffer of the world rendering that's going on.
     */
    @Nullable
    GpuBufferSlice ip_getCurrentFog();

    void ip_setCurrentFog(@Nullable GpuBufferSlice fog);

    Vector4f ip_computeFogColor(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount, boolean isFoggy
    );
}
