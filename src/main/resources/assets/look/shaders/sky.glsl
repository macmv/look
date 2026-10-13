// Enhanced sky, clouds and aerial perspective (docs/enhanced.md sections 4.1 to 4.3). Pasted into
// voxel.fsh by VoxelRenderer inside #ifdef ENHANCED, after the uniforms and before main(), so it can
// use uSunDir, uMoonDir, uMoonPhase, uRain, uThunder, uCamBlock, uMaxDist and uSeaLevel.
//
// Entry points used by main():
//   skyPixel(w, d)                       color of a ray that hit nothing (sky, sun, moon, stars, clouds)
//   atmosphereHit(c, w, d, t)            fog and clouds over a hit surface at distance t
// For other passes:
//   skyTransmittance(L)                  color of direct light from direction L at the camera, 0 below the horizon
//   cloudShadow(worldPos, L)             1 = sunlit, 0 = in cloud shadow; multiply into sun visibility (3.1)
//   aerialPerspective(c, t, d, y0)       fog over any color
//
// Colors leave here as HDR radiance (the sun disc is well above 1, so it blooms); the resolve pass
// applies exposure and the tonemap, the same as for terrain.

uniform int uSkyFeatures;         // bit 0 sky, 1 clouds, 2 fog (SkyState)
uniform int uCloudStyle;          // 0 off, 1 vanilla grid, 2 soft
uniform float uCloudHeight;       // bottom of the cloud layer, blocks
uniform vec2 uCloudScroll;        // how far the wind has moved the pattern, blocks (x, z)
uniform vec3 uCloudColor;         // vanilla's cloud color: dimmed by night and rain

const float SKY_RE = 6371e3;
const float SKY_RA = 6431e3;
const vec3 SKY_BETA_R = vec3(5.8e-6, 13.5e-6, 33.1e-6);
const float SKY_BETA_M = 21e-6;
const float SKY_HR = 8e3;
const float SKY_HM = 1.2e3;
const float SKY_SUN_I = 22.0;

const float CLOUD_CELL = 12.0;
const float CLOUD_THICK = 4.0;       // vanilla grid
const float CLOUD_SOFT_THICK = 36.0; // soft layer
const float CLOUD_FAR = 2400.0;

float skyHash(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

vec3 skyHash3(vec3 p) {
  p = fract(p * vec3(0.1031, 0.1030, 0.0973));
  p += dot(p, p.yxz + 33.33);
  return fract((p.xxy + p.yxx) * p.zyx);
}

float skyNoise3(vec3 p) {
  vec3 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(
      mix(mix(skyHash(i), skyHash(i + vec3(1, 0, 0)), f.x), mix(skyHash(i + vec3(0, 1, 0)), skyHash(i + vec3(1, 1, 0)), f.x), f.y),
      mix(mix(skyHash(i + vec3(0, 0, 1)), skyHash(i + vec3(1, 0, 1)), f.x), mix(skyHash(i + vec3(0, 1, 1)), skyHash(i + vec3(1, 1, 1)), f.x), f.y),
      f.z);
}

// How day-like the sun makes it: 0 at night, 1 in daylight.
float skyDay() {
  return smoothstep(-0.18, 0.2, uSunDir.y);
}

// Distance along a ray from inside the sphere of radius r (centered at the origin) to its surface.
float skyExit(vec3 o, vec3 d, float r) {
  float b = dot(o, d);
  float c = dot(o, o) - r * r;
  return -b + sqrt(max(b * b - c, 0.0));
}

bool skyHitsPlanet(vec3 o, vec3 d) {
  float b = dot(o, d);
  float c = dot(o, o) - SKY_RE * SKY_RE;
  return b < 0.0 && b * b - c > 0.0;
}

const vec3 SKY_EYE = vec3(0.0, SKY_RE + 200.0, 0.0);

// Optical depth (Rayleigh, Mie) from p toward L out of the atmosphere. x < 0 when blocked by the planet.
vec3 skyLightDepth(vec3 p, vec3 L, int n) {
  if (skyHitsPlanet(p, L)) return vec3(-1.0);
  float len = skyExit(p, L, SKY_RA) / float(n);
  float odR = 0.0, odM = 0.0;
  for (int i = 0; i < n; i++) {
    float h = length(p + L * (len * (float(i) + 0.5))) - SKY_RE;
    odR += exp(-h / SKY_HR) * len;
    odM += exp(-h / SKY_HM) * len;
  }
  return vec3(odR, odM, 0.0);
}

// Direct light from direction L at the camera: the sun's color, reddening and fading toward the horizon.
vec3 skyTransmittance(vec3 L) {
  vec3 od = skyLightDepth(SKY_EYE, L, 8);
  if (od.x < 0.0) return vec3(0.0);
  return exp(-(SKY_BETA_R * od.x + SKY_BETA_M * 1.1 * od.y));
}

// Single scattering through the atmosphere along d (unit, d.y >= 0), in radiance.
vec3 atmosphereScatter(vec3 d, vec3 L, int nView, int nLight) {
  // Nearly all the air is in the lowest few km, so near the horizon only march the first stretch.
  float len = min(skyExit(SKY_EYE, d, SKY_RA), 110e3) / float(nView);
  float mu = dot(d, L);
  float g = 0.76;
  float phaseR = 3.0 / (16.0 * 3.14159265) * (1.0 + mu * mu);
  float phaseM = 3.0 / (8.0 * 3.14159265) * ((1.0 - g * g) * (1.0 + mu * mu)) / ((2.0 + g * g) * pow(1.0 + g * g - 2.0 * g * mu, 1.5));

  float odR = 0.0, odM = 0.0;
  vec3 sumR = vec3(0.0), sumM = vec3(0.0);
  for (int i = 0; i < nView; i++) {
    vec3 p = SKY_EYE + d * (len * (float(i) + 0.5));
    float h = length(p) - SKY_RE;
    float hr = exp(-h / SKY_HR) * len;
    float hm = exp(-h / SKY_HM) * len;
    odR += hr;
    odM += hm;
    vec3 ld = skyLightDepth(p, L, nLight);
    if (ld.x < 0.0) continue;
    vec3 att = exp(-(SKY_BETA_R * (odR + ld.x) + SKY_BETA_M * 1.1 * (odM + ld.y)));
    sumR += att * hr;
    sumM += att * hm;
  }
  vec3 raw = (sumR * SKY_BETA_R * phaseR + sumM * SKY_BETA_M * phaseM) * SKY_SUN_I;
  // Single scattering turns the horizon too yellow; multiple scattering washes it toward white.
  return mix(raw, vec3(dot(raw, vec3(0.3, 0.55, 0.15))) * vec3(0.95, 1.0, 1.1), 0.12);
}

// The sky without sun, moon, stars or clouds, as radiance. Directions below the horizon use the
// horizon color, which is what distant terrain fades into.
vec3 skyBackground(vec3 d, int nView, int nLight) {
  vec3 dc = normalize(vec3(d.x, max(d.y, 0.0) + 0.012, d.z));
  vec3 raw = atmosphereScatter(dc, uSunDir, nView, nLight);

  // Moonlight and starlight: a dim blue that gathers at the horizon, brighter around a full moon.
  float moonBright = 0.5 + 0.5 * cos(float(uMoonPhase) * 0.785398);
  float night = 1.0 - skyDay();
  raw += vec3(0.0016, 0.0030, 0.0075) * (0.5 + 0.6 * moonBright) * night * (1.0 + 1.5 * (1.0 - dc.y));

  // Overcast: grey the sky and darken it with the storm.
  float lum = dot(raw, vec3(0.3, 0.55, 0.15));
  vec3 grey = vec3(lum) * vec3(0.85, 0.88, 0.95) * (1.0 - 0.45 * uThunder);
  raw = mix(raw, grey, clamp(uRain * 0.85, 0.0, 1.0));
  return raw;
}

vec3 skyStars(vec3 d) {
  float vis = (1.0 - smoothstep(-0.2, 0.02, uSunDir.y)) * (1.0 - uRain) * smoothstep(0.0, 0.18, d.y);
  if (vis <= 0.0) return vec3(0.0);

  // Fixed to the celestial sphere: undo the sun's turn about the z axis.
  vec3 p = vec3(d.x * uSunDir.y - d.y * uSunDir.x, d.x * uSunDir.x + d.y * uSunDir.y, d.z);
  vec3 g = p * 150.0;
  vec3 cell = floor(g);
  vec3 h = skyHash3(cell);
  if (h.x > 0.035) return vec3(0.0);
  vec3 star = (cell + skyHash3(cell + 17.0)) * (150.0 / max(length(cell + 0.5), 1.0));
  float dist = length(g - normalize(star) * 150.0);
  float r = 0.28 + 0.4 * h.y * h.y;
  float b = smoothstep(r, 0.0, dist) * (0.35 + 1.4 * h.y * h.y * h.y);
  vec3 tint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.85, 0.7), h.z);
  return tint * b * vis;
}

// Sun disc and moon with its phase, as radiance.
vec3 skySunMoon(vec3 d) {
  vec3 col = vec3(0.0);
  float clear = 1.0 - uRain;

  // Sun: a disc a few degrees across (bigger than the real one, like the vanilla billboard).
  float sunR = 0.042;
  float cs = dot(d, uSunDir);
  if (cs > cos(sunR * 1.1) && d.y > -0.02) {
    float disc = smoothstep(cos(sunR * 1.1), cos(sunR * 0.95), cs);
    col += skyTransmittance(uSunDir) * disc * 6.0 * clear;
  }

  // Moon: the lit part follows the phase (0 full, 4 new).
  float moonR = 0.036;
  float cm = dot(d, uMoonDir);
  if (cm > cos(moonR * 1.2) && d.y > -0.02) {
    vec3 right = normalize(cross(vec3(0.0, 0.0, 1.0), uMoonDir));
    vec3 up = cross(uMoonDir, right);
    vec2 uv = vec2(dot(d, right), dot(d, up)) / moonR;
    float r2 = dot(uv, uv);
    if (r2 < 1.0) {
      vec3 n = vec3(uv, sqrt(1.0 - r2));
      float th = float(uMoonPhase) * 0.785398;
      vec3 Lm = vec3(sin(th), 0.0, cos(th));
      float lit = smoothstep(-0.03, 0.1, dot(n, Lm));
      float maria = 0.72 + 0.28 * skyNoise3(vec3(uv * 3.5, 1.7));
      float edge = smoothstep(1.0, 0.94, r2);
      vec3 moon = vec3(0.92, 0.95, 1.0) * maria * lit + vec3(0.02, 0.03, 0.05);
      col += moon * edge * 0.95 * clear * (1.0 - 0.6 * skyDay());
    }
  }
  return col;
}

float skyIgn() {
  return fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
}

// --- Clouds ---------------------------------------------------------------------------------------

float cloudCoverage() {
  return 0.5 + 0.28 * uRain + 0.1 * uThunder;
}

// Blocky layer: is the 12 block cell filled. Smooth value noise on the cell grid, thresholded.
bool cloudCellFilled(vec2 cell) {
  vec2 c = cell + 31.0;
  float n = 0.62 * skyNoise3(vec3(c * 0.17, 3.0)) + 0.38 * skyNoise3(vec3(c * 0.43, 9.0));
  return n > 1.0 - cloudCoverage() - 0.02;
}

// Vanilla-style clouds. Returns rgb (display) and alpha, and the distance of the surface hit in t.
vec4 cloudGrid(vec3 w, vec3 d, float tLimit, out float t) {
  t = 0.0;
  float y0 = uCloudHeight, y1 = uCloudHeight + CLOUD_THICK;
  float ta = (y0 - w.y) / d.y, tb = (y1 - w.y) / d.y;
  float tmin = max(min(ta, tb), 0.0);
  float tmax = min(max(ta, tb), min(tLimit, CLOUD_FAR));
  if (tmax <= tmin) return vec4(0.0);

  vec3 p = w + d * tmin;
  vec2 q = p.xz + uCloudScroll;
  vec2 cell = floor(q / CLOUD_CELL);
  vec2 stepDir = sign(d.xz);
  vec2 sd = vec2(abs(d.x) < 1e-5 ? 1e-5 : d.x, abs(d.z) < 1e-5 ? 1e-5 : d.z);
  vec2 inv = 1.0 / abs(sd);
  vec2 nextEdge = (cell + max(stepDir, vec2(0.0))) * CLOUD_CELL;
  vec2 tNext = tmin + (nextEdge - q) / sd;
  tNext = max(tNext, vec2(tmin));

  float tEnter = tmin;
  int sideAxis = -1; // -1: entered through the top or bottom
  for (int i = 0; i < 96; i++) {
    if (tEnter >= tmax) break;
    if (cloudCellFilled(cell)) {
      float shade;
      if (sideAxis < 0) shade = d.y < 0.0 ? 1.0 : 0.7;
      else shade = sideAxis == 0 ? 0.9 : 0.8;
      if (w.y > y0 && w.y < y1 && tEnter == 0.0) shade = 0.85; // camera inside a cloud
      t = tEnter;
      vec3 st = skyTransmittance(uSunDir);
      vec3 sunTint = mix(vec3(1.0), st / max(max(st.r, max(st.g, st.b)), 1e-3), 0.8 * skyDay());
      vec3 col = min(uCloudColor * shade * sunTint * 1.12, vec3(1.0));
      float fade = 1.0 - smoothstep(0.55 * CLOUD_FAR, CLOUD_FAR, tEnter);
      return vec4(col, 0.88 * fade);
    }
    if (tNext.x < tNext.y) {
      tEnter = tNext.x;
      tNext.x += CLOUD_CELL * inv.x;
      cell.x += stepDir.x;
      sideAxis = 0;
    } else {
      tEnter = tNext.y;
      tNext.y += CLOUD_CELL * inv.y;
      cell.y += stepDir.y;
      sideAxis = 1;
    }
  }
  return vec4(0.0);
}

float cloudSoftDensity(vec3 p, int octaves) {
  float h = clamp((p.y - uCloudHeight) / CLOUD_SOFT_THICK, 0.0, 1.0);
  float profile = smoothstep(0.0, 0.18, h) * smoothstep(1.0, 0.55, h);
  vec3 q = vec3(p.x + uCloudScroll.x, p.y * 1.7, p.z + uCloudScroll.y) * 0.0085;
  float n = 0.0, amp = 0.55, norm = 0.0;
  for (int i = 0; i < 4; i++) {
    if (i >= octaves) break;
    n += amp * skyNoise3(q + float(i) * 11.3);
    norm += amp;
    q *= 2.17;
    amp *= 0.5;
  }
  n /= norm;
  float cut = 1.0 - cloudCoverage() + 0.02;
  return clamp((n - cut) * 5.5, 0.0, 1.0) * profile;
}

float cloudHG(float c, float g) {
  float g2 = g * g;
  return (1.0 - g2) / (4.0 * 3.14159265 * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}

// Soft volumetric layer: a short march through a noise slab with a few light samples. rgb is
// premultiplied by alpha's coverage (it is the in-scattered light), a is 1 - transmittance.
vec4 cloudSoft(vec3 w, vec3 d, float tLimit, out float t) {
  t = 0.0;
  float y0 = uCloudHeight, y1 = uCloudHeight + CLOUD_SOFT_THICK;
  float ta = (y0 - w.y) / d.y, tb = (y1 - w.y) / d.y;
  float tmin = max(min(ta, tb), 0.0);
  float tmax = min(max(ta, tb), min(tLimit, CLOUD_FAR));
  if (tmax <= tmin) return vec4(0.0);
  tmax = min(tmax, tmin + 900.0);

  const int STEPS = 24;
  float dt = (tmax - tmin) / float(STEPS);
  float t0 = tmin + dt * skyIgn();

  float day = skyDay();
  vec3 sunCol = skyTransmittance(uSunDir) * 1.35 * (1.0 - 0.7 * uRain);
  vec3 L = uSunDir;
  float mu = dot(d, L);
  float phase = mix(cloudHG(mu, 0.62), cloudHG(mu, -0.2), 0.45) * 4.0 * 3.14159265 * 0.45;
  vec3 ambient = uCloudColor * mix(0.7, 0.95, day) * (1.0 - 0.3 * uRain);

  float T = 1.0;
  vec3 light = vec3(0.0);
  float tFirst = -1.0;
  for (int i = 0; i < STEPS; i++) {
    float ts = t0 + dt * float(i);
    vec3 p = w + d * ts;
    float dens = cloudSoftDensity(p, 3);
    if (dens <= 0.001) continue;
    if (tFirst < 0.0) tFirst = ts;

    // Light that gets through the cloud toward the sun.
    float depth = 0.0;
    for (int j = 1; j <= 3; j++) depth += cloudSoftDensity(p + L * (float(j) * 7.0), 2);
    float tSun = exp(-depth * 2.4);
    float h = clamp((p.y - uCloudHeight) / CLOUD_SOFT_THICK, 0.0, 1.0);
    vec3 s = sunCol * tSun * phase * (1.0 - exp(-dens * 3.0)) * 1.1 + ambient * (0.7 + 0.3 * h);

    float ext = dens * 0.2 * dt;
    float stepT = exp(-ext);
    light += T * (1.0 - stepT) * s;
    T *= stepT;
    if (T < 0.02) break;
  }
  if (tFirst < 0.0) return vec4(0.0);
  t = tFirst;
  float fade = 1.0 - smoothstep(0.5 * CLOUD_FAR, CLOUD_FAR, tFirst);
  float a = (1.0 - T) * fade;
  // light is already weighted by coverage; turn it into an unpremultiplied color.
  vec3 col = (1.0 - T) > 1e-4 ? light / (1.0 - T) : vec3(0.0);
  return vec4(min(col, vec3(1.3)), a);
}

// Clouds along a ray from world position w, nearer than tLimit.
vec4 cloudLayer(vec3 w, vec3 d, float tLimit, out float t) {
  t = 0.0;
  if ((uSkyFeatures & 2) == 0 || uCloudStyle == 0 || abs(d.y) < 1e-4) return vec4(0.0);
  return uCloudStyle == 1 ? cloudGrid(w, d, tLimit, t) : cloudSoft(w, d, tLimit, t);
}

// Shadow of the clouds on the point worldPos for light from direction L: 1 = lit, 0 = dark. For the
// lighting model to multiply into its sun visibility. Cheap: one density sample on the layer.
float cloudShadow(vec3 worldPos, vec3 L) {
  if ((uSkyFeatures & 2) == 0 || uCloudStyle == 0 || L.y < 0.03) return 1.0;
  float mid = uCloudHeight + (uCloudStyle == 1 ? 0.5 * CLOUD_THICK : 0.45 * CLOUD_SOFT_THICK);
  if (worldPos.y > mid) return 1.0;
  vec3 p = worldPos + L * ((mid - worldPos.y) / L.y);
  float cover;
  if (uCloudStyle == 1) {
    cover = cloudCellFilled(floor((p.xz + uCloudScroll) / CLOUD_CELL)) ? 0.85 : 0.0;
  } else {
    cover = 1.0 - exp(-cloudSoftDensity(p, 2) * 2.2);
  }
  return 1.0 - 0.75 * cover * clamp(L.y * 6.0, 0.0, 1.0);
}

// --- Fog ------------------------------------------------------------------------------------------

// Fog over color c for a ray from world height y0 along d that travels t blocks: the colors dim with
// distance and the sky's horizon color is scattered in. Thicker in rain and at night, in low air, and
// reaching full thickness at the edge of the render range so the world fades into the sky.
vec3 aerialFog(vec3 c, float t, vec3 d, float y0, bool border) {
  if ((uSkyFeatures & 4) == 0 || gUnder) return c;

  float ext = 0.00085 * (1.0 + 5.0 * uRain + 3.0 * uThunder) * (1.0 + 0.6 * (1.0 - skyDay()));
  // Exponential height falloff, integrated along the ray.
  const float H = 110.0;
  float h0 = max(y0 - uSeaLevel, 0.0);
  float dy = d.y * t;
  float k = dy / H;
  float avg = abs(k) < 1e-3 ? 1.0 : (1.0 - exp(-k)) / k;
  float tau = ext * t * exp(-h0 / H) * clamp(avg, 0.0, 40.0);

  float fog = 1.0 - exp(-tau);
  if (border) fog = max(fog, smoothstep(0.62 * uMaxDist, 0.97 * uMaxDist, t));
  if (fog < 0.002) return c;

  vec3 haze = skyBackground(d, 6, 3);
  return mix(c, haze, fog);
}

// The same fog for anything that is not terrain (clouds, the sky): no fade at the edge of the world.
vec3 aerialPerspective(vec3 c, float t, vec3 d, float y0) {
  return aerialFog(c, t, d, y0, false);
}

// Fog and clouds over a surface hit at distance t along d from world position w.
vec3 atmosphereHit(vec3 c, vec3 w, vec3 d, float t) {
  if (uSkyFeatures == 0) return c;
  c = aerialFog(c, t, d, w.y, true);
  float tc;
  vec4 cl = cloudLayer(w, d, t, tc);
  if (cl.a > 0.0) {
    vec3 cc = aerialPerspective(cl.rgb, tc, d, w.y);
    c = mix(c, cc, cl.a);
  }
  return c;
}

// The full sky for a ray that hit nothing, from world position w.
vec3 skyPixel(vec3 w, vec3 d) {
  vec3 col = skyBackground(d, 12, 4);
  col += skyStars(d);
  col += skySunMoon(d);
  float tc;
  vec4 cl = cloudLayer(w, d, 1e9, tc);
  if (cl.a > 0.0) {
    vec3 cc = aerialPerspective(cl.rgb, tc, d, w.y);
    col = mix(col, cc, cl.a);
  }
  return col;
}
