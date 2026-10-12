//! Runs terrain sampling on a pool of worker threads and keeps the finished
//! tiles.
//!
//! The Java side asks for tiles (one per section column of a pyramid level),
//! gets told which ones finished, and then fetches individual bricks out of
//! them. A tile is tiny (a height and a surface kind per column), so bricks are
//! built on demand instead of being stored.
//!
//! Work is handed out nearest-first, re-sorted as the camera moves, and dropped
//! when it falls out of range, so a long flight does not leave the workers busy
//! with terrain nobody can see.

use std::{
  collections::{BinaryHeap, HashMap, HashSet},
  panic::{catch_unwind, AssertUnwindSafe},
  sync::{
    atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering},
    Arc, Condvar, Mutex, RwLock,
  },
  thread::JoinHandle,
  time::{Duration, Instant},
};

use crate::terrain::{Ids, Terrain, Tile, BRICK_VOXELS, HF_BYTES};

/// Keys pack a tile like `SectionKey.java` does: level in bits 60..62, x in
/// 22..43, z in 0..21.
pub fn pack_key(level: u32, sx: i32, sz: i32) -> u64 {
  ((level as u64) << 60) | ((sx as u64 & 0x3F_FFFF) << 22) | (sz as u64 & 0x3F_FFFF)
}

/// Set on the keys of tiles that feed the far heightfield instead of voxel
/// bricks. Such a tile is handed over once (see `Engine::take_heightfield`) and
/// then forgotten.
pub const HF_FLAG: u64 = 1 << 63;

/// Sections (of the tile's own level) a heightfield tile may be from the camera
/// before it is dropped. The heightfield window is 128 tiles wide, so a little
/// over half of that.
const HF_WANT_SECTIONS: i64 = 68;

pub fn unpack_key(key: u64) -> (u32, i32, i32) {
  let level = ((key >> 60) & 7) as u32;
  let sx = ((key << 20) as i64 >> 42) as i32;
  let sz = ((key << 42) as i64 >> 42) as i32;
  (level, sx, sz)
}

/// Block distance (Chebyshev) from the camera to the center of a tile.
fn distance(key: u64, cam_x: i32, cam_z: i32) -> i64 {
  let (level, sx, sz) = unpack_key(key);
  let cx = ((sx as i64) << (level + 4)) + (8i64 << level);
  let cz = ((sz as i64) << (level + 4)) + (8i64 << level);
  (cx - cam_x as i64).abs().max((cz - cam_z as i64).abs())
}

struct Job {
  key:      u64,
  /// Distance from the camera when queued or last re-sorted; smaller goes
  /// first.
  priority: i64,
}

impl PartialEq for Job {
  fn eq(&self, other: &Self) -> bool { self.priority == other.priority && self.key == other.key }
}
impl Eq for Job {}
impl PartialOrd for Job {
  fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> { Some(self.cmp(other)) }
}
impl Ord for Job {
  fn cmp(&self, other: &Self) -> std::cmp::Ordering {
    // BinaryHeap is a max-heap and we want the nearest tile first.
    other.priority.cmp(&self.priority).then_with(|| other.key.cmp(&self.key))
  }
}

#[derive(Default)]
struct Queue {
  heap:    BinaryHeap<Job>,
  /// Keys that are queued or being computed, so a tile is never requested
  /// twice.
  pending: HashSet<u64>,
}

struct Shared {
  terrain:       Terrain,
  /// How far a tile may be from the camera, in sections of its own level,
  /// before it is dropped.
  want_sections: i64,

  queue: Mutex<Queue>,
  wake:  Condvar,
  tiles: Mutex<HashMap<u64, Arc<Tile>>>,
  done:  Mutex<Vec<u64>>,
  ids:   RwLock<Ids>,

  cam_x:     AtomicI32,
  cam_z:     AtomicI32,
  shutdown:  AtomicBool,
  completed: AtomicU64,
}

impl Shared {
  fn wanted(&self, key: u64, extra_sections: i64) -> bool {
    let (level, _, _) = unpack_key(key);
    let want = if key & HF_FLAG != 0 { HF_WANT_SECTIONS } else { self.want_sections };
    let limit = (want + extra_sections) * (16i64 << level);
    distance(key, self.cam_x.load(Ordering::Relaxed), self.cam_z.load(Ordering::Relaxed)) <= limit
  }
}

pub struct Engine {
  shared:     Arc<Shared>,
  workers:    Vec<JoinHandle<()>>,
  last_sort:  Mutex<(i32, i32)>,
  last_evict: Mutex<Instant>,
}

/// Sections past `want_sections` that a tile is still kept for, so walking back
/// and forth over a border does not recompute it.
const KEEP_MARGIN: i64 = 8;
const SORT_DISTANCE: i64 = 32;
const EVICT_INTERVAL: Duration = Duration::from_secs(2);

impl Engine {
  pub fn new(terrain: Terrain, want_sections: i32, threads: usize) -> Engine {
    let shared = Arc::new(Shared {
      terrain,
      want_sections: want_sections as i64,
      queue: Mutex::new(Queue::default()),
      wake: Condvar::new(),
      tiles: Mutex::new(HashMap::new()),
      done: Mutex::new(Vec::new()),
      ids: RwLock::new(Ids::default()),
      cam_x: AtomicI32::new(0),
      cam_z: AtomicI32::new(0),
      shutdown: AtomicBool::new(false),
      completed: AtomicU64::new(0),
    });

    let workers = (0..threads.max(1))
      .map(|i| {
        let shared = shared.clone();
        std::thread::Builder::new()
          .name(format!("look-terrain-{i}"))
          .spawn(move || worker(shared))
          .expect("failed to spawn terrain worker")
      })
      .collect();

    Engine {
      shared,
      workers,
      last_sort: Mutex::new((0, 0)),
      last_evict: Mutex::new(Instant::now()),
    }
  }

  pub fn set_ids(&self, ids: Ids) { *self.shared.ids.write().unwrap() = ids; }

  /// Queues a tile unless it exists or is already queued. Returns true if it
  /// was queued now.
  pub fn request(&self, level: u32, sx: i32, sz: i32) -> bool {
    self.request_key(pack_key(level, sx, sz))
  }

  /// Like `request`, for a tile of the far heightfield: the key it finishes
  /// under has `HF_FLAG` set.
  pub fn request_heightfield(&self, level: u32, sx: i32, sz: i32) -> bool {
    self.request_key(pack_key(level, sx, sz) | HF_FLAG)
  }

  fn request_key(&self, key: u64) -> bool {
    if self.shared.tiles.lock().unwrap().contains_key(&key) {
      return false;
    }

    let priority = distance(
      key,
      self.shared.cam_x.load(Ordering::Relaxed),
      self.shared.cam_z.load(Ordering::Relaxed),
    );
    let mut q = self.shared.queue.lock().unwrap();
    if !q.pending.insert(key) {
      return false;
    }
    q.heap.push(Job { key, priority });
    drop(q);
    self.shared.wake.notify_one();
    true
  }

  /// Tells the engine where the camera is: re-sorts the queue nearest-first
  /// when it moved enough, drops queued tiles that are now out of range, and
  /// forgets finished tiles far behind.
  pub fn set_camera(&self, x: i32, z: i32) {
    let s = &self.shared;
    s.cam_x.store(x, Ordering::Relaxed);
    s.cam_z.store(z, Ordering::Relaxed);

    {
      let mut last = self.last_sort.lock().unwrap();
      if (last.0 as i64 - x as i64).abs().max((last.1 as i64 - z as i64).abs()) >= SORT_DISTANCE {
        *last = (x, z);
        let mut q = s.queue.lock().unwrap();
        let jobs = std::mem::take(&mut q.heap).into_vec();
        for job in jobs {
          if s.wanted(job.key, 0) {
            q.heap.push(Job { key: job.key, priority: distance(job.key, x, z) });
          } else {
            q.pending.remove(&job.key);
          }
        }
      }
    }

    let mut last = self.last_evict.lock().unwrap();
    if last.elapsed() >= EVICT_INTERVAL {
      *last = Instant::now();
      s.tiles.lock().unwrap().retain(|&k, _| s.wanted(k, KEEP_MARGIN));
    }
  }

  /// Moves the keys of tiles finished since the last call into `out`; returns
  /// how many.
  pub fn poll(&self, out: &mut [u64]) -> usize {
    let mut done = self.shared.done.lock().unwrap();
    let n = done.len().min(out.len());
    out[..n].copy_from_slice(&done[..n]);
    done.drain(..n);
    n
  }

  /// Builds one brick of a finished tile. Returns None if the tile is not
  /// ready.
  pub fn fetch(
    &self,
    level: u32,
    sx: i32,
    sy: i32,
    sz: i32,
    out: &mut [u16; BRICK_VOXELS],
  ) -> Option<u32> {
    let tile = self.shared.tiles.lock().unwrap().get(&pack_key(level, sx, sz)).cloned()?;
    let ids = *self.shared.ids.read().unwrap();
    Some(tile.fill_brick(level, sy, &self.shared.terrain.params, &ids, out))
  }

  /// Writes a finished tile's grass colors as far tint texels, returning the
  /// texels per side.
  pub fn far_tint(&self, level: u32, sx: i32, sz: i32, out: &mut [u8]) -> Option<usize> {
    let tile = self.shared.tiles.lock().unwrap().get(&pack_key(level, sx, sz)).cloned()?;
    Some(tile.write_far_tint(level, out))
  }

  /// Writes a finished heightfield tile as texels (see
  /// `Tile::write_heightfield`) and forgets it, so the far field costs no
  /// memory here once the GPU has it. False if the tile is not finished.
  pub fn take_heightfield(&self, level: u32, sx: i32, sz: i32, out: &mut [u8; HF_BYTES]) -> bool {
    let key = pack_key(level, sx, sz) | HF_FLAG;
    let Some(tile) = self.shared.tiles.lock().unwrap().remove(&key) else { return false };
    tile.write_heightfield(self.shared.terrain.params.sea_level, out);
    true
  }

  /// (queued or running, finished tiles kept, tiles computed since start)
  pub fn stats(&self) -> (usize, usize, u64) {
    let pending = self.shared.queue.lock().unwrap().pending.len();
    let tiles = self.shared.tiles.lock().unwrap().len();
    (pending, tiles, self.shared.completed.load(Ordering::Relaxed))
  }
}

impl Drop for Engine {
  fn drop(&mut self) {
    self.shared.shutdown.store(true, Ordering::SeqCst);
    // Take the lock so no worker is between checking the flag and waiting.
    drop(self.shared.queue.lock().unwrap());
    self.shared.wake.notify_all();
    for w in self.workers.drain(..) {
      let _ = w.join();
    }
  }
}

fn worker(shared: Arc<Shared>) {
  let mut scratch = shared.terrain.scratch();
  loop {
    let job = {
      let mut q = shared.queue.lock().unwrap();
      loop {
        if shared.shutdown.load(Ordering::SeqCst) {
          return;
        }
        if let Some(job) = q.heap.pop() {
          break job;
        }
        q = shared.wake.wait(q).unwrap();
      }
    };

    // Out of range by now: skip it. If it comes back into range the Java side
    // asks again.
    if !shared.wanted(job.key, 0) {
      shared.queue.lock().unwrap().pending.remove(&job.key);
      continue;
    }

    let (level, sx, sz) = unpack_key(job.key);
    let tile =
      catch_unwind(AssertUnwindSafe(|| shared.terrain.sample_tile(&mut scratch, level, sx, sz)))
        .unwrap_or_else(|_| {
          // The memo may be half updated; start clean.
          scratch = shared.terrain.scratch();
          Tile::empty()
        });

    shared.tiles.lock().unwrap().insert(job.key, Arc::new(tile));
    shared.completed.fetch_add(1, Ordering::Relaxed);
    shared.done.lock().unwrap().push(job.key);
    shared.queue.lock().unwrap().pending.remove(&job.key);
  }
}
