package qouteall.imm_ptl.core.ducks;

public interface IEShader {
    /**
     * @return the location of the clipping equation uniform, -1 if the shader program doesn't have it.
     */
    int ip_getClippingEquationLocation();
}
