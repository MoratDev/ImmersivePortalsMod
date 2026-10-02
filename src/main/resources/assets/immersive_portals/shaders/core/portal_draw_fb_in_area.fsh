#version 150

uniform sampler2D DiffuseSampler;

out vec4 fragColor;

void main() {
    //screen space from -1 to 1
    //texture from 0 to 1

    // Before 1.21.6 the size was passed by the uniforms w and h.
    // Since 1.21.6 there are only uniform blocks. The size is the size of the sampled texture.
    vec2 size = vec2(textureSize(DiffuseSampler, 0));

    fragColor = texture(DiffuseSampler, gl_FragCoord.xy / size);
}
