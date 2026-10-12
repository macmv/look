package dev.macmv.look;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.macmv.look.HeightField.Level;
import dev.macmv.look.HeightField.Upload;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeightFieldCellsTest {
  private static List<String> check(Level l, List<Upload> ups, List<int[]> hides) {
    return l.checkInvariants(ups, hides, 0);
  }

  @Test
  void freshLevelIsConsistent() {
    assertTrue(check(new Level(), List.of(), List.of()).isEmpty());
  }

  @Test
  void legalLifeCycle() {
    Level l = new Level();
    l.move(5, TileCell.REQUESTED);
    l.requested[5] = 1;
    l.move(5, TileCell.SHOWN);
    l.resident[5] = 1;
    l.move(5, TileCell.STALE);
    l.move(5, TileCell.REQUESTED);
    l.move(5, TileCell.BLANK);
    assertEquals(0, l.violations);
  }

  @Test
  void illegalEdgesAreCounted() {
    Level l = new Level();
    l.move(0, TileCell.SHOWN);
    assertEquals(1, l.violations);
    assertTrue(l.firstViolation.contains("EMPTY -> SHOWN"));
    assertFalse(check(l, List.of(), List.of()).isEmpty());
  }

  @Test
  void shownCellNeedsAVisibleGpuMaximum() {
    Level l = new Level();
    l.move(3, TileCell.REQUESTED);
    l.requested[3] = 7;
    l.move(3, TileCell.SHOWN);
    l.resident[3] = 7;
    // The upload has not been flushed: the queue accounts for it.
    List<Upload> ups = new ArrayList<>();
    ups.add(new Upload(0, 3, 0, new byte[0], (short) 10));
    // residentCount is global state; only the GPU line is under test here.
    List<String> bad = check(l, ups, List.of());
    assertTrue(bad.stream().noneMatch(s -> s.contains("GPU maximum")), bad.toString());
    // Without the upload the GPU would still hide a SHOWN cell.
    assertTrue(check(l, List.of(), List.of()).stream().anyMatch(s -> s.contains("GPU maximum")));
  }

  @Test
  void uploadQueuedAfterHideLeavesStaleCellVisible() {
    Level l = new Level();
    l.move(3, TileCell.REQUESTED);
    l.requested[3] = 7;
    l.move(3, TileCell.SHOWN);
    l.resident[3] = 7;
    l.gpuMax[3] = 10;
    l.move(3, TileCell.STALE);
    // flush() applies hides first, so an old upload still queued would re-show the cell.
    List<Upload> ups = List.of(new Upload(0, 3, 0, new byte[0], (short) 10));
    List<int[]> hides = List.of(new int[] {0, 3});
    assertTrue(check(l, ups, hides).stream().anyMatch(s -> s.contains("STALE")));
  }
}
