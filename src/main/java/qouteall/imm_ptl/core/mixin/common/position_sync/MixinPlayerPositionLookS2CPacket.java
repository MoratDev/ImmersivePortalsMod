package qouteall.imm_ptl.core.mixin.common.position_sync;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;

/**
 * Attaches the player dimension to the position sync packet.
 *
 * Since 1.21.2 the packet is a record and its codec is made by
 * {@link StreamCodec#composite}. There is no longer a write method or a
 * reading constructor to inject into. So the codec is wrapped.
 */
@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinPlayerPositionLookS2CPacket implements IEPlayerPositionLookS2CPacket {
    @Shadow
    @Final
    @Mutable
    public static StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> STREAM_CODEC;

    @Unique
    private ResourceKey<Level> playerDimension;

    @Override
    public ResourceKey<Level> ip_getPlayerDimension() {
        return playerDimension;
    }

    @Override
    public void ip_setPlayerDimension(ResourceKey<Level> dimension) {
        playerDimension = dimension;
    }

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void onClassInit(CallbackInfo ci) {
        StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> vanillaCodec = STREAM_CODEC;

        STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                vanillaCodec.encode(buf, packet);

                // only the server sends it
                ResourceKey<Level> dimension =
                    ((IEPlayerPositionLookS2CPacket) (Object) packet).ip_getPlayerDimension();
                Validate.notNull(dimension, "player dimension is null in position packet");
                buf.writeResourceKey(dimension);
            },
            buf -> {
                ClientboundPlayerPositionPacket packet = vanillaCodec.decode(buf);

                // only the client receives it
                if (ImmPtlNetworkConfig.doesServerHaveImmPtl()) {
                    ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
                    ((IEPlayerPositionLookS2CPacket) (Object) packet).ip_setPlayerDimension(dimension);
                }

                return packet;
            }
        );
    }
}
