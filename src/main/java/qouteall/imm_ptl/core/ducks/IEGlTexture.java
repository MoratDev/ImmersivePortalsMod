package qouteall.imm_ptl.core.ducks;

public interface IEGlTexture {
    /**
     * @return Whether it's a depth-stencil texture.
     */
    boolean ip_hasStencil();

    void ip_setHasStencil(boolean cond);
}
