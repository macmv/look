package dev.macmv.look;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The CPU bookkeeping behind {@link VoxelWorld}'s indirection texture: which section owns each cell,
 * which brick slot each cell points at, and which slots are free. No GL and no Minecraft, so it can
 * be tested on its own.
 *
 * <p>A section that is one voxel id throughout shares a brick with every other section of that id
 * (refcounted); all other sections own a private slot. Not thread safe: {@link VoxelWorld} only
 * touches it from the render thread.
 */
final class BrickTable {
  static final long NO_OWNER = Long.MIN_VALUE;

  /** The result of {@link #place}. A negative {@code slot} means the pool was full. */
  record Placement(int slot, boolean upload, long evicted) {}

  private int[] indirection = new int[0];
  private long[] owner = new long[0];

  private int[] freeSlots;
  private int freeCount;
  // Per slot the voxel id a shared brick is filled with (-1 for a private brick or a free slot) and
  // how many sections use it, plus the reverse lookup.
  private int[] slotUniform;
  private int[] slotRefs;
  private final Map<Integer, Integer> uniformSlots = new HashMap<>();
  private int capacity;

  /** Forgets every cell and gives every slot back. Sections must be re-placed afterwards. */
  void resetCells(int cells) {
    indirection = new int[cells];
    owner = new long[cells];
    Arrays.fill(owner, NO_OWNER);
    resetSlots();
  }

  /** Sets the number of brick slots, all free. */
  void initSlots(int capacity) {
    this.capacity = capacity;
    freeSlots = new int[capacity];
    slotUniform = new int[capacity];
    slotRefs = new int[capacity];
    resetSlots();
  }

  private void resetSlots() {
    if (freeSlots == null) return;
    freeCount = 0;
    for (int i = capacity - 1; i >= 0; i--) freeSlots[freeCount++] = i;
    Arrays.fill(slotUniform, -1);
    Arrays.fill(slotRefs, 0);
    uniformSlots.clear();
  }

  int capacity() {
    return capacity;
  }

  int freeCount() {
    return freeCount;
  }

  int used() {
    return capacity - freeCount;
  }

  /** The cell value for the texture: slot + 1, or 0 when empty. */
  int indirection(int cell) {
    return indirection[cell];
  }

  long owner(int cell) {
    return owner[cell];
  }

  boolean owns(int cell, long key) {
    return owner[cell] == key;
  }

  /** The voxel id the slot's shared brick is filled with, or -1 for a private or free slot. */
  int uniformOf(int slot) {
    return slotUniform[slot];
  }

  int refsOf(int slot) {
    return slotRefs[slot];
  }

  /**
   * Gives {@code key} a slot in {@code cell}. {@code uniform} is the voxel id the section is filled
   * with, or -1 if it is mixed. If another section owns the cell it is evicted (its slot freed) and
   * reported in {@link Placement#evicted}. The slot {@code key} already owns is only given up once
   * the new one is secured, so a starved section keeps what it had.
   *
   * <p>{@link Placement#upload} says whether the brick data must be written to the slot: false when
   * the section joined an existing shared brick.
   */
  Placement place(long key, int cell, int uniform) {
    long evicted = NO_OWNER;
    // Cannot happen while the view fits the grid, but never let two sections share a cell.
    if (owner[cell] != NO_OWNER && owner[cell] != key) {
      evicted = owner[cell];
      release(evicted, cell);
    }

    int own = owner[cell] == key && indirection[cell] != 0 ? indirection[cell] - 1 : -1;

    int slot;
    boolean upload = true;
    if (uniform >= 0) {
      Integer shared = uniformSlots.get(uniform);
      if (shared != null) {
        slot = shared;
        upload = false;
      } else {
        slot = allocSlot();
        if (slot < 0) return new Placement(-1, false, evicted);
        slotUniform[slot] = uniform;
        slotRefs[slot] = 0;
        uniformSlots.put(uniform, slot);
      }
      if (own != slot) {
        slotRefs[slot]++;
        if (own >= 0) freeSlot(own);
      }
    } else if (own >= 0 && slotUniform[own] < 0) {
      slot = own; // a private brick, rewritten in place
    } else {
      slot = allocSlot();
      if (slot < 0) return new Placement(-1, false, evicted);
      if (own >= 0) freeSlot(own); // it was shared with other sections
    }

    owner[cell] = key;
    indirection[cell] = slot + 1;
    return new Placement(slot, upload, evicted);
  }

  /**
   * Frees the slot {@code key} owns in {@code cell}, if it owns it. Returns whether the cell
   * changed, i.e. whether it needs uploading.
   */
  boolean release(long key, int cell) {
    if (owner[cell] != key) return false;
    if (indirection[cell] != 0) freeSlot(indirection[cell] - 1);
    indirection[cell] = 0;
    owner[cell] = NO_OWNER;
    return true;
  }

  private int allocSlot() {
    if (freeCount == 0) return -1;
    return freeSlots[--freeCount];
  }

  /** Gives a slot back. A shared brick is only freed when its last section lets go. */
  private void freeSlot(int slot) {
    int id = slotUniform[slot];
    if (id >= 0) {
      if (--slotRefs[slot] > 0) return;
      uniformSlots.remove(id);
      slotUniform[slot] = -1;
    }
    freeSlots[freeCount++] = slot;
  }

  /**
   * Checks the bookkeeping against itself and returns what is wrong, empty if all is well. Cells
   * and owners agree, every used slot is referenced as often as it claims (once for a private
   * brick), no referenced slot is also free, and no slot is free twice.
   */
  java.util.List<String> checkInvariants() {
    java.util.List<String> bad = new java.util.ArrayList<>();
    int[] refs = new int[capacity];

    for (int cell = 0; cell < indirection.length; cell++) {
      boolean hasOwner = owner[cell] != NO_OWNER;
      if ((indirection[cell] != 0) != hasOwner) {
        bad.add("cell " + cell + ": indirection " + indirection[cell] + " but owner " + owner[cell]);
      }
      int slot = indirection[cell] - 1;
      if (slot >= capacity) bad.add("cell " + cell + " points past the pool: slot " + slot);
      else if (slot >= 0) refs[slot]++;
    }

    boolean[] free = new boolean[capacity];
    for (int i = 0; i < freeCount; i++) {
      int slot = freeSlots[i];
      if (free[slot]) bad.add("slot " + slot + " is free twice");
      free[slot] = true;
    }

    for (int slot = 0; slot < capacity; slot++) {
      if (free[slot]) {
        if (refs[slot] != 0) bad.add("slot " + slot + " is free but " + refs[slot] + " cells use it");
        if (slotUniform[slot] >= 0) bad.add("slot " + slot + " is free but marked uniform " + slotUniform[slot]);
        continue;
      }
      if (slotUniform[slot] >= 0) {
        if (refs[slot] != slotRefs[slot]) {
          bad.add("shared slot " + slot + ": " + refs[slot] + " cells but refcount " + slotRefs[slot]);
        }
        Integer back = uniformSlots.get(slotUniform[slot]);
        if (back == null || back != slot) bad.add("shared slot " + slot + " is missing from the uniform lookup");
      } else if (refs[slot] != 1) {
        bad.add("private slot " + slot + " is used by " + refs[slot] + " cells");
      }
    }
    for (Map.Entry<Integer, Integer> e : uniformSlots.entrySet()) {
      if (slotUniform[e.getValue()] != e.getKey()) bad.add("uniform lookup " + e.getKey() + " -> slot " + e.getValue() + " is stale");
    }
    return bad;
  }
}
