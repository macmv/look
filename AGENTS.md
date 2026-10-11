# Workflow

The user runs the game themselves (the build output is symlinked into their instance). Do not try
to launch Minecraft.

After finishing a chunk of work:

1. Build with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build` (Loom 1.11.x needs JDK 21 for
   Gradle). Run `cargo test` in `native/` too if the Rust crate changed.
2. Fix any build failures before continuing.
3. Tell the user to run the game and say what to look for, since you cannot see the result.
4. Commit the changes when asked, or once the build passes if the user has asked you to commit.

# Rules

- ASCII only in files and docs: `--` not an em dash, `->` not an arrow, straight quotes.
- Never add Co-Authored-By lines or any AI attribution to commit messages or PR descriptions.

# Project notes

- Fabric 1.21.1, Mojang mappings, no Fabric API dependency. Keep it that way unless needed.
- Raycasting is done in the fragment shader; Rust (`native/`) owns CPU-side voxel data and talks to
  Java over JNI with coarse calls and direct buffers. Java owns all GL calls.
- Go through `GlStateManager` / `RenderSystem` for GL state vanilla caches, so its cache stays valid.
- Prefer `@Inject` over `@Overwrite` / `@Redirect` in mixins, to coexist with other mods.
