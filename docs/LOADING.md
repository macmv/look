# Loading and unloading, as state machines

Four things have a life cycle. Today only the first is a real enum; the others are implied by
flags and maps. Writing them down is what a Vulkan port needs, because there each transition that
crosses CPU -> GPU needs a fence or barrier.

## 1. Tile (Rust, `native/src/engine.rs`) -- one section column of one level

    ABSENT --request--> QUEUED --worker pops--> RUNNING --insert--> READY --evict--> ABSENT
       ^                  |                                            |
       +-- out of range --+                                            +-- poll() reports it once

- Today: `queue.pending` (QUEUED or RUNNING), `tiles` (READY), `done` (READY, not yet reported).
- A tile may be READY while no brick exists for it, and a brick may exist after its tile was
  evicted. The brick is a copy; the tile is only the recipe.

## 2. Section (Java, `VoxelWorld.SectionState`) -- one 16^3 brick of one level

    (untracked) --event--> WAITING --rebuild--> LOADED | EMPTY | STARVED | LOADING
    LOADING --source finished--> WAITING
    any --left the view--> OUT_OF_RANGE --entered--> WAITING
    any --column dropped--> (untracked)

## 3. Slot (Java, `BrickTable`) -- one 8 KiB brick in the GPU buffer

    FREE --place--> ALLOCATED(private | shared, refs) --release--> FREE

The CPU decides when a slot is FREE. The GPU may still be reading it. OpenGL hides that from us
(the driver orders `glBufferSubData` after earlier draws, copying if it must). Vulkan does not.

## 4. GPU visibility -- what the shader can see

    CPU-ONLY --written to staging--> STAGED --copy submitted--> IN-FLIGHT --fence signals--> VISIBLE
    VISIBLE --release (cell zeroed)--> RETIRING --last frame that could read it finishes--> FREE

This machine does not exist in the code. Today a slot goes ALLOCATED -> written -> LOADED in one
call, and FREE the moment `release` runs.

## What Vulkan would change

1. **Slot reuse needs a grace period.** After `release`, a slot must stay RETIRING until every
   frame in flight that could sample the old indirection has finished (frames-in-flight fence
   values). Reusing it immediately overwrites bricks the previous frame is still drawing.
2. **Writes go through staging.** `rebuildSection` fills a CPU scratch, which becomes a staging
   buffer slice + `vkCmdCopyBuffer`. The scratch must be per-frame (ring), not one shared buffer.
3. **Indirection updates are ordered after brick copies.** A cell may only point at a slot once
   the copy is visible: copy -> barrier (TRANSFER -> FRAGMENT_SHADER read) -> cell write. Today
   the same ordering is implicit in call order.
4. **`glTexSubImage3D` per cell becomes one buffer copy per frame** (indirection as a buffer or
   an image updated by one batched copy), double-buffered, so the shader never sees half of an
   update.
5. **Native tile results are not GPU resources**, so they need no fences, but `fetch` runs on the
   render thread and reads the shared `ids`; those should be versioned so a brick records which
   id table it was built with.
6. **Resource reload** (`refreshAll`) must wait for the device to be idle (or retire every slot)
   before the id/face tables change.

## Bugs this structure would catch

- A slot referenced by two live cells, or free and referenced (`BrickTable.checkInvariants`).
- A brick that changed after upload (`VoxelWorld.verify`: GPU bytes vs the hash taken at write).
- A source that fills a section differently now than when it was uploaded, with no invalidate
  (`VoxelWorld.verify`: refill vs resident).
