# Enhanced render mode

Render Mode has two settings (F6, `LookState.enhanced`):

- **Vanilla**: the true-to-vanilla look. What the renderer does today.
- **Enhanced**: the target is the feature set of Complementary Shaders (Reimagined and Unbound):
  sun and moon shadows, god rays, waves, reflections, atmosphere, clouds, bloom and so on.

This document lists the features of that pack and says how each one is built here. Only step 1 of
section 11 exists so far: with Enhanced on, the world is drawn by a second program (`ENHANCED`
define) into an HDR target (`EnhancedTarget`) and resolved into the main framebuffer with a plain
tonemap, looking the same as Vanilla. The sun, moon and rain uniforms are declared and set
(`VoxelRenderer.Atmosphere`) but nothing reads them yet.

Sky and atmosphere (4.1 to 4.3, steps 2 and 5) exist: `sky.glsl` (pasted into `voxel.fsh` inside
`#ifdef ENHANCED` by an `#include` line that `VoxelRenderer` expands) has an analytic Rayleigh/Mie sky,
sun and moon discs with phases, stars, vanilla-style and soft volumetric clouds, `cloudShadow(pos, L)`
(multiplied into the sun's visibility in `enhancedLight`), and aerial perspective with height, rain and night fog and the border
fade. `SkyState` holds the feature bits, cloud style (`SkyState.cloudStyle`, 0 off, 1 grid, 2 soft) and wind, and
`LevelRendererMixin` cancels vanilla's `renderSky` and `renderClouds` in the overworld. Not done:
aurora, rainbow, biome fog, cave fog, blindness and darkness fog, matching vanilla's entity fog to the
horizon color, clouds at half resolution with temporal accumulation.

The feature list comes from memory of the pack's option menus. It is probably not complete. Before
building a section, check the pack's actual option list for that area.

Everything below leans on one fact: the fragment shader can trace an arbitrary ray through the
world (`trace(o, d, ...)`, which picks the LOD level by distance from the camera, not by ray origin).
A pack built on a rasterized G-buffer has to fake secondary rays with screen-space tricks. We can
just cast them.

## 1. Pipeline changes

Enhanced needs more than a different lighting function, so the frame flow in `architecture.md`
section 2 changes for it.

### 1.1 Two programs, one source

Compile `voxel.fsh` twice, with and without `#define ENHANCED`. Vanilla mode then pays nothing for
Enhanced code: no extra branches, no extra registers, no extra passes. Every Enhanced feature below
sits behind that define. The Vanilla program stays byte-for-byte what we have now, so its golden
images (`src/test/resources/golden`) stay valid.

### 1.2 Passes

```
 1. shadow pass      sun-view depth, 2 cascades            (new, section 3)
 2. world pass       voxel.fsh -> HDR color + aux + depth   (changed: own FBO, not the main one)
 3. resolve pass     temporal accumulation, bloom, tonemap -> main framebuffer, depth copied
 4. vanilla          entities, block entities, particles, weather, translucent layer, as today
 5. final post       LDR effects on the finished frame (section 9.2)
```

- **HDR target.** The world pass renders into an `RGBA16F` target. Vanilla's main framebuffer is
  8-bit, so lighting, bloom and exposure cannot happen there.
- **Aux outputs (MRT).** Packed normal, roughness/material class, and linear depth. Used for
  temporal reprojection, denoising, and the screen-space-ish post effects.
- **Depth.** The resolve pass writes the depth we already compute (`gl_FragDepth` in `main`), so
  vanilla entities still depth-test against the voxel world.
- **Why accumulate in our own target.** Soft shadows, AO, rough reflections and volumetrics are all
  noisy single-sample estimates that need temporal accumulation. Vanilla entities, particles and
  weather have no motion vectors and would ghost. Accumulating before vanilla draws keeps the history
  clean. Cost: entities are not part of the HDR image (see 9.1).
- **GL state.** All of it goes through `GlStateManager` / `RenderSystem` (project rule). Java owns the
  FBOs and the shader uniforms; none of this touches the native crate.

### 1.3 New uniforms and data

| Data | Source | Used by |
|------|--------|---------|
| Sun and moon direction, moon phase | `level.getSunAngle`, `getMoonPhase` | lighting, shadows, sky |
| Rain and thunder level (smoothed) | `level.getRainLevel`, `getThunderLevel` | sky, fog, wetness, clouds |
| Vanilla sky and fog colors | `level.getSkyColor`, `FogRenderer` | tint for the biome-aware atmosphere |
| Previous view-projection, jitter | `VoxelRenderer` | TAA, reprojection |
| Blue-noise texture | generated once | all stochastic sampling |
| Biome fog and water color per column | `TintMap` (water color already flows to the shader) | fog, water |
| Camera state: in water, in lava, blind, night vision, darkness | `Camera`, `LocalPlayer` effects | fog, grading |

### 1.4 Things vanilla still draws, and what to do about them

- **Sky, sun, moon, stars, clouds.** The shader currently `discard`s on a miss so vanilla's sky shows
  through. Enhanced must own the sky: cancel vanilla's `renderSky` and `renderClouds` with `@Inject`
  cancels in `LevelRendererMixin`, and shade miss pixels in the shader at depth 1.
- **Vanilla fog.** Entities and particles are still fogged by vanilla's fog uniforms. Set the fog
  color to the atmosphere's horizon color and keep the range compatible with ours so they match.
- **Translucent blocks** (glass, ice, stained glass, slime). Vanilla draws these after us, with no
  reflections, refraction or shadows. Fine for the first version. Later they move into the
  raymarcher the way water already did (section 4.8).
- **Entity shadows and lighting.** Entities are not in the voxel world, so they neither cast nor
  receive our shadows. See 3.6.

## 2. Features at a glance

| Area | Complementary features | Section |
|------|------------------------|---------|
| Lighting | Sun/moon light, tonemap, ambient, block light, minimum light, night vision | 3.1, 3.2 |
| Shadows | Shadow mapping, soft shadows, shadow distance, colored shadows from glass | 3.3 - 3.6 |
| Ambient occlusion | SSAO | 3.7 |
| Light shafts | Volumetric light, god rays | 3.8 |
| Subsurface | Foliage and thin-material translucency | 3.9 |
| Block light | Colored block light, flicker, handheld/dynamic light | 3.10 |
| Sky | Gradient, stars, sun and moon discs, moon phases, aurora, rainbow, End and Nether skies | 4.1 |
| Clouds | Vanilla-style, soft, volumetric, cloud shadows | 4.2 |
| Fog | Atmospheric, height, cave, biome, water, lava, powder snow, darkness, blindness | 4.3 |
| Water | Waves, normals, reflections, refraction, absorption, foam, caustics, underwater | 4.4 - 4.6 |
| Reflections | Water, glass, polished and wet surfaces, sky, clouds | 4.7, 4.8 |
| Weather | Rain wetness, puddles, ripples, rain fog, lightning, snow | 4.9 |
| Materials | Integrated PBR, labPBR, emission, normal maps, parallax, metals, gems | 5 |
| Motion | Waving foliage, waving water, waving lava | 6 |
| Post | TAA, bloom, auto-exposure, tonemap, grading, vignette, CA, grain, DOF, motion blur, lens flare, sharpen | 7 |
| Dimensions | Nether, End specific looks | 8 |
| Misc | Entity effects, selection box, color options, performance options | 9, 10 |

## 3. Lighting and shadows

### 3.1 Light model and tonemapping

**What the pack does.** Physically inspired direct light from the sun or moon, sky ambient light,
warm block light, all in HDR and mapped to the screen by a tonemapper with exposure.

**Here.** `lightRgb(light)` in `voxel.fsh` is vanilla's lightmap curve and stays for Vanilla mode.
Enhanced replaces it with:

```
radiance = albedo * ( sunColor * sunVisibility * max(dot(N, L), 0)
                    + skyAmbient(N) * skyLight * ao
                    + blockLightRgb * blockLight )
```

- `L` is the sun direction by day and the moon direction by night. The two cross-fade at dusk and
  dawn, with the sun color going from white to orange as it nears the horizon (this falls out of the
  atmosphere model in 4.1 if we transmit sunlight through it).
- `skyLight` and `blockLight` are the existing 0..15 values from `lightAt`; `uSkyDarken` is replaced
  by real time-of-day and weather terms.
- `skyAmbient(N)` is a cheap hemisphere function: sky color from above, ground bounce from below,
  horizon color from the sides. Later it can sample the sky model instead.
- Tonemap in the resolve pass (an ACES-like curve), with eye adaptation from auto-exposure (7.3).
- Output is HDR until the resolve pass, so emissive blocks and the sun can exceed 1.0 and feed bloom.

### 3.2 Ambient, minimum light, night vision

- `uAmbientLight` already lifts the darkest levels per dimension. Enhanced keeps it as a floor in
  the ambient term, with the pack's "minimum light" slider as a setting.
- Night vision and the Nether/End ambient get explicit multipliers into the same term.

### 3.3 Sun shadows: shadow map from the raymarcher

**What the pack does.** Rasterized cascaded-ish shadow map with distortion, PCF/soft filtering, a
shadow distance setting.

**Here.** Build the shadow map by tracing, not rasterizing. Pass 1 renders an orthographic view from
the sun: for each texel, cast a ray along `-L` using `trace`, store the hit distance. Two cascades
(near ~64 blocks at high resolution, far ~256 at lower), both centered on the camera and snapped to
texel size to avoid shimmering. Because `trace` selects LOD by distance from the camera, far cascade
rays automatically use coarse levels and the heightfield.

Then everything that wants "is this point in shadow" does a texture lookup: surface lighting, water,
god rays (3.8), cloud and caustics masks. Compared with casting a shadow ray per pixel, this cost is
paid once per shadow texel and shared by all consumers, which matters most for god rays, which need
many shadow queries per pixel.

Update strategy: the sun moves slowly and the world changes in small pieces, so the map does not
need a full rebuild every frame. Rebuild a rolling subset of tiles per frame, and mark tiles dirty
when `VoxelWorld` reports a section upload that intersects them. Start with a full rebuild every
frame and measure before getting clever.

### 3.4 Shadow filtering and softness

- Percentage-closer filtering with a rotated Poisson/blue-noise kernel, then temporal accumulation
  in the resolve pass to hide the noise.
- Contact hardening (sharper near the occluder) is possible for free: the shadow map stores
  distance, so the penumbra width can scale with `receiverDist - occluderDist`.
- A short exact shadow ray from the pixel (tracing a few blocks toward the sun) fixes the contact
  detail the map's resolution cannot resolve. Optional; add it only if map artifacts are visible.

### 3.5 Shadow distance, fade, and the far field

Past the far cascade, shadows fade out into the unshadowed sun term. The heightfield can still cast
cheap long shadows (mountains at sunset): sample a coarse heightfield march toward the sun for
pixels beyond the cascades. That is a later add-on.

**Built (3.1 to 3.5).** `ShadowPass` renders two cascades (near 96 blocks wide at 512 texels, far
512 wide at 1024) every frame, with voxel.fsh compiled a third time as `SHADOW_PASS` (a `main` that
traces one ray per texel along `-uShadowDir` and writes the hit distance to an R32F texture). The
light is the sun by day and the moon by night, kept 0.06 above the horizon. Lookups from `voxel.fsh`:
`sunVisibility(pos, normal[, fallback])` (blocker search, then a rotated Poisson kernel whose width
shrinks toward the occluder) and `sunVisibilityFast(pos)` (one tap, for volumetrics). `pos` is relative
to `uCamBlock`. Raw maps are `uShadowMap0` / `uShadowMap1` on units 26 and 27; `shadowCoords(c, pos)`
gives texel and ray distance. Past the far cascade the lookup returns the receiver's sky light, so
caves stay dark and open terrain lit. The light model is `enhancedLight()`; `uSunLighting` is 0 in the
Nether and End, which keep vanilla's light and skip the pass. Not done: rolling updates, the short
exact contact ray, heightfield shadows past the cascades, night vision, a real blue-noise texture
(interleaved gradient noise rotates the kernel instead).

### 3.6 Entities in shadows

Entities are not in the voxel world. Options, cheapest first:

1. Entities get nothing. They are lit by vanilla's lightmap and look flat against Enhanced terrain.
2. Entities are shaded by a vanilla-side tweak: sky/block light tint matched to our sun color (a
   `LightTexture` mixin that scales the lightmap).
3. Entities receive terrain shadows by sampling our shadow map in a patched entity shader. This means
   replacing a vanilla core shader, so it conflicts with other mods and is last on the list.
4. Entities cast shadows: render entity bounding volumes or simple capsules into the shadow map.

### 3.7 Ambient occlusion

**What the pack does.** SSAO.

**Here.** We already compute per-vertex-style voxel AO at level 0 (`ambientOcclusion`, `cornerAO`).
Enhanced adds a second term, a real hemisphere visibility estimate: a few short rays (2-4 per pixel,
2-6 blocks) from the surface along cosine-distributed directions, using `trace` with an any-hit exit.
Accumulate temporally. This is true world-space AO, not screen-space, so no halos and no artifacts at
screen edges. Fades with distance like the existing AO.

### 3.8 Volumetric light (god rays)

**What the pack does.** Light shafts through trees, clouds, and at sunset, with a density option
and color from the sun; volumetric fog is part of the same system.

**Here.** In the world pass, march from the camera to the hit point (or to a max distance on a miss)
in 8-16 steps with blue-noise start offset. At each step sample the shadow map (3.3) and accumulate
in-scattered sun light, weighted by a phase function (Henyey-Greenstein, forward lobe toward the sun)
and by fog density at that height (4.3). Add the result to the pixel color and attenuate the pixel by
the transmittance. Underwater, the same code runs with the water's scattering coefficient, which
gives underwater shafts.

This is cheap precisely because the shadow lookup is a texture fetch. If it is still too costly, render
it at half resolution into its own target and upsample in the resolve pass.

### 3.9 Subsurface scattering

**What the pack does.** Leaves, grass, and thin blocks glow when the sun is behind them.

**Here.** Mark classes of voxel ids (leaves, plants, wool-like) in `BlockClassifier` with a
translucency flag in the existing per-id tables. In the lighting function, add
`albedo * sunColor * sunVisibility * max(dot(-N, L), 0) * k` for flagged ids, using the same shadow
lookup. Leaf shadows then also light up: sun through leaves reads green-gold.

### 3.10 Block light, colored light, flicker, dynamic light

- **Warm tint and curve.** Already done in `lightRgb` (the `blockRgb` curve is vanilla's). Enhanced
  swaps in its own: orange-white falloff with a gentler curve.
- **Flicker.** Multiply block light by a small noise of `uTime` and the cell hash, for flagged ids
  (torches, campfires, lanterns). Per-pixel-cheap.
- **Colored light.** The pack tints light by the source (soul fire blue, redstone red, amethyst).
  The vanilla light engine has one block-light channel, so we need our own data: a Rust-side flood
  fill per light section that carries an RGB triple from emissive voxel ids, stored in `LightMap` next
  to the existing byte (or replacing it with RGB4 + sky4). Cost: a format change in `LightMap`, the
  light upload, and `lightAt`. Medium size; late in the plan.
- **Handheld / dynamic light.** An item in hand or a dropped torch lights its surroundings. Pass the
  player's held-light color and radius (and nearby light-emitting entities, capped at a few) as
  uniforms and add an inverse-square term in the lighting function. No data structures needed.

## 4. Atmosphere, water, weather

### 4.1 Sky

| Pack feature | Here |
|--------------|------|
| Sky gradient, sunset colors | Analytic single-scattering atmosphere (Rayleigh + Mie) evaluated per miss pixel. The same function gives the sun color at the horizon (3.1) and the aerial perspective in 4.3. No textures. |
| Sun and moon discs | Cancel vanilla's billboards. Draw a disc plus glow in the shader from `L`; the moon uses the moon-phase texture or an analytic phase mask. |
| Stars | Procedural star field from a hash of the view direction, rotated by the sun angle, faded by daylight and rain. |
| Moon phases | `getMoonPhase` selects the disc mask and sets the night light strength. |
| Aurora | Layered noise curtains at high altitude, only at night in cold biomes (biome temperature from the tint map). Raymarch a few layers; gate behind a setting. |
| Rainbow | A faint arc at a fixed angle opposite the sun, only when rain just stopped and the sun is low. |
| Night sky, galaxy | Optional procedural nebula noise on top of the stars. Low priority. |
| End and Nether skies | See section 8. |

Sky pixels write depth 1. Everything uses view direction only, so it is the cheapest part of the
frame.

### 4.2 Clouds

**What the pack does.** Several styles: vanilla blocky, soft, volumetric ("realistic"), with shadows
on the ground and lit edges.

| Style | Here |
|-------|------|
| Vanilla-style blocky | Raymarch the same cloud grid vanilla uses (a 12-block-cell layer at y=192 in the overworld, driven by the cloud texture). Intersect the ray with the slab and sample the texture. Cheap. |
| Soft / volumetric | Raymarch a thin 3D density slab (FBM noise) in ~16-24 steps, with a few light-direction samples for self-shadowing. Run at half resolution with temporal accumulation. |
| Cloud shadows | Sample the same density at the point projected up to the cloud layer along `L`. Multiply into the sun visibility term (3.1). Cheap, gives the ground moving shadows. |
| Coverage and weather | Rain level raises coverage and darkens the base; thunder darkens further. |

Vanilla's `renderClouds` is cancelled in Enhanced.

### 4.3 Fog

| Pack feature | Here |
|--------------|------|
| Atmospheric / distance fog | Aerial perspective: for every hit and miss, in-scatter and extinction from the atmosphere model over `tHit`. Replaces vanilla's flat fog; the horizon fades into the sky color rather than a fog band. |
| Height fog | Exponential density with height. Also feeds the god-ray density (3.8). |
| Rain fog, night fog | Density multipliers on rain level and daylight. |
| Biome fog | Swamp is thicker and green, desert is clear. Needs per-column fog parameters from `TintMap`, same route as the water color. |
| Cave fog | Fade to near-black with distance when sky light is zero and the camera is underground (read `lightAt` at the camera). |
| Lava, powder snow | When the camera is inside them: solid-color short-range fog, like vanilla's. |
| Blindness, darkness effect | Short-range fog (blindness) and a pulsing dark fog (darkness effect). Read the player effects each frame. |
| Border fog | The pyramid's `uMaxDist` edge must fade into the atmosphere, not into a visible cut. The aerial perspective at that range does it. |

Existing `underFog` becomes a case of the same function.

### 4.4 Water surface: waves and normals

**What the pack does.** Animated bumpy waves, with a choice of vanilla-textured or smooth water.

**Here.** Water surface hits are already identified in the shader (`gWaterT`, `gWaterAxis`, markWater).
For a top-facing water hit at the surface height, evaluate a height function from 2-3 octaves of
scrolling gradient noise (`uTime`) and take its analytic gradient as the perturbed normal. That
normal drives reflections (4.7), refraction (4.5), and specular. For true geometric waves, do a
short raymarch against the height function in the surface cell (a few steps) so the silhouette of the
surface moves, not only the shading. Fade wave amplitude with distance to avoid shimmering and with
the LOD level.

"Vanilla water texture" stays an option: keep the current `surface()` sample, and add the waves on
top as a normal perturbation only.

### 4.5 Water: refraction, absorption, color

- **Refraction.** Bend the transmitted ray at the water surface with Snell's law using the perturbed
  normal, and continue `trace` from the surface into the water to find what is seen below. Real
  refraction, no screen-space approximation, works at screen edges.
- **Entities, particles and the block outline** are triangles vanilla draws after the raycast pass, so
  the ray bending above cannot reach them. `EntityRefraction` patches the vertex stage of vanilla's
  core shaders as they load (`ProgramMixin`), so every vertex goes through `look_eye` in
  `entity_refract.glsl`: it finds the point on the surface where the camera's ray bends onto the
  vertex (a bisection on Snell's law for the flat surface, then a few corrections for the wave
  normal, `water_waves.glsl` being shared with `voxel.fsh`) and puts the vertex on the camera's ray
  through that point, at the length of the bent path. The raycast pass writes that same length as
  the depth of refracted pixels (`gRefractT`), so the two sort correctly. The surface height comes
  from a 128 x 128 window of columns around the camera, rebuilt from the client level
  (`EntityRefraction.rebuild`); water under a roof is not seen. Only a camera above the surface
  looking at things below it is handled. It is on only between the raycast pass and the end of level
  rendering, so the hand and the GUI draw as vanilla does. Large triangles are warped as flat
  triangles between their warped corners.
- **Absorption.** Beer-Lambert along the underwater segment length, with per-channel coefficients so
  deep water goes blue-green. Replaces the fixed alpha that vanilla (and Vanilla mode) uses.
- **Water color.** The biome water color from `TintMap` sets the scattering color so swamps are murky
  and warm oceans are clear.

### 4.6 Water: foam, caustics, underwater

| Pack feature | Here |
|--------------|------|
| Shoreline foam | Near the shoreline, when the underwater segment is short (shallow), add white foam with a noise mask animated by `uTime`. Depth is known from the refraction trace. |
| Caustics | On surfaces hit through water, sample a caustic pattern (animated noise folded into bright lines) projected along `L`, multiplied by sun visibility. |
| Underwater fog and tint | `underFog` generalized: extinction and in-scatter by the water's coefficients, `tHit`-based, plus god rays (3.8). |
| Underwater distortion | A subtle UV warp in the final post (9.2), only when the camera is submerged. |
| Surface seen from below | Total internal reflection beyond the critical angle (`gWaterExit` already exists) with the sky or the reflected scene. |

### 4.7 Reflections

**What the pack does.** Sky and screen-space reflections on water, and (in Reimagined and Unbound)
on polished, metallic and wet surfaces, with roughness.

**Here.** Cast a real reflection ray with `trace` from the surface along `reflect(d, N)`, in the
same shader, up to a limited distance and with a coarse step budget (reflections can use higher LOD
levels from the start, since they do not need detail). If it hits, shade the hit with the same
lighting function (without further bounces). If it misses, return the sky model. Fresnel (Schlick)
sets the mix. Rough surfaces jitter the ray within a cone and rely on temporal accumulation.

For water this is the main visual feature and goes in with the water pass. For other materials the
roughness comes from section 5.

Expected cost is the biggest in the plan: it is a second full `trace` for every reflective pixel.
Mitigations: step budget cap, lower LOD bias, skip when Fresnel is small, half-resolution for rough
cases, and a quality setting.

### 4.8 Glass and other translucent blocks

Vanilla still draws these (1.4). To get reflections, refraction and tinted light through them, they
move into the raymarcher the way water did: classify them as see-through kinds in `BlockClassifier`,
let `trace` carry a small stack of translucent hits (water already carries one: `gWaterT`), blend
front-to-back, cancel the vanilla layer for them like `LiquidBlockRendererMixin` does for water.
Colored shadows (stained glass tinting the sunlight) then come naturally if the shadow pass stores a
tint alongside the distance. This is a large change; defer it until the basics work.

### 4.9 Weather

| Pack feature | Here |
|--------------|------|
| Wet ground | While raining, lower albedo (darker), raise smoothness on top-facing and exposed surfaces (sky light high, normal up). Ramp with rain level and dry out slowly after. |
| Puddles | Noise mask on wet flat surfaces, strongest in low spots by noise; puddles become mirror-smooth water-like reflections (4.7). |
| Rain ripples | Animated ring pattern on the puddle mask. |
| Rain fog | Density bump (4.3). |
| Thunderstorm lightning | On a lightning strike (vanilla's `skyFlashTime`), pulse the sky and ambient terms. |
| Snow | Snowy biomes get a subtle sparkle on snow blocks (a hash-gated specular highlight); falling snow itself stays vanilla. |
| Rain/snow particles | Stay vanilla. They render after us and depth-test against our depth. |

## 5. Materials

### 5.1 Integrated PBR (no resource pack needed)

**What the pack does.** Reimagined assigns reflectivity, roughness, metalness and emission per block
automatically, based on the block id and texture content, so default textures look right.

**Here.** `BlockClassifier` already maps block ids and models to voxel ids and a table the shader
reads. Add a per-id material record: roughness, reflectance (F0) or metal, emission strength,
subsurface flag, wetness response. Hand-assigned for the important blocks (metals, gems, polished
stone, ice, glass, ores, glowstone family, lava, magma, sea lantern) and class-based defaults for the
rest. Where a block's brightness should depend on the texel (an ore's gems glow, the stone does not),
derive emission or smoothness from the texel's luminance and saturation above a threshold in the
shader. The record fits in the existing `uFaces`-style buffer, so it is one more fetch per hit.

### 5.2 labPBR resource packs

If a resource pack ships `_n` (normal + height) and `_s` (specular: smoothness, F0/metal, porosity,
subsurface, emission) textures, Reimagined and Unbound read them. For us that means stitching those
textures into extra atlases parallel to the block atlas (vanilla's atlas has no mechanism for this),
and sampling them in `surface()` with the same UVs. The work is the atlas build in Java. Later feature;
integrated PBR (5.1) comes first.

### 5.3 Normal maps and parallax

- **Normal maps** perturb `hitNormal` in lighting and reflections once a normal atlas exists (5.2).
- **Parallax occlusion** is a short height march inside the hit cell using the height channel. We are
  already marching rays, so it is the same machinery as the voxel DDA but on a texture height field.
  Level 0 only, within a few blocks.
- **Generated normals** (the pack's "pixel-aligned" bump from the albedo's edges) can be derived from
  neighboring texels in `surface()` with no extra data. Cheap option for a small visual lift.

### 5.4 Emission and glowing blocks

Emissive ids add `albedo * emission` to the HDR color, bypassing the lighting function. Redstone ore,
glowstone, lava, sea lanterns, torches' flames, end rods, ochre froglights, candles, etc. Combined with
bloom (7.2) this is where the pack gets its look at night.

### 5.5 Specular highlights

Sun and moon specular on smooth surfaces (GGX with the integrated roughness). On water this is the sun
glitter path. Computed from `L`, `N`, and the view direction; shadowed by the sun visibility term.

## 6. Motion

| Pack feature | Here |
|--------------|------|
| Waving leaves, grass, plants | Already present in a basic form: the shader sways plant models from `uTime` and a per-cell hash (`SWAY_OMEGA`, see `quadSurface`). Enhanced adds wind direction and gusts: a slow 2D noise field scrolling across the world, scaling the sway amplitude, stronger in rain and thunder. Leaves and tall grass use different amplitudes. |
| Waving water | Waves in 4.4. |
| Waving lava | A slow vertex-style displacement of the lava surface plus brightness flicker, using the same height function approach as water with a lower frequency. |
| Waving in the heightfield | Not done. Far terrain is too coarse for it to be visible. |

## 7. Post processing

### 7.1 Temporal anti-aliasing

Jitter the pixel ray origin by a Halton sequence each frame (a sub-pixel offset added to the NDC in
`main`). The resolve pass reprojects the previous accumulated frame with the previous view-projection
and the stored linear depth, clamps it to the neighborhood color range of the current frame to reject
ghosts, and blends. This also pays for the stochastic effects: shadows, AO, reflections, volumetrics.
Needs the history textures and a motion-free reprojection (the camera moves, the world does not;
entities are not in the buffer, see 1.2). On camera cuts and teleports, drop the history.

### 7.2 Bloom

Threshold + downsample/upsample chain on the HDR world pass output, added back before tonemapping.
Only the voxel world blooms in the first version; entities draw after the resolve pass (9.1).

Built (`PostChain`): threshold with a soft knee, 13-tap downsample chain, tent upsample, added in
`resolve.fsh`. The sky is HDR radiance with alpha 1, so it blooms like terrain (the sun disc most). Knobs in `EnhancedSettings`.

### 7.3 Auto-exposure

Compute average (log) luminance on a small mip of the HDR image, smooth over time with different up
and down rates, and apply as an exposure multiplier in the resolve pass. A fixed exposure while it is
being built; the sun-vs-cave brightness swing is what it is for.

Built (`PostChain`): log-luminance of a 128x128 reduction, mipmapped to one texel, smoothed on the GPU
in a 1x1 ping-pong texture (no readback). Strength, key, limits and rates are in `EnhancedSettings`.

### 7.4 Color grading and tonemap options

Tonemap curve, saturation, vibrance, contrast, brightness, and per-time-of-day tints. A final lookup
or a few uniforms in the resolve pass. The Nether and End get their own presets (section 8).

### 7.5 Screen effects

| Pack feature | Here |
|--------------|------|
| Vignette | Radial darken in final post. |
| Chromatic aberration | Per-channel UV offset, scaled by distance from center. Final post. |
| Film grain | Blue-noise add, final post. |
| Sharpening | Unsharp mask / CAS after TAA, which softens. Resolve pass. |
| Depth of field | Needs depth, which the main framebuffer has. Final post, using the depth texture; the focus distance from the center sample, smoothed. |
| Motion blur | Camera-motion blur from the depth and the previous view-projection. Final post. Entities do not blur correctly; accepted. |
| Lens flare | Sun-screen-position sprites. Occlusion from one `trace` toward the sun (a single ray per frame) or a depth sample. |
| Underwater / lava distortion | UV warp in final post when the camera is inside the fluid. |
| Hurt, pumpkin, nausea overlays | Stay vanilla (GUI layer). |

Built (`FinalPost`, `post.fsh`, hooked at the tail of `LevelRenderer.renderLevel`): vignette, chromatic
aberration, grain, sharpening, depth of field (depth and focus from the world pass, so entities take the
terrain's depth), motion blur, lens flare and underwater/lava warp, each with its own switch. Sharpening
runs here, not in the resolve pass, until TAA exists. Tonemap, grading and the other resolve knobs
live in `EnhancedSettings`.

## 8. Dimensions

| Dimension | Pack features | Here |
|-----------|---------------|------|
| Overworld | Everything above. | Default path. |
| Nether | Red-orange fog, lava glow, ambient from lava light, no sun/moon, no clouds, no sky. | Fog color from the biome, strong lava emission and bloom, lighting function uses the dimension ambient with no sun term. Skip the shadow pass entirely. |
| End | Dark purple sky, star field, big void fog, end island rim light. | Procedural dark sky with the star field and a faint purple gradient, no sun term; a weak fixed directional light for the islands. Shadow pass off. |

The shadow pass, clouds, and sun disc are gated by dimension so those dimensions do not pay for them.

## 9. Interaction with what vanilla draws

### 9.1 Entities and particles

They are drawn after the resolve pass, in LDR, with vanilla's lighting. Consequences: no bloom on
them, no auto-exposure applied to them, no temporal accumulation on them. Mitigations, in order:

1. Tint entity light to match our sun and sky color (3.6, option 2).
2. Apply the exposure multiplier to entity colors via a mixin on the entity render types' shaders, or
   a post-multiply in the final post pass using a stencil mark.
3. Move the resolve (tonemap, bloom) after vanilla's translucent layer, not before, so all geometry is
   tonemapped together. This needs the main framebuffer to be HDR, which is a larger change.

Choose 1 first. Revisit 3 if the mismatch is distracting.

### 9.2 Final post

A second fullscreen pass at the very end of level rendering (hooked after the translucent layer, before
the GUI). It handles effects that need the whole frame including entities: vignette, chromatic
aberration, film grain, depth of field, motion blur, underwater warp, lens flare. These are all LDR
and do not need the HDR target.

## 10. Settings and quality

Render Mode (F6) is the main switch. Under Enhanced, expose a small number of quality knobs so the
cost can be dialed:

- Shadow resolution and distance (3.3, 3.5).
- Reflection quality: off / sky only / traced (4.7).
- Volumetric light quality: off / half-res / full (3.8).
- Cloud style (4.2).
- Water: waves on/off, vanilla texture or smooth (4.4).
- Post: each effect on its own toggle (section 7).

Defaults aim at a mid-range GPU. Performance numbers are unknown until the base passes exist;
`RenderStats` already shows timing and should grow per-pass timers (GL timer queries) as passes land.

## 11. Order of work

Each step should leave the game working and look better than the last. Vanilla mode must stay
unchanged at every step; the render tests in `src/test/java/dev/macmv/look/gl` cover that.

1. **Infrastructure.** Two shader programs (`ENHANCED` define), HDR world target, resolve pass with a
   plain tonemap, sun/rain/time uniforms, F6 wired up. Enhanced should look like Vanilla at this point.
2. **Sky and atmosphere.** Own the sky (cancel vanilla's), procedural sky, sun and moon, stars, aerial
   perspective fog, border fade.
3. **Sun lighting and shadows.** Lighting model (3.1), shadow map (3.3), filtering, cloud-less ground
   shading. This is the point where the world starts to look different.
4. **Water.** Waves, reflections (sky first, then traced), refraction, absorption, foam, underwater
   fog.
5. **Clouds.** Vanilla-style first, then volumetric, with cloud shadows.
6. **TAA and the soft stuff.** Jitter, history, reprojection. Then traced AO, rougher reflections.
7. **Volumetric light.** God rays from the shadow map, underwater shafts.
8. **Materials.** Integrated PBR table, emission, specular, wet surfaces, subsurface for foliage.
9. **Bloom, auto-exposure, grading, final post effects.**
10. **Weather and the rest.** Puddles, ripples, lightning, aurora, rainbow, wind.
11. **Large items.** Colored block light, glass in the raymarcher, labPBR atlases and normal maps,
    parallax, dynamic lights, entity shadows.

## 12. Risks

- **Cost of secondary rays.** Shadows come from a shared map to avoid a ray per pixel, but traced
  reflections and AO are real extra traces. The step-count heatmap debug view (F8) is the tool for
  seeing it. Budget caps and LOD bias are the first lever.
- **Far field.** Far levels are coarse voxels and a heightfield. Shadows, reflections and AO from
  there will look blocky or be absent. The aerial perspective hides most of it.
- **Temporal artifacts.** Reprojection with a moving player and sudden world edits (block break, chunk
  upload) can smear. Needs history invalidation hooks on section uploads.
- **Entities.** They will look like they belong to a different renderer until section 9.1 is done.
- **Mod compatibility.** Cancelling vanilla's sky and clouds, and any entity shader patch, can clash
  with other mods. Use `@Inject` cancels, not `@Overwrite` (project rule).
- **Scope.** This is the size of an entire shader pack. The order above is chosen so that stopping
  at any step leaves something shippable.
