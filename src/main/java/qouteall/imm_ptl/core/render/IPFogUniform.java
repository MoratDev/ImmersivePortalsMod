package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.renderer.DynamicUniformStorage;
import org.joml.Vector4f;

import java.nio.ByteBuffer;

/**
 * The content of the fog uniform block. The same layout as FogRenderer#updateBuffer.
 */
public record IPFogUniform(
    Vector4f fogColor,
    float environmentalStart,
    float environmentalEnd,
    float renderDistanceStart,
    float renderDistanceEnd,
    float skyEnd,
    float cloudEnd
) implements DynamicUniformStorage.DynamicUniform {
    @Override
    public void write(ByteBuffer buffer) {
        Std140Builder.intoBuffer(buffer)
            .putVec4(fogColor)
            .putFloat(environmentalStart)
            .putFloat(environmentalEnd)
            .putFloat(renderDistanceStart)
            .putFloat(renderDistanceEnd)
            .putFloat(skyEnd)
            .putFloat(cloudEnd);
    }
}
