package dev.macmv.look;

/**
 * Where one cell of a {@link HeightField} window is in its life. The cell holds at most one tile's
 * texels, and the GPU shows them (the cell's maximum is not {@link HeightField#NONE}) only in
 * {@link #SHOWN}.
 *
 * <pre>
 *   EMPTY --request--> REQUESTED --tile arrives--> SHOWN | BLANK
 *   SHOWN | BLANK --camera moved on--> STALE --request--> REQUESTED
 *   REQUESTED --timeout--> REQUESTED (asked again)
 *   SHOWN | BLANK | STALE --duplicate or late arrival--> SHOWN | BLANK
 *   any --reset--> EMPTY
 * </pre>
 *
 * <p>Entering STALE from SHOWN queues a hide, and the hide must reach the GPU before any upload of
 * the old tile does; {@link HeightField.Level#checkInvariants} compares a shadow of what the GPU
 * holds against these states.
 */
enum TileCell {
  /** Nothing held, nothing asked for. */
  EMPTY,
  /** Asked the engine for the cell's tile. The cell may still hold an older tile, hidden. */
  REQUESTED,
  /** Holds a tile with terrain in it, and rays can enter it. */
  SHOWN,
  /** Holds a tile with no terrain at all (open sky or void). Rays skip it, same as hidden. */
  BLANK,
  /** Holds the tile of a window position the camera has left. Hidden until a new request goes out. */
  STALE;

  boolean canGo(TileCell to) {
    return switch (this) {
      case EMPTY -> to == REQUESTED;
      case REQUESTED -> to == REQUESTED || to == SHOWN || to == BLANK;
      case SHOWN, BLANK -> to == STALE || to == SHOWN || to == BLANK;
      case STALE -> to == REQUESTED || to == SHOWN || to == BLANK;
    };
  }

  /** Whether the cell holds a tile's texels, whatever the GPU shows of them. */
  boolean holdsTile() {
    return this == SHOWN || this == BLANK || this == STALE;
  }
}
