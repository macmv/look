package dev.macmv.look;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.macmv.look.BrickTable.Placement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrickTableTest {
  private static final int CELLS = 64;

  private static BrickTable table(int slots) {
    BrickTable t = new BrickTable();
    t.resetCells(CELLS);
    t.initSlots(slots);
    return t;
  }

  /** The slot a cell points at, or -1. */
  private static int slotOf(BrickTable t, int cell) {
    return t.indirection(cell) - 1;
  }

  @Test
  void privateSectionsGetDistinctSlots() {
    BrickTable t = table(4);
    Placement a = t.place(1, 0, -1);
    Placement b = t.place(2, 1, -1);
    assertTrue(a.upload() && b.upload());
    assertNotEquals(a.slot(), b.slot());
    assertEquals(a.slot(), slotOf(t, 0));
    assertEquals(b.slot(), slotOf(t, 1));
    assertEquals(2, t.used());
  }

  @Test
  void rebuildingAPrivateSectionReusesItsSlotInPlace() {
    BrickTable t = table(4);
    int slot = t.place(1, 0, -1).slot();
    Placement again = t.place(1, 0, -1);
    assertEquals(slot, again.slot());
    assertTrue(again.upload());
    assertEquals(1, t.used());
  }

  @Test
  void uniformSectionsShareOneBrickAndOnlyTheFirstUploads() {
    BrickTable t = table(4);
    Placement a = t.place(1, 0, 7);
    Placement b = t.place(2, 1, 7);
    Placement c = t.place(3, 2, 7);
    assertTrue(a.upload());
    assertFalse(b.upload());
    assertFalse(c.upload());
    assertEquals(a.slot(), b.slot());
    assertEquals(a.slot(), c.slot());
    assertEquals(3, t.refsOf(a.slot()));
    assertEquals(1, t.used());
  }

  @Test
  void differentUniformIdsDoNotShare() {
    BrickTable t = table(4);
    assertNotEquals(t.place(1, 0, 7).slot(), t.place(2, 1, 8).slot());
  }

  @Test
  void sharedBrickIsFreedOnlyWhenTheLastSectionReleases() {
    BrickTable t = table(2);
    int slot = t.place(1, 0, 7).slot();
    t.place(2, 1, 7);

    assertTrue(t.release(1, 0));
    assertEquals(1, t.used());
    assertEquals(7, t.uniformOf(slot));
    assertEquals(0, t.indirection(0));

    assertTrue(t.release(2, 1));
    assertEquals(0, t.used());
    assertEquals(-1, t.uniformOf(slot));

    // A new uniform section of that id must upload again: the old data may have been overwritten.
    assertTrue(t.place(3, 2, 7).upload());
  }

  @Test
  void freedSharedSlotCanBecomeAPrivateBrick() {
    BrickTable t = table(1);
    int shared = t.place(1, 0, 7).slot();
    t.release(1, 0);
    Placement p = t.place(2, 1, -1);
    assertEquals(shared, p.slot());
    assertEquals(-1, t.uniformOf(p.slot()));
    // Rebuilt in place as private, not mistaken for a shared brick.
    assertEquals(p.slot(), t.place(2, 1, -1).slot());
    assertEquals(1, t.used());
  }

  @Test
  void sectionMovingFromPrivateToUniformFreesItsOldSlot() {
    BrickTable t = table(2);
    int priv = t.place(1, 0, -1).slot();
    Placement p = t.place(1, 0, 7);
    assertNotEquals(priv, p.slot());
    assertTrue(p.upload());
    assertEquals(1, t.used());
    assertEquals(1, t.refsOf(p.slot()));
  }

  @Test
  void sectionMovingFromUniformToMixedDropsItsRef() {
    BrickTable t = table(3);
    int shared = t.place(1, 0, 7).slot();
    t.place(2, 1, 7);
    Placement p = t.place(1, 0, -1);
    assertNotEquals(shared, p.slot());
    assertEquals(1, t.refsOf(shared));
    assertEquals(2, t.used());
  }

  @Test
  void sectionMovingBetweenUniformIdsSwitchesBricks() {
    BrickTable t = table(3);
    int a = t.place(1, 0, 7).slot();
    t.place(2, 1, 8);
    int b = t.place(1, 0, 8).slot();
    assertNotEquals(a, b);
    assertEquals(-1, t.uniformOf(a));
    assertEquals(2, t.refsOf(b));
    assertEquals(1, t.used());
  }

  @Test
  void replacingAUniformSectionWithTheSameUniformIdChangesNothing() {
    BrickTable t = table(2);
    int slot = t.place(1, 0, 7).slot();
    Placement again = t.place(1, 0, 7);
    assertEquals(slot, again.slot());
    assertFalse(again.upload());
    assertEquals(1, t.refsOf(slot));
  }

  @Test
  void fullPoolStarvesWithoutDisturbingExistingSections() {
    BrickTable t = table(1);
    int slot = t.place(1, 0, -1).slot();
    Placement starved = t.place(2, 1, -1);
    assertTrue(starved.slot() < 0);
    assertEquals(0, t.indirection(1));
    assertEquals(BrickTable.NO_OWNER, t.owner(1));
    assertEquals(slot, slotOf(t, 0));
  }

  @Test
  void starvedSectionThatOwnsASlotKeepsIt() {
    BrickTable t = table(2);
    int shared = t.place(1, 0, 7).slot();
    t.place(2, 1, 7);
    t.place(3, 2, -1); // takes the last slot
    // Section 1 turns mixed but there is no slot to move to: it keeps showing the shared brick.
    Placement p = t.place(1, 0, -1);
    assertTrue(p.slot() < 0);
    assertEquals(shared, slotOf(t, 0));
    assertEquals(2, t.refsOf(shared));
  }

  @Test
  void slotsFreedByReleaseAreHandedOutAgain() {
    BrickTable t = table(1);
    int slot = t.place(1, 0, -1).slot();
    assertTrue(t.place(2, 1, -1).slot() < 0);
    t.release(1, 0);
    assertEquals(slot, t.place(2, 1, -1).slot());
  }

  @Test
  void releaseOfANonOwnerDoesNothing() {
    BrickTable t = table(2);
    int slot = t.place(1, 0, -1).slot();
    assertFalse(t.release(2, 0));
    assertFalse(t.release(1, 5));
    assertEquals(slot, slotOf(t, 0));
    assertEquals(1, t.used());
  }

  @Test
  void releaseTwiceDoesNotDoubleFree() {
    BrickTable t = table(2);
    t.place(1, 0, -1);
    assertTrue(t.release(1, 0));
    assertFalse(t.release(1, 0));
    assertEquals(0, t.used());
    // Both slots must still be distinct after the double release.
    assertNotEquals(t.place(2, 1, -1).slot(), t.place(3, 2, -1).slot());
  }

  @Test
  void aliasingSectionEvictsTheOldOwnerAndFreesItsSlot() {
    BrickTable t = table(2);
    int old = t.place(1, 0, -1).slot();
    Placement p = t.place(2, 0, -1);
    assertEquals(1, p.evicted());
    assertEquals(2, t.owner(0));
    assertEquals(old, p.slot()); // the freed slot is the only one handed out again
    assertEquals(1, t.used());
    // The evicted section no longer owns anything, so releasing it is a no-op.
    assertFalse(t.release(1, 0));
    assertEquals(old, slotOf(t, 0));
  }

  @Test
  void evictionStillHappensWhenTheNewSectionStarves() {
    BrickTable t = table(1);
    t.place(1, 0, 7);
    t.place(2, 1, 7); // shares: refs 2
    // Section 3 aliases section 1's cell and is mixed; section 2 still holds the shared brick.
    Placement p = t.place(3, 0, -1);
    assertEquals(1, p.evicted());
    assertTrue(p.slot() < 0);
    assertEquals(BrickTable.NO_OWNER, t.owner(0));
    assertEquals(0, t.indirection(0));
    assertEquals(1, t.refsOf(slotOf(t, 1)));
  }

  @Test
  void resetCellsGivesEverySlotBack() {
    BrickTable t = table(3);
    t.place(1, 0, -1);
    t.place(2, 1, 7);
    t.resetCells(CELLS);
    assertEquals(0, t.used());
    assertEquals(0, t.indirection(0));
    assertEquals(BrickTable.NO_OWNER, t.owner(1));
    assertTrue(t.place(3, 0, 7).upload());
  }

  /**
   * Random sequences of loads, rebuilds with changing content, releases and resets, in an order that
   * differs per seed (as chunk arrival differs per launch). After every step the whole table must
   * be consistent: no slot is owned by two private sections, shared refcounts match the cells that
   * point at them, and every slot is either in use or free exactly once.
   */
  @Test
  void randomOperationsKeepTheTableConsistent() {
    for (long seed = 0; seed < 300; seed++) {
      Random rng = new Random(seed);
      int slots = 1 + rng.nextInt(12);
      BrickTable t = table(slots);
      // What the caller believes each section owns: key -> cell, and cell -> key.
      Map<Long, Integer> cellOfKey = new HashMap<>();

      for (int step = 0; step < 400; step++) {
        int op = rng.nextInt(10);
        // Few cells and few keys, so aliasing and starvation happen constantly.
        int cell = rng.nextInt(CELLS / 4);
        long key = 100 + rng.nextInt(CELLS / 2);
        if (op < 6) {
          int c = cellOfKey.getOrDefault(key, cell);
          int uniform = rng.nextInt(3) == 0 ? -1 : rng.nextInt(4);
          Placement p = t.place(key, c, uniform);
          if (p.evicted() != BrickTable.NO_OWNER) cellOfKey.remove(p.evicted());
          if (p.slot() >= 0) {
            cellOfKey.put(key, c);
            assertEquals(p.slot(), slotOf(t, c), "seed " + seed + " step " + step);
            assertEquals(key, t.owner(c));
            assertEquals(uniform, t.uniformOf(p.slot()), "slot kind, seed " + seed + " step " + step);
            if (uniform < 0) assertTrue(p.upload());
          }
        } else if (op < 9) {
          Integer c = cellOfKey.remove(key);
          if (c != null) assertTrue(t.release(key, c));
        } else if (rng.nextInt(20) == 0) {
          t.resetCells(CELLS);
          cellOfKey.clear();
        }
        check(t, slots, "seed " + seed + " step " + step);
      }
    }
  }

  private static void check(BrickTable t, int slots, String where) {
    assertEquals(java.util.List.of(), t.checkInvariants(), "invariants, " + where);
    Map<Integer, Integer> pointing = new HashMap<>();
    Set<Integer> privateSlots = new HashSet<>();
    for (int cell = 0; cell < CELLS; cell++) {
      boolean owned = t.owner(cell) != BrickTable.NO_OWNER;
      assertEquals(owned, t.indirection(cell) != 0, "owner and indirection agree at cell " + cell + ", " + where);
      if (!owned) continue;
      int slot = t.indirection(cell) - 1;
      assertTrue(slot >= 0 && slot < slots, where);
      pointing.merge(slot, 1, Integer::sum);
      if (t.uniformOf(slot) < 0) assertTrue(privateSlots.add(slot), "private slot shared, " + where);
    }

    Set<Integer> uniformIds = new HashSet<>();
    for (int slot = 0; slot < slots; slot++) {
      int refs = pointing.getOrDefault(slot, 0);
      if (t.uniformOf(slot) >= 0) {
        assertEquals(refs, t.refsOf(slot), "refcount of slot " + slot + ", " + where);
        assertTrue(refs > 0, "shared slot with no users, " + where);
        assertTrue(uniformIds.add(t.uniformOf(slot)), "two shared bricks of one id, " + where);
      }
    }
    // Every slot is in use (pointed at) or free, never both, never lost.
    assertEquals(pointing.size(), t.used(), "used slots, " + where);
    assertEquals(slots, t.used() + t.freeCount(), where);
  }

  @Test
  void emptySectionsMarkTheirCellWithoutASlot() {
    BrickTable t = table(4);
    assertEquals(0, t.indirection(0));
    assertEquals(BrickTable.NO_OWNER, t.markEmpty(1, 0));
    assertEquals(BrickTable.EMPTY_CELL, t.indirection(0));
    assertTrue(t.owns(0, 1));
    assertEquals(0, t.used());
    assertTrue(t.checkInvariants().isEmpty());

    // Releasing goes back to "not resolved".
    assertTrue(t.release(1, 0));
    assertEquals(0, t.indirection(0));
    assertTrue(t.checkInvariants().isEmpty());
  }

  @Test
  void emptyingASectionFreesItsSlotAndPlacingOverAnEmptyMarkWorks() {
    BrickTable t = table(4);
    t.place(1, 0, -1);
    assertEquals(1, t.used());
    t.markEmpty(1, 0);
    assertEquals(0, t.used());
    assertEquals(BrickTable.EMPTY_CELL, t.indirection(0));

    Placement p = t.place(1, 0, -1);
    assertTrue(p.slot() >= 0);
    assertEquals(p.slot(), slotOf(t, 0));
    assertEquals(1, t.used());
    assertTrue(t.checkInvariants().isEmpty());
  }

  @Test
  void markingEmptyEvictsAnotherOwner() {
    BrickTable t = table(4);
    t.place(1, 0, -1);
    assertEquals(1, t.markEmpty(2, 0));
    assertEquals(0, t.used());
    assertEquals(BrickTable.EMPTY_CELL, t.indirection(0));
    assertTrue(t.owns(0, 2));
    assertTrue(t.checkInvariants().isEmpty());
  }
}
