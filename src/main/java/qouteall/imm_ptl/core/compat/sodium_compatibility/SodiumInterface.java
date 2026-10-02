package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.mixin.sodium.IESodiumWorldRenderer;
import qouteall.imm_ptl.core.render.FrustumCuller;

@Environment(EnvType.CLIENT)
public class SodiumInterface {
    
    @Nullable
    public static FrustumCuller frustumCuller = null;
    
    public static class Invoker {
        public boolean isSodiumPresent() {
            return false;
        }
        
        public Object createNewContext(int renderDistance) {
            return null;
        }
        
        public void switchContextWithCurrentWorldRenderer(Object context) {
        
        }
        
        public void markSpriteActive(TextureAtlasSprite sprite) {
        
        }
        
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
        
        }
        
        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
        
        }
    }
    
    public static Invoker invoker = new Invoker();
    
    public static class OnSodiumPresent extends Invoker {
        @Override
        public boolean isSodiumPresent() {
            return true;
        }
        
        @Override
        public Object createNewContext(int renderDistance) {
            return new SodiumRenderingContext(renderDistance);
        }
        
        @Override
        public void switchContextWithCurrentWorldRenderer(Object context) {
            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer).sodium$getWorldRenderer();
            swr.scheduleTerrainUpdate();
            
            RenderSectionManager renderSectionManager =
                ((IESodiumWorldRenderer) swr).ip_getRenderSectionManager();
            
            SodiumRenderingContext sodiumContext = (SodiumRenderingContext) context;

            ((IESodiumRenderSectionManager) renderSectionManager).ip_swapContext(sodiumContext);

            // Since Sodium 0.7 the world renderer keeps the fog parameters for drawing terrain.
            // The outer world rendering draws the translucent terrain after rendering portals.
            IESodiumWorldRenderer ieSwr = (IESodiumWorldRenderer) swr;
            FogParameters currentFog = ieSwr.ip_getLastFogParameters();
            if (sodiumContext.fogParameters != null) {
                ieSwr.ip_setLastFogParameters(sodiumContext.fogParameters);
            }
            sodiumContext.fogParameters = currentFog;
            
            swr.scheduleTerrainUpdate();
        }
        
        @Override
        public void markSpriteActive(TextureAtlasSprite sprite) {
            SpriteUtil.INSTANCE.markSpriteActive(sprite);
        }
        
        @Override
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
            ChunkTrackerHolder.get(world)
                .onChunkStatusAdded(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
        
        @Override
        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
            ChunkTrackerHolder.get(world)
                .onChunkStatusRemoved(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
    }
    
}
