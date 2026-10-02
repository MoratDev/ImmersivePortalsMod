package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.jetbrains.annotations.Nullable;

public class SodiumRenderingContext {
    public SortedRenderLists renderLists;
    
    public int renderDistance;

    // the fog parameters of the outer world rendering, while the portal content is being rendered
    public @Nullable FogParameters fogParameters = null;
    
    public SodiumRenderingContext(int renderDistance) {
        this.renderDistance = renderDistance;
        this.renderLists = SortedRenderLists.empty();
    }
}
