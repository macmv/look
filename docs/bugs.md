# Known bugs

- **ME cables (Applied Energistics 2)**: ME blocks of any kind do not render. Only an outline is
  drawn.
- **Witch's cauldron**: the outside of the cauldron is not shown.
- **Flowing water**: the wrong shape is drawn for flowing water.
- **Lava**: does not show up at all.
- **Regions Unexplored leaves**: leaf tinting is wrong for the leaves added by Regions Unexplored.
  See "Position-dependent tints" below.
- **Xaero's waypoints**: waypoints show up in random spots.
- **Underwater block sides**: the sides of blocks seen under water are continuous stripes instead of
  the block texture.
- **Grass in LOD'ed chunks**: tall and short grass is missing from the coarse levels. See "Plants
  missing in coarse levels" below.

## Position-dependent tints (Regions Unexplored silver birch)

Silver birch leaves, which should be yellow, come out bright green.

Cause: `BlockClassifier.tintOf` runs the block's color provider against a fake level at
`BlockPos.ZERO` and only recognizes the grass and foliage marker colors. Silver birch's provider
returns `getAspenColor(level, pos)`, a yellow computed from the position (`Color.getHSBColor` with
`Mth.sin` terms), which is neither marker. The fallback then calls the provider with a null level,
where it returns `FoliageColor.getDefaultColor()` (`0x48B518`), and that green is baked in as a
constant. Any provider that colors by position hits this (RU's rainbow eucalyptus, rainbow glass
and enchanted aspen too).

Fix options:

- Pragmatic: when the probe result is not a marker, run the provider at a handful of real
  positions with a real level and bake the average as the constant. Loses the per-tree variation
  but is close to right.
- Heavier: add a "position-dependent" tint kind and sample the provider on the CPU per voxel.
  Exact, but expensive over a large view, and the shader cannot run Java providers itself.
- Cheap and wrong: skip the null-level fallback for position-dependent providers and use white.
  Stops the green but loses the color.

The sections below are from reading the code, not from a debugger or the modded game; the
"to confirm" steps say how to check each guess. The `1.21.1-look` Prism instance only has Fabric API
and this mod, so none of the modded cases can be reproduced there yet.

## ME cables (Applied Energistics 2)

Only the outline (the selection box) is drawn, so the block exists and has a shape, but the
raycaster sees nothing.

Likely cause: `BlockClassifier.extractQuads` reads the model with
`model.getQuads(state, cull, random)`, which has no level or position. AE2's cable bus, and most of
its other blocks, are block entities with a dynamic baked model that emits its quads through the
Fabric Rendering API (`emitBlockQuads`, with the connections read from block entity data), and its
plain `getQuads` returns an empty list. Then `recs.isEmpty()` makes `extractQuads` return null, and
`classify` returns 0, which is air. Vanilla's section mesher goes through the Renderer API, so the
blocks look fine with the mod off (F7).

To confirm: log the model class and quad count when `extractQuads` returns null. If it is an AE2 or
Fabric `FabricBakedModel` with `isVanillaAdapter() == false`, this is it.

Fix options:

- Run the Fabric `emitBlockQuads` path with a `QuadEmitter` that collects quads. Needs a real level
  and position (cable connections come from the block entity), so this is per-position data, not
  per-state. The voxel id table is per-state, so these would need per-position ids or a separate
  per-position quad pool.
- Cheap: for a state that yields no quads but is `RenderShape.MODEL`, fall back to its collision or
  outline shape as a colored box, so the block is at least visible.
- Do not hide the failure: when a `MODEL` block yields no quads, warn once per block id.

## Witch's cauldron

The outside of the cauldron is not shown (the inside is).

Likely causes, most likely first:

1. `MAX_QUADS` is 48 and `extractQuads` collects the uncullable quads first (`i = -1`), then each
   culled direction. The vanilla cauldron model has many elements (four legs, four walls with an
   outer and an inner face, the floor), so it may pass 48. The overflow is dropped (logged at debug
   level only), and what is dropped is the tail, the culled directions, which is where the outer
   wall faces live.
2. The outer walls are cullable faces. The shader may cull a quad against a neighbor when its
   `cullface` direction is set, and a cauldron is not a full cube, so this should not apply. Check
   how `KIND_QUADS` handles `cullface`.
3. If this is the Witchery mod's cauldron (a block entity with its own renderer), the outside is
   drawn by the block entity renderer and vanilla's model is only the inside. Then `classify`
   draws what the model has, and the rest is a BER that depends on the block entity pass.

To confirm: run with debug logging on and look for `has N quads; only using the first 48` for the
cauldron's block state. If the vanilla cauldron looks fine, the cause is not 1 and is specific to that mod's model.

## Flowing water

The wrong shape is drawn.

Cause: `BlockClassifier.waterEighths` turns a fluid state into one height per block
(`round(amount * 8 / 9)`, whole when `FALLING`), and the shader draws it as a flat-topped box. Vanilla
(`LiquidBlockRenderer`) takes the height at each of the four top corners as a weighted average of
the four blocks around that corner, so flowing water has a sloped surface, and it also tilts the
texture along the flow direction and uses the flowing sprite for the sides. Other differences:

- A source block with water above it is lifted to a whole voxel by the shader, but a flowing block
  with a flowing block above it is not (the check is `isWaterVoxel` above, which should cover it).
- The surface is a per-voxel box, so a water column of mixed heights looks like stairs.
- `waterId` ties the sprite to the still-water sprite for every face.

Fix options: bake the four corner heights into the voxel (needs the neighbors, so it cannot be a
per-state id; it would have to be extra per-voxel data like the fill height, `FILL_SHIFT`), or
accept the stairs and only fix falling water and the sides.

## Lava

Does not show up at all, not even with the vanilla mesh.

Cause: lava is not drawn by the voxel path, and the vanilla path that does draw it is cancelled.

- `classify` returns 0 for the lava block: `LiquidBlock` has `RenderShape.INVISIBLE`, it is not
  water, so it falls through to "block entities and invisible blocks are drawn by vanilla".
- `LiquidBlockRendererMixin` leaves lava to vanilla, but vanilla puts lava in the **solid** layer
  (`ItemBlockRenderTypes.getRenderLayer(FluidState)` is `solid` for lava, `translucent` for water),
  and `LevelRendererMixin.renderSectionLayer` cancels `solid`, `cutout_mipped` and `cutout`.

So lava is in a mesh that is never drawn. The comment on the mixin ("Lava and everything else
translucent stay vanilla") is only true for the translucent layer.

Fix options:

- Classify lava as a solid voxel with an emissive material. `Materials` already special-cases
  `lava` and `lava_cauldron` (`Materials.java:185`), so it was expected to reach the shader. Needs
  the lava sprite (animated) and a height (same `waterEighths` approach, `LavaFluid.getAmount`).
- Or draw the lava mesh layer in the vanilla pass: do not cancel the solid layer for sections that
  only contain lava. Fiddly, since the layers are per section.

## Xaero's waypoints

In-world waypoints show up in random spots.

No code in this mod touches projection or model-view matrices (no `setProjectionMatrix` or similar),
so the cause is probably in what Xaero reads. Guesses:

1. Xaero projects waypoints from the camera matrices it captured during the level render, and
   `VoxelRenderer.render` and `FinalPost` bind other framebuffers and run extra passes inside
   `renderLevel`. If it captures from a render type or `GL` state that we change (viewport, bound
   framebuffer, depth state, `RenderSystem` matrices), the waypoint lands somewhere else.
2. Waypoints that are in front of the camera but drawn at the wrong place could be an offset: our
   passes use camera-relative coordinates with some origin (`uOrigin`-style), and if anything is
   left bound or the model-view stack is not restored, a mod that draws after `renderLevel` inherits it.
3. The depth buffer the shader writes (so entities draw on top) could differ from vanilla's, which
   would make Xaero's depth-tested labels vanish or show through, but that is "wrong visibility",
   not "wrong position", so this one is less likely.

To confirm: turn F7 off. If the waypoints return to the right spots, it is a state leak from the
voxel pass. Then bisect by disabling `FinalPost` (the enhanced post) separately from `VoxelRenderer`.

## Underwater block sides

Looking at the side of a block through (or from) the water, the face is continuous stripes, not
the block's texture.

Likely cause: the grazing-angle filter in `surface()` (`voxel.fsh`, around line 275). A face is
sampled at `taps` points along the ray's direction in the surface, spread over `r * (1/cos - 1)` with
`r = t * uPixelAngle` and `cos` clamped to 0.05. Under water the rays are bent by the wave normal and
refraction, and `traceShade` caps the taps at 2 (`gTapCap`), so a bent ray that hits a side face at a
shallow angle gets a spread of many blocks sampled with only 2 taps. The `fract` wraps the taps
across several texture repeats, and a mip level picked from `r` (a long ray from the refracted
origin, plus `tPrior`) blurs across the other axis, leaving smears along one direction.

Other candidates: `uPixelAngle` is the primary pixel's angle, but a refracted ray's footprint is
wider or narrower than that; and `t` for the refracted ray may be measured from the water surface
rather than from the camera, so `r` and the mip level are off.

To confirm: `uDebug == 3` forces one tap at lod 0. If the stripes go away with it, it is the filter
and not the ray (a stripe that stays means the hit position or `local` is wrong). Also compare with
the water refraction setting off (`uWaterRefract`): if that fixes it, the bent rays are the trigger.

Fix options: scale `r` and `spread` by the refracted ray's actual footprint, raise `gTapCap` for the
water rays, or cap `spread` to about one block so the taps stay inside one repeat.

## Plants missing in coarse levels

Grass, tall grass and other plants mostly disappear once a chunk is drawn at a coarse LOD. This is
from reading the code; it has not been confirmed in game.

Loaded chunks (`WorldSource` -> `LodReduce.reduce`):

- Plants never win a coarse voxel (priority 0, skipped by `reduce`). They only show as a "cover":
  the ground cube with the plant's sprite drawn over its up face (`BlockClassifier.coverId`).
- That only works when the ground block and the plant fall in the same 2x2x2 sample group. When
  the ground is the top layer of a group, the plant is in the bottom layer of the group above. That
  group holds only the plant, so `best == 0`, `reduce` returns 0 and the plant is gone. Which
  columns hit this depends on the height parity of the terrain, so roughly half of them do.
- The cover is skipped when the winner is not a plain cube or is leaves, and it uses only the
  first quad's sprite, so the upper half of tall grass never shows.

Generated terrain (`SeedSource` -> native `forest.rs`):

- A coarse voxel only gets a plant when at least half of its columns (`size * size / 2`) have one.
  Grass is sparse, so that almost never holds.
- In the heightfield levels the threshold is `size * size / 8` (`plant_need`). Grass thinner than
  that is still dropped.

Fix options:

- Loaded chunks: when a group has a plant but no cube, carry the plant down and put the cover on
  the ground in the group below (needs a vertical neighbor lookup in `WorldSource`).
- Generated terrain: lower the thresholds, or tint/texture the ground voxel by plant density
  instead of requiring coverage.
