package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(CoreShaders.class)
public interface CoreShadersAccessor {
    /**
     * The shader programs in this list are compiled when resources are (re)loaded.
     */
    @Accessor("PROGRAMS")
    static List<ShaderProgram> ip_getPrograms() {
        throw new AssertionError();
    }
}
