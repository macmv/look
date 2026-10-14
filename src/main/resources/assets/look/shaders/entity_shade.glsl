// The fragment half of entity_refract.glsl, pasted into vanilla's core fragment shaders by
// EntityRefraction: entities, particles and the like get the two things the raycast shader does to
// terrain that vanilla's lightmap cannot, wrapped around vanilla's `linear_fog` call.
//
//  - Sun shadow: the shadow map ShadowPass built, sampled at the real position of the fragment. Vanilla
//    has no separate sun term (the lightmap bakes it in), so shadow only takes a share of the color away.
//  - Water: what is seen through `look_depth` blocks of water is absorbed channel by channel, red first,
//    plus the water's own scattered light, as waterAbsorb in voxel.fsh does for terrain.
//
// This runs after tonemapping, in LDR, so the numbers are tuned by eye rather than shared with voxel.fsh.

uniform int LookShadow;               // 1 in the world pass
uniform float LookShadowStrength;     // share of the color that is direct light, 0 at night and under cloud
uniform vec3 LookWaterColor;          // albedo of the water around the camera
uniform float LookWaterLight;         // the light the water scatters back, 0..1

in vec3 look_pos;
in float look_depth;
in float look_sky;
in float look_cos;

#include "shadow_lookup.glsl"

const float LOOK_WATER_ABSORB = 0.3;   // WATER_ABSORB of voxel.fsh
const float LOOK_WATER_F0 = 0.03;      // WATER_F0 of voxel.fsh
const float LOOK_SURFACE_TINT = 0.2;   // share of the water's own color the surface texture lays over what is below
const vec3 LOOK_SKY = vec3(0.6, 0.75, 1.0);   // what the surface mirrors, scaled by daylight

vec4 look_shade(vec4 c) {
  if (LookShadow == 0) return c;

  // Light toward the sun is checked, not the surface normal: vanilla's face shading already has that.
  // Under a roof the lightmap is dark already, so the shadow only applies to sky lit vertices.
  if (LookShadowStrength > 0.0) {
    float gate = smoothstep(0.35, 0.9, look_sky);
    float vis = sunVisibility(look_pos, uShadowDir);
    c.rgb *= 1.0 - LookShadowStrength * gate * (1.0 - vis);
  }

  if (look_depth > 0.0) {
    vec3 sigma = (vec3(1.0) - clamp(LookWaterColor, 0.0, 0.95)) * LOOK_WATER_ABSORB;
    vec3 tr = exp(-sigma * look_depth);
    c.rgb = c.rgb * tr + LookWaterColor * LookWaterLight * (1.0 - tr);

    // The surface in front of it: the water texture over the color, and the sky mirrored by Fresnel.
    // Both are needed or entities look like they are in a hole in the water.
    float F = LOOK_WATER_F0 + (1.0 - LOOK_WATER_F0) * pow(1.0 - clamp(look_cos, 0.0, 1.0), 5.0);
    c.rgb = mix(c.rgb, LookWaterColor * (0.3 + LookWaterLight), LOOK_SURFACE_TINT);
    c.rgb = mix(c.rgb, LOOK_SKY * (0.1 + 1.4 * LookWaterLight), F);
  }
  return c;
}
