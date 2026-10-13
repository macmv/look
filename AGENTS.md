# Workflow

The user runs the game themselves (the build output is symlinked into their instance). Do not try
to launch Minecraft.

After finishing a chunk of work:

1. Build with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build` (Loom 1.11.x needs JDK 21 for
   Gradle). Run `cargo test` in `native/` too if the Rust crate changed.
2. Fix any build failures before continuing. Additionally, format native code with `cargo fmt`.
3. Render tests (`src/test/java/dev/macmv/look/gl`) draw fixed scenes through the real shader on
   Mesa software GL and compare them to `src/test/resources/golden`. Run them with
   `LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a ./gradlew test`; they skip without a display or on a
   hardware GPU. After an intended visual change, rerun with `-PupdateGolden`, look at the images,
   and commit them. Failures leave `-actual`/`-diff` PNGs in `build/render-tests`.
4. Tell the user to run the game and say what to look for, since you cannot see the result.
5. Commit the changes when asked, or once the build passes if the user has asked you to commit.

# Rules

- ASCII only in files and docs: `--` not an em dash, `->` not an arrow, straight quotes.
- Never add Co-Authored-By lines or any AI attribution to commit messages or PR descriptions.

# Project notes

- Fabric 1.21.1, Mojang mappings. Fabric API is a dependency for networking only (the server sends
  its terrain description to clients); do not lean on it for rendering or world access.
- Raycasting is done in the fragment shader; Rust (`native/`) owns CPU-side voxel data and talks to
  Java over JNI with coarse calls and direct buffers. Java owns all GL calls.
- Go through `GlStateManager` / `RenderSystem` for GL state vanilla caches, so its cache stays valid.
- Prefer `@Inject` over `@Overwrite` / `@Redirect` in mixins, to coexist with other mods.

## Architecture

Fabric 1.21.1 client mod replacing the chunk renderer with a voxel raycaster: one fullscreen
triangle, raycast in `assets/look/shaders/voxel.fsh`, depth written so vanilla entities, particles,
and weather still draw on top. `LevelRendererMixin` cancels vanilla's opaque section layers.
Vanilla meshes are still built, so F7 (toggle) is instant.

**Level-of-detail pyramid** (`Lod`): level `k` has voxels `2^k` blocks wide, chosen in the shader by
horizontal Chebyshev distance. Every level is a same-size ring of sections, so memory per level is
constant and reach doubles per level. Only the first `Lod.LEVELS` are voxel bricks; the levels past
that (`HF_LEVELS`) are a ray-marched heightfield (`HeightField`) fed by tiles from the native engine.

**Java side** (`dev.macmv.look`): Java owns all GL.
- `VoxelWorld` is the core: per-level residency of 16^3 sections (`SectionState` machine), the
  `BrickTable` slot allocator for the GPU brick buffer, and the indirection grid the shader walks.
  `VoxelWorld.verify` (F4) cross-checks GPU bytes and refills against what was uploaded.
- `VoxelSource` is the producer interface, assigned per level with `VoxelWorld.setSource`. A source
  pushes (`invalidate`) and/or is pulled (`enumerated`); `fill` returns a count, `UNAVAILABLE`, or
  `PENDING`. Implementations: `WorldSource` (loaded client chunks), `SeedSource` (generated from
  the seed via native), `HillsSource`, and `LayeredSource` (real chunks over generated terrain).
- `WorldDescriptor` (seed plus `TerrainExport` config) is how `SeedSource` learns the world. In
  singleplayer it is read from the integrated server; in multiplayer the server half (`LookCommon`,
  common code, must not touch client classes) sends it as a `WorldDescriptorPayload` on join and
  `ClientWorldDescriptor` receives it. The client regenerates terrain locally; nothing is cached.
- `BlockClassifier`, `TintMap`, `LightMap` turn block ids/models/biomes/light into the u16 voxel ids
  and tables the shader reads. `TerrainExport` serializes the vanilla worldgen config for Rust.
- `docs/loading.md` documents the tile / section / slot / heightfield cell / GPU-visibility life cycles and what a
  Vulkan port would change; read it before touching residency or slot reuse.

**Native side** (`native/`, crate `look_native`): pure Rust voxel data and a port of vanilla
overworld terrain generation (`rng`, `noise`, `density`, `biome`, `terrain`), plus `engine` (worker
threads producing tiles per level/section column) and `brick` (occupancy masks). `lib.rs` is the
thin JNI layer: coarse calls with direct buffers, and `guard` turns panics into Java
`RuntimeException`s, so never let a panic cross the boundary. `NativeLib.java` declares the
bindings and extracts the bundled library. JNI symbol names must match the Java class/method names.
