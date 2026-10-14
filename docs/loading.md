# Loading and unloading, as state machines

Five things have a life cycle. Sections (2) and heightfield cells (4) are real enums in Java
(`VoxelWorld.SectionState`, `TileCell`); the others are implied by flags and maps. Writing them
down is what a Vulkan port needs, because there each transition that crosses CPU -> GPU needs a
fence or barrier.

## 1. Tile (Rust, `native/src/engine.rs`) -- one section column of one level

A tile is the recipe for a column: what the generator predicts for it. There are two kinds, told
apart by `HF_FLAG` in the key: voxel tiles (feed bricks for the voxel levels) and heightfield
tiles (feed the far field).

    ABSENT --request--> QUEUED --worker pops--> RUNNING --insert--> READY --evict--> ABSENT
       ^                  |                                            |
       +-- out of range --+                                            +-- poll() reports it once

- Today: `queue.pending` (QUEUED or RUNNING), `tiles` (READY), `done` (READY, not yet reported).
- The queue is a heap, nearest the camera first. `set_camera` re-sorts it after the camera moves
  `SORT_DISTANCE` blocks, and drops queued jobs that are now out of range (QUEUED -> ABSENT).
- READY voxel tiles are evicted by `set_camera` (at most every `EVICT_INTERVAL`) once they are
  more than `want_sections + KEEP_MARGIN` sections away. The margin stops a walk back and forth
  over a border from recomputing them.
- A READY heightfield tile leaves sooner: `take_heightfield` copies it out as texels and removes
  it, so the far field costs no memory in Rust once the GPU has it.
- A tile may be READY while no brick exists for it, and a brick may exist after its tile was
  evicted. The brick is a copy; the tile is only the recipe. `fetch` builds one brick of a READY
  tile on demand, on the render thread, under a read lock on the shared `ids`.

## 2. Section (Java, `VoxelWorld.SectionState`) -- one 16^3 brick of one level

    (untracked) --event--> WAITING --rebuild--> LOADED | EMPTY | STARVED | LOADING
    LOADING --source finished--> WAITING
    WAITING | LOADING | LOADED | EMPTY | STARVED --left the view--> OUT_OF_RANGE
    OUT_OF_RANGE --entered the view--> WAITING
    any --column dropped--> (untracked)

- `processWaiting` rebuilds WAITING sections nearest-first until `BUDGET_NANOS` runs out, so a
  burst of events spreads over frames.
- `rebuildSection` asks the level's `VoxelSource` to `fill` a scratch brick. The result decides the
  next state: a solid count places it (LOADED), 0 releases any slot (EMPTY), `PENDING` keeps the
  old slot and waits for the source to announce it is done (LOADING), and `UNAVAILABLE` forgets
  the section (untracked) until the source announces it again.
- STARVED means the brick pool was full. Those sections are retried as slots free up.
- `BrickTable.place` can take a slot from a section that left the view; that one drops to
  OUT_OF_RANGE.
- EMPTY and OUT_OF_RANGE own no slot. WAITING and LOADING may still own one, which keeps the old
  data on screen until the rebuild lands.
- What the shader reads in the section's indirection cell: LOADED is the slot, EMPTY is
  `BrickTable.EMPTY_CELL` (the section is resolved and all air: rays skip it), and every section with
  no slot otherwise (untracked, STARVED, OUT_OF_RANGE, WAITING or LOADING without an old value) is 0,
  "not resolved yet". In a 0 cell the shader draws the far heightfield (first level, which also
  covers the area under the voxel levels) up to where the ray leaves the section, so terrain shows
  while the bricks load and the section switches to voxels the frame its cell is written. EMPTY
  owns its cell (so `markEmpty` can evict a stale owner) but no slot.

## 3. Slot (Java, `BrickTable`) -- one 8 KiB brick in the GPU buffer

    FREE --place--> ALLOCATED(private | shared, refs) --release--> FREE

- A section that is one voxel id throughout (`solid == BRICK_VOXELS`) joins a shared brick for that
  id and bumps its refcount instead of uploading. The slot is FREE once the last section lets go.
  Every other section owns a private slot.
- `place` reports whether the caller must upload (`upload`), and which section lost its slot, if
  one did (`evicted`).

The CPU decides when a slot is FREE. The GPU may still be reading it. OpenGL hides that from us
(the driver orders `glBufferSubData` after earlier draws, copying if it must). Vulkan does not.

## 4. Heightfield cell (Java, `TileCell`) -- one tile position in a far-field window

Each heightfield level is a window of cells around the camera. A cell holds at most one tile's
texels, and the GPU shows them only in SHOWN.

    EMPTY --request--> REQUESTED --tile arrives--> SHOWN | BLANK
    SHOWN | BLANK --camera moved on--> STALE --request--> REQUESTED
    REQUESTED --timeout--> REQUESTED (asked again)
    SHOWN | BLANK | STALE --duplicate or late arrival--> SHOWN | BLANK
    any --reset--> EMPTY

- BLANK is a tile with no terrain (open sky or void); rays skip it. Its tile maximum is `BLANK`, not
  `NONE`: a `NONE` tile is one that has not arrived, and there the shader falls back to the coarser
  level (which keeps its tiles inside the finer level's range) until the finer tile lands.
- Entering STALE from SHOWN or BLANK queues a hide, and the hide must reach the GPU before any upload of the
  old tile does. This is the one place the code already orders GPU writes by state.
- `HeightField.Level.move` is the only way a cell changes state. An illegal edge is counted, not fatal.
- Cells are scanned a few per frame (a whole level takes about a dozen), and each request goes to
  the engine as a heightfield tile (1).

## 5. GPU visibility -- what the shader can see

    CPU-ONLY --written to staging--> STAGED --copy submitted--> IN-FLIGHT --fence signals--> VISIBLE
    VISIBLE --release (cell zeroed)--> RETIRING --last frame that could read it finishes--> FREE

This machine does not exist in the code, for voxel bricks. Today a slot goes ALLOCATED -> written
-> LOADED in one call (`glBufferSubData` in `rebuildSection`), and FREE the moment `release` runs.
The indirection grid is written one cell at a time by `flushCells`, after the brick it points at.

## What Vulkan would change

1. **Slot reuse needs a grace period.** After `release`, a slot must stay RETIRING until every
   frame in flight that could sample the old indirection has finished (frames-in-flight fence
   values). Reusing it immediately overwrites bricks the previous frame is still drawing. This
   includes the shared-brick refcount reaching zero.
2. **Writes go through staging.** `rebuildSection` fills a CPU scratch, which becomes a staging
   buffer slice + `vkCmdCopyBuffer`. The scratch must be per-frame (ring), not one shared buffer.
   The same goes for the heightfield, tint and light uploads (`HeightField`, `TintMap`,
   `LightMap`), which all use `glTexSubImage3D` / `glBufferSubData` today.
3. **Indirection updates are ordered after brick copies.** A cell may only point at a slot once
   the copy is visible: copy -> barrier (TRANSFER -> FRAGMENT_SHADER read) -> cell write. Today
   the same ordering is implicit in call order. The heightfield's hide-before-upload rule (4) is
   the same constraint.
4. **`glTexSubImage3D` per cell (`flushCells`) becomes one buffer copy per frame** (indirection as
   a buffer or an image updated by one batched copy), double-buffered, so the shader never sees
   half of an update.
5. **Native tile results are not GPU resources**, so they need no fences, but `fetch` runs on the
   render thread and reads the shared `ids`; those should be versioned so a brick records which
   id table it was built with.
6. **Resource reload** (`refreshAll`, triggered by an atlas re-stitch or a tint radius change)
   must wait for the device to be idle (or retire every slot) before the id/face tables change.

## Bugs this structure would catch

All of these run from F4 (`VoxelWorld.verify`).

- A slot referenced by two live cells, or free and referenced (`BrickTable.checkInvariants`).
- A brick that changed after upload (GPU bytes vs the hash taken at write).
- A source that fills a section differently now than when it was uploaded, with no invalidate
  (refill vs resident).
- A heightfield cell whose state disagrees with what it holds or what the GPU shows
  (`HeightField.checkInvariants`, `HeightField.verifyGpu`).
- The biome tint texture differing from what was uploaded (`TintMap.verifyGpu`).
