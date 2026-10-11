# look

Fabric mod for Minecraft 1.21.1 that replaces the chunk renderer with a voxel raycaster.
One fullscreen triangle, raycast in the fragment shader, depth written so vanilla entities,
block entities, particles, clouds and weather draw on top.

## Layout

- `src/main/java/dev/macmv/look/mixin/LevelRendererMixin.java` -- cancels the opaque section layers
- `src/main/java/dev/macmv/look/VoxelRenderer.java` -- the fullscreen pass (GL via LWJGL)
- `src/main/resources/assets/look/shaders/` -- raycast shaders (currently a debug plane at y=64)
- `src/main/java/dev/macmv/look/NativeLib.java` -- JNI bindings, extracts the bundled library
- `native/` -- Rust crate (voxel brick / occupancy building). `cargo test` works standalone.

## Building

Gradle must run on JDK 21 (Loom 1.11.x; newer Loom needs Java 25):

    JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew runClient

`cargoBuild` builds the host platform only; other platforms are a CI job.

## Keys

Rebindable in Options > Controls > Look:

- F7: toggle voxel / vanilla renderer (vanilla meshes are always kept up to date, so it is instant)
- F8: toggle debug view (step-count heatmap, plus timing stats in the action bar and log)
