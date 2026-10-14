<h1 align="center">Look</h1>

<p align="center">
  A voxel raycasting renderer for Minecraft 1.21.1 (Fabric).
</p>

<p align="center">
  <img src="https://img.shields.io/badge/minecraft-1.21.1-62b47a" alt="Minecraft 1.21.1">
  <img src="https://img.shields.io/badge/loader-Fabric-dbd0b4" alt="Fabric">
</p>

<p align="center">
  <img src="docs/splash.png" alt="Forested hills at sunset, with terrain stretching to the horizon">
</p>

## Why

- **No meshes.** Terrain is raycast on the GPU straight from voxel data, so there are no chunk
  meshes to build, upload, or rebuild. Moving fast and flying don't hitch.
- **Real terrain, not a fake horizon.** Distant Horizons-style mods bake low-detail meshes of
  chunks the game already generated. Look instead regenerates terrain locally from the world seed,
  so far terrain is accurate to the real world and nothing is saved to disk or pre-generated.
- **Cheap distance.** Detail drops off in power-of-two steps with distance, so every ring of
  terrain costs about the same. Reach doubles per level without costing more memory.
- **Still Minecraft.** Entities, particles, clouds and weather draw normally on top, and a toggle
  switches back to the vanilla renderer instantly.

## Screenshots

<p align="center">
  <img src="docs/far.png" alt="Far terrain with shaders" width="49%">
  <img src="docs/far-no-shaders.png" alt="Far terrain without shaders" width="49%">
</p>

## Building

```
./gradlew build
./gradlew runClient
```

Needs JDK 21 and a Rust toolchain. See `AGENTS.md` and `docs/` for internals.
