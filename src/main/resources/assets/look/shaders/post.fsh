#version 330 core

// Enhanced final post: LDR effects on the finished frame (entities, particles, translucent layer
// included). Reads a copy of the main framebuffer and the depth of the voxel world pass, writes back
// over the whole main framebuffer. Every effect has its own switch.

uniform sampler2D uScene;   // copy of the finished frame
uniform sampler2D uDepth;   // depth of the world pass (1 where nothing was hit); entities are not in it
uniform sampler2D uFocus;   // 1x1 smoothed focus distance
uniform vec2 uSize;
uniform float uTime;
uniform int uFrame;

uniform int uFluid;         // 0 none, 1 water, 2 lava
uniform float uFluidAmp;

uniform int uVignette;
uniform float uVignetteStrength;

uniform int uCA;
uniform float uCAStrength;

uniform int uGrain;
uniform float uGrainStrength;

uniform int uSharpen;
uniform float uSharpenStrength;

uniform int uDof;
uniform float uDofRadius;   // pixels at full defocus
uniform float uDofStrength;
uniform vec2 uProjAB;       // projection[2][2], projection[3][2]

uniform int uMotion;
uniform float uMotionStrength;
uniform mat4 uInvViewProj;
uniform mat4 uPrevViewProj;
uniform vec3 uCamDelta;     // camera position now minus last frame

uniform int uFlare;
uniform float uFlareStrength;
uniform vec3 uSunUv;        // xy: sun position in uv, z: 0 when it cannot be seen

in vec2 vNdc;
out vec4 fragColor;

vec3 scene(vec2 uv) {
  return textureLod(uScene, uv, 0.0).rgb;
}

float depthAt(vec2 uv) {
  ivec2 p = clamp(ivec2(uv * uSize), ivec2(0), ivec2(uSize) - 1);
  return texelFetch(uDepth, p, 0).r;
}

float linearDist(float d) {
  if (d >= 0.99999) return 4096.0;
  return uProjAB.y / (d * 2.0 - 1.0 + uProjAB.x);
}

float cocAt(vec2 uv, float focus) {
  float dist = linearDist(depthAt(uv));
  return clamp(abs(1.0 / focus - 1.0 / dist) * focus * uDofStrength, 0.0, 1.0);
}

// Interleaved gradient noise: cheap and close to blue.
float ign(vec2 p) {
  return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

vec3 depthOfField(vec2 uv) {
  float focus = texelFetch(uFocus, ivec2(0), 0).r;
  float coc = cocAt(uv, focus);
  float radius = coc * uDofRadius * (uSize.y / 1080.0);
  if (radius < 0.75) return scene(uv);

  vec3 sum = scene(uv);
  float wsum = 1.0;
  float rot = ign(gl_FragCoord.xy) * 6.2831853;
  for (int i = 0; i < 24; i++) {
    float r = sqrt((float(i) + 0.5) / 24.0);
    float a = float(i) * 2.399963 + rot;
    vec2 suv = uv + vec2(cos(a), sin(a)) * r * radius / uSize;
    float w = 0.2 + cocAt(suv, focus);
    sum += scene(suv) * w;
    wsum += w;
  }
  return sum / wsum;
}

vec3 motionBlur(vec2 uv) {
  float d = depthAt(uv);
  vec4 clip = uInvViewProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
  vec3 rel = clip.xyz / clip.w;
  vec4 prev = uPrevViewProj * vec4(rel + uCamDelta, 1.0);
  vec2 prevUv = prev.xy / prev.w * 0.5 + 0.5;
  vec2 vel = (uv - prevUv) * uMotionStrength;
  float len = length(vel);
  if (len > 0.06) vel *= 0.06 / len;
  if (len * uSize.y < 1.0) return scene(uv);

  vec3 sum = vec3(0.0);
  float jitter = ign(gl_FragCoord.xy + float(uFrame) * 5.588238) - 0.5;
  for (int i = 0; i < 12; i++) {
    float t = (float(i) + 0.5 + jitter) / 12.0 - 0.5;
    sum += scene(uv + vel * t);
  }
  return sum / 12.0;
}

vec3 flare(vec2 uv) {
  vec2 aspect = vec2(uSize.x / uSize.y, 1.0);
  vec2 sun = uSunUv.xy;

  // How much of the sun is not behind terrain: a grid of depth samples around it.
  float seen = 0.0;
  for (int i = 0; i < 9; i++) {
    vec2 o = vec2(i % 3 - 1, i / 3 - 1) * 0.008;
    vec2 p = sun + o;
    if (p.x < 0.0 || p.y < 0.0 || p.x > 1.0 || p.y > 1.0) continue;
    seen += depthAt(p) >= 0.99999 ? 1.0 : 0.0;
  }
  seen /= 9.0;
  float vis = seen * uSunUv.z;
  if (vis <= 0.0) return vec3(0.0);

  vec2 toCenter = vec2(0.5) - sun;
  vec3 col = vec3(0.0);
  // Glow around the sun.
  float dSun = length((uv - sun) * aspect);
  col += vec3(1.0, 0.85, 0.6) * 0.5 * exp(-dSun * 9.0);
  // Ghosts along the line from the sun through the screen center.
  float ghosts[4] = float[4](0.35, 0.8, 1.35, 1.9);
  float sizes[4] = float[4](0.05, 0.09, 0.04, 0.12);
  vec3 tints[4] = vec3[4](vec3(1.0, 0.7, 0.4), vec3(0.5, 0.8, 1.0), vec3(0.6, 1.0, 0.6), vec3(1.0, 0.6, 0.9));
  for (int i = 0; i < 4; i++) {
    vec2 p = sun + toCenter * ghosts[i];
    float d = length((uv - p) * aspect);
    float ring = smoothstep(sizes[i], sizes[i] * 0.7, d) * 0.12;
    col += tints[i] * ring;
  }
  // Fade out as the sun leaves the screen.
  float edge = 1.0 - smoothstep(0.55, 0.9, length((sun - 0.5) * 2.0));
  return col * vis * edge * uFlareStrength;
}

void main() {
  vec2 uv = gl_FragCoord.xy / uSize;

  if (uFluid != 0) {
    // Slow wobble; lava is thicker and slower than water.
    float freq = uFluid == 2 ? 9.0 : 18.0;
    float speed = uFluid == 2 ? 0.8 : 1.6;
    float t = uTime * speed;
    uv += uFluidAmp * vec2(sin(uv.y * freq + t) + 0.5 * sin(uv.y * freq * 2.3 - t * 1.3),
                           cos(uv.x * freq * 0.8 + t * 1.1));
  }

  vec3 col;
  if (uDof != 0) col = depthOfField(uv);
  else if (uMotion != 0) col = motionBlur(uv);
  else col = scene(uv);

  if (uCA != 0) {
    vec2 dir = (uv - 0.5) * uCAStrength;
    float r = scene(uv + dir).r;
    float b = scene(uv - dir).b;
    col.r += r - scene(uv).r;
    col.b += b - scene(uv).b;
  }

  if (uSharpen != 0) {
    vec2 px = 1.0 / uSize;
    vec3 c = scene(uv);
    vec3 blur = (scene(uv + vec2(px.x, 0)) + scene(uv - vec2(px.x, 0)) + scene(uv + vec2(0, px.y)) + scene(uv - vec2(0, px.y))) * 0.25;
    col += clamp((c - blur) * uSharpenStrength * 2.0, -0.12, 0.12);
  }

  if (uFlare != 0) col += flare(uv);

  if (uVignette != 0) {
    vec2 q = (uv - 0.5) * vec2(uSize.x / uSize.y, 1.0);
    float v = smoothstep(0.3, 1.0, length(q));
    col *= 1.0 - uVignetteStrength * v;
  }

  if (uGrain != 0) {
    float n = ign(gl_FragCoord.xy + float(uFrame % 64) * 5.588238) - 0.5;
    col += n * uGrainStrength;
  }

  fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
