# Architecture

This document explains how the Look mod works. It is a map of the code.

## 1. What the mod does

Look is a Fabric 1.21.1 client mod. It replaces the vanilla chunk renderer with a voxel raycaster.

- The mod draws one fullscreen triangle.
- The fragment shader (`voxel.fsh`) casts one ray for each pixel.
- The shader writes color and depth. Vanilla entities, particles and weather then draw on top.
- Vanilla still builds its chunk meshes. The F7 key switches between the two renderers at once.

The mod has two parts:

| Part | Path | Job |
|------|------|-----|
| Java | `src/main/java/dev/macmv/look` | Owns all OpenGL calls and all Minecraft hooks. |
| Rust | `native/` | Owns CPU voxel data and the terrain generator. |

Java calls Rust through JNI. The calls are coarse and use direct buffers.

## 2. Frame flow

1. Vanilla calls `LevelRenderer.renderSectionLayer` for the `solid` layer.
2. `LevelRendererMixin` takes the call. It runs `LookState.poll` (key presses), then
   `VoxelRenderer.render`.
3. `VoxelRenderer.render` calls `VoxelWorld.update`. This step builds and uploads sections, within a
   2 ms budget.
4. `VoxelRenderer.render` sets the shader uniforms and draws the triangle.
5. The mixin cancels the `solid`, `cutout_mipped` and `cutout` layers. Vanilla then draws entities,
   block entities and the translucent layer. These pass the depth test against our depth.

In Enhanced mode (F6) step 4 draws into an HDR target with a second shader program instead, and
`EnhancedTarget.resolve` copies it, with depth, into the main framebuffer. See `enhanced.md`.

Translucent blocks (water, glass) are still vanilla.

## 3. Level-of-detail pyramid

Far terrain uses coarse voxels. Level `k` has voxels that are `2^k` blocks wide (`Lod`).

- The shader picks the level from the horizontal (Chebyshev) distance of the ray from the camera.
- Level 0 covers 0 to 256 blocks (`Lod.BASE_BLOCKS`). Level `k` covers `256 * 2^(k-1)` to
  `256 * 2^k`.
- Each level is a ring of sections of the same size. Memory per level is therefore constant. The
  reach doubles with each level.

There are two kinds of level:

| Levels | Storage | Class |
|--------|---------|-------|
| 0 and 1 (`Lod.LEVELS`) | Voxel bricks | `VoxelWorld` |
| 2 to 6 (`Lod.HF_LEVELS`) | Heightfield | `HeightField` |

A heightfield stores one height for each column. It is much cheaper than bricks. Its texels are 4
times finer than a voxel of the same level. Together the two kinds reach 2048 chunks.

## 4. GPU data layout (Java)

`VoxelWorld` mirrors the world into these GPU objects:

| Object | Content |
|--------|---------|
| Indirection 3D texture | One texel per 16x16x16 section. Value 0 is empty. Other values are `slot + 1`. |
| Brick buffer | 4096 `u16` voxel ids per slot (8 KiB). |
| Color buffer | Voxel id -> RGB color. |
| Face buffer | Voxel id -> atlas UV for each face and layer. Textures come from the vanilla block atlas. |
| Tint array (`TintMap`) | Biome grass and foliage color for each 4x4 block column. |
| Light (`LightMap`) | Vanilla sky and block light for level 0. It has its own indirection texture. |
| Heightfield arrays | Height, kind and color, and the highest texel of each tile. |

Rules:

- The indirection grid wraps in X and Z (`GRID_XZ` = 128 sections). The levels stack along Y.
- All levels share one pool of brick slots (`BrickTable`).
- A section that has one voxel id throughout (for example solid stone) has no brick of its own. All
  such sections of that id point to one shared brick.
- A voxel id has 13 bits. The top 3 bits of a brick texel are the fill height (eighths of a voxel).

## 5. Sources: where blocks come from

`VoxelSource` is the producer interface. `VoxelWorld.setSource` assigns one source to each level.
A source fills a 16^3 section with voxel ids through `fill`.

`fill` returns one of these:

- A count of solid voxels. `0` means all air.
- `UNAVAILABLE`. The source has no data. The section is not tracked.
- `PENDING`. The data is in production. The section waits in `LOADING` until the source calls
  `VoxelWorld.invalidate`.

A source gives data to the world in one or both of two ways:

- **Push.** The source calls `invalidate` when a section is new or changed.
- **Pull.** `enumerated()` returns true. The world asks for each section in view when the camera
  moves.

| Source | Use |
|--------|-----|
| `WorldSource` | Blocks from loaded client chunks. Level 0 is exact. Coarse levels use point samples. |
| `SeedSource` | Terrain generated from the world seed by the native engine. Singleplayer only. |
| `LayeredSource` | Real chunks on top of generated terrain. This is the default for levels 0 and 1. |
| `HillsSource` | A simple test generator. |

In `LayeredSource`, a loaded column replaces the generated column. The terrain changes from real to
generated at the edge of the loaded chunks, not at a level border. On a server, only `WorldSource`
is active.

### How the world learns about changes

Mixins send events to `WorldSource`:

- `ClientChunkCacheMixin.replaceWithPacketData` gives the raw chunk packet bytes (`WireChunk`
  decodes them in one pass). `drop` reports a chunk unload. `onLightUpdate` marks light as dirty.
- `LevelRendererMixin.setSectionDirty` and `blockChanged` report changed blocks.

## 6. Section life cycle

`VoxelWorld` keeps one `SectionState` for each tracked section:

    (untracked) -> WAITING -> LOADED | EMPTY | STARVED | LOADING
    LOADING -> WAITING             (the source finished)
    any -> OUT_OF_RANGE -> WAITING (the camera moved away, then back)

- `LOADED` owns a brick slot. `EMPTY` is all air and owns no slot.
- `STARVED` wants a slot but the pool is full. It retries when a slot is free.
- Each frame, `VoxelWorld.update` rebuilds `WAITING` sections nearest first. It stops after 2 ms.
- Everything runs on the render thread. There are no data races. The risk is wrong bookkeeping, so
  every change goes through `setState`.

## 7. Native side (Rust)

| Module | Job |
|--------|-----|
| `rng`, `noise`, `density`, `biome` | Port of the vanilla overworld noise, density functions and biome lookup. |
| `terrain` | Predicts the surface height and surface block of each column from the seed. It is within a fraction of a block of vanilla on average. It does not know trees, structures or aquifers. |
| `engine` | Worker threads. They make one tile for each section column of a level, nearest first. |
| `brick` | Occupancy masks (one `u64` for each 4x4x4 cell). |
| `lib` | The JNI layer. `guard` turns a panic into a Java `RuntimeException`. |

Flow for a generated section:

1. Java (`SeedSource`) asks the engine for a tile and returns `PENDING`.
2. A worker makes the tile. A tile is small: a height and a surface kind for each column.
3. Java polls the finished keys. It calls `invalidate` for each one.
4. The world calls `fill` again. The engine expands the tile into a brick on demand.

`TerrainExport` writes the vanilla worldgen config (noise router, biomes) as JSON for Rust. Heightfield
tiles use the same engine. Their keys carry `HF_FLAG`, and the engine forgets them after Java takes them.

JNI names must match the Java class and method names in `NativeLib.java`. That class also extracts
the bundled library.

## 8. Block and color mapping

- `BlockClassifier` maps a block state to a voxel id (0 is air). It reads the baked model:
  - Full cubes use the face table.
  - Other shapes (slabs, stairs, plants, fences) use a pool of real model quads.
  - Fluids and block entities become air. Vanilla draws them.
- `LodReduce` chooses one id for each 2x2x2 group of blocks at a coarse level. It prefers visible
  surfaces: grass over dirt, and exposed blocks over covered ones. Plants never win.
- `TintMap` and `LightMap` fill the tint and light tables.
- A resource reload invalidates all voxel ids. `VoxelWorld.refreshAll` rebuilds everything.

## 9. The shader

`voxel.fsh` does the work for each pixel:

1. Build a ray from the inverse view-projection matrix. Positions are relative to the camera block,
   so float precision stays good far from the origin.
2. `trace` walks the ray with a two-level DDA. It skips empty sections and steps voxels inside
   bricks. `levelAt` picks the level from the distance. The level never decreases along a ray.
3. When the ray passes the last voxel level, `traceHeightfield` marches over the height texels.
4. On a hit, the shader reads the texture from the atlas (with a mip level from the pixel angle),
   applies the biome tint, and computes the light. Level 0 has sky light, block light and ambient
   occlusion. Far levels use open-sky light.
5. The shader writes `gl_FragDepth` with the same value vanilla would write.

The alpha channel marks the hit type: 0 none, 0.5 heightfield, 1 other.

## 10. Far hit filter

Far heightfield texels are about as wide as a pixel, and neighboring texels differ (grass, stone,
snow, water, cliff sides), so a single ray per pixel grains. In `traceHeightfield`, a hit whose pixel
covers more than a quarter of a texel is averaged with four more spots of the pixel's footprint on
the ground: two along the ray, where grazing angles stretch the footprint, and two across it.

- `hfSample` reads each spot from the neighboring texel column with no ray of its own: its top when
  the ray passes within about a pixel of the top, its side when the ray is below the top, and nothing
  when the ray is well above it (ground in front of a cliff base) or deep inside it.
- Near hits and voxel bricks are not filtered.

## 11. Keys and debug tools

| Key | Action |
|-----|--------|
| F4 | `VoxelWorld.verify`. Checks GPU bytes against what was uploaded, and refills against resident data. |
| F7 | Voxel renderer or vanilla renderer. |
| F8 | Cycle debug views (step heatmap, level tint, no mipmaps, unlit, and others). |
| F9 | Reload: drop all voxel data and rebuild. |
| F10 | Cycle the far-terrain style (smooth, terraces, and others). |

`RenderStats` and `DebugHud` show timing on screen.

## 12. Where to start reading

| Question | File |
|----------|------|
| How does a frame draw? | `mixin/LevelRendererMixin`, `VoxelRenderer`, `voxel.fsh` |
| How do sections load and unload? | `VoxelWorld`, `BrickTable`, `LOADING.md` |
| Where does terrain come from? | `LayeredSource`, `SeedSource`, `native/src/engine.rs`, `terrain.rs` |
| How do far levels work? | `Lod`, `HeightField`, `traceHeightfield` in `voxel.fsh` |
| How do block ids get textures? | `BlockClassifier`, `LodReduce`, `TintMap`, `LightMap` |

Tests: `BrickTableTest` (Java), `cargo test` in `native/` (includes an oracle test against vanilla
terrain height).
