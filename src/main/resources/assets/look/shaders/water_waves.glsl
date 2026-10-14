// Shared by the raycast shader (voxel.fsh) and the entity vertex refraction (entity_refract.glsl), so a
// refracted entity and the refracted terrain around it bend by the same waves. The includer declares
// `uniform float uWaterWaves`, the wave slope scale.

// Sine waves that tile every 64 blocks and every 128 ticks (the wrap of uTime). Wave vectors are in
// cycles per 64 blocks, slopes are the steepness of each, speeds are cycles per 128 ticks.
const int WAVES = 5;
const vec2 WAVE_K[WAVES] = vec2[WAVES](vec2(3.0, 1.0), vec2(-2.0, 4.0), vec2(7.0, -3.0), vec2(-9.0, -8.0), vec2(14.0, 11.0));
const float WAVE_S[WAVES] = float[WAVES](0.050, 0.045, 0.040, 0.030, 0.025);
const float WAVE_SPEED[WAVES] = float[WAVES](1.0, 2.0, 3.0, 5.0, 6.0);
const float WAVE_PHASE[WAVES] = float[WAVES](0.0, 1.7, 4.1, 2.3, 5.2);

// Gradient of the water height at `p` (x, z, wrapped), fading out the waves a pixel can no longer resolve
// (`foot` is the size of a pixel on the surface, in blocks).
vec2 waveSlope(vec2 p, float time, float foot) {
  vec2 g = vec2(0.0);
  for (int i = 0; i < WAVES; i++) {
    vec2 k = WAVE_K[i] * (6.2831853 / 64.0);
    float kl = length(k);
    float fade = 1.0 - smoothstep(0.4, 1.5, foot * kl);
    float ph = dot(k, p) - WAVE_SPEED[i] * (6.2831853 / 128.0) * time + WAVE_PHASE[i];
    g += (k / kl) * (WAVE_S[i] * fade * cos(ph));
  }
  return g * uWaterWaves;
}
