//! JNI entry points for the look mod. Keep this layer thin: marshal arguments,
//! call into the pure modules, and never let a panic cross the JNI boundary.

mod brick;

pub mod biome;
pub mod density;
pub mod engine;
pub mod noise;
pub mod rng;
pub mod terrain;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::{
  objects::{JByteArray, JByteBuffer, JClass, JIntArray, JLongArray},
  sys::{jboolean, jint, jlong, jstring},
  JNIEnv,
};

/// Runs `f`, converting any panic or error into a Java `RuntimeException`.
fn guard<T: Default>(env: &mut JNIEnv, f: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> T {
  match catch_unwind(AssertUnwindSafe(|| f(env))) {
    Ok(Ok(v)) => v,
    Ok(Err(msg)) => {
      let _ = env.throw_new("java/lang/RuntimeException", msg);
      T::default()
    }
    Err(_) => {
      let _ = env.throw_new("java/lang/RuntimeException", "native panic in look_native");
      T::default()
    }
  }
}

#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_version<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
) -> jstring {
  guard(&mut env, |env| {
    env
      .new_string(concat!("look_native ", env!("CARGO_PKG_VERSION")))
      .map(|s| s.into_raw())
      .map_err(|e| e.to_string())
  })
}

/// Reads a 16^3 brick of u16 block ids (native endian, direct buffer) and
/// writes 64 u64 occupancy masks (direct buffer, native endian).
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_buildOccupancy<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  ids: JByteBuffer<'l>,
  out: JByteBuffer<'l>,
) {
  guard(&mut env, |env| {
    let ids_len = env.get_direct_buffer_capacity(&ids).map_err(|e| e.to_string())?;
    let out_len = env.get_direct_buffer_capacity(&out).map_err(|e| e.to_string())?;
    if ids_len < brick::BRICK_VOLUME * 2 {
      return Err(format!("ids buffer too small: {ids_len}"));
    }
    if out_len < brick::CELL_COUNT * 8 {
      return Err(format!("out buffer too small: {out_len}"));
    }

    let ids_ptr = env.get_direct_buffer_address(&ids).map_err(|e| e.to_string())?;
    let out_ptr = env.get_direct_buffer_address(&out).map_err(|e| e.to_string())?;
    if ids_ptr as usize % std::mem::align_of::<u16>() != 0
      || out_ptr as usize % std::mem::align_of::<u64>() != 0
    {
      return Err("direct buffers must be aligned (use allocateDirect)".into());
    }

    // SAFETY: sizes and alignment were checked above, and Java owns both
    // buffers for the duration of this call. The two buffers are distinct
    // allocations.
    let ids = unsafe { &*(ids_ptr as *const [u16; brick::BRICK_VOLUME]) };
    let out = unsafe { &mut *(out_ptr as *mut [u64; brick::CELL_COUNT]) };

    brick::build_occupancy(ids, out);
    Ok(())
  })
}

// --- Predicted terrain
// -------------------------------------------------------------------------

use engine::Engine;
use terrain::{Ids, Terrain, BRICK_VOXELS};

/// Borrows the engine behind a handle from `terrainCreate`. Java must not use a
/// handle after `terrainDestroy`, and only calls in from the render thread.
fn engine<'a>(handle: jlong) -> Result<&'a Engine, String> {
  if handle == 0 {
    return Err("terrain engine handle is null".into());
  }
  // SAFETY: non-null handles come from Box::into_raw in terrainCreate and live
  // until terrainDestroy.
  Ok(unsafe { &*(handle as *const Engine) })
}

/// Direct buffer contents as a typed pointer, checking size and alignment.
fn buffer_ptr<T>(
  env: &JNIEnv,
  buf: &JByteBuffer,
  min_bytes: usize,
) -> Result<(*mut T, usize), String> {
  let cap = env.get_direct_buffer_capacity(buf).map_err(|e| e.to_string())?;
  if cap < min_bytes {
    return Err(format!("buffer too small: {cap} < {min_bytes}"));
  }
  let ptr = env.get_direct_buffer_address(buf).map_err(|e| e.to_string())?;
  if ptr as usize % std::mem::align_of::<T>() != 0 {
    return Err("direct buffers must be aligned (use allocateDirect)".into());
  }
  Ok((ptr as *mut T, cap))
}

/// Builds the generator from the exported world config and starts its worker
/// threads (`threads <= 0` picks a default that leaves two cores for the game).
/// Returns a handle.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainCreate<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  seed: jlong,
  config: JByteArray<'l>,
  want_sections: jint,
  threads: jint,
) -> jlong {
  guard(&mut env, |env| {
    let config = env.convert_byte_array(&config).map_err(|e| e.to_string())?;
    let terrain = Terrain::from_config(seed, &config)?;
    let threads = if threads > 0 {
      threads as usize
    } else {
      std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).saturating_sub(2).max(1)
    };
    Ok(Box::into_raw(Box::new(Engine::new(terrain, want_sections, threads))) as jlong)
  })
}

/// Stops the workers and frees the engine.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainDestroy<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
) {
  guard(&mut env, |_| {
    if handle != 0 {
      // SAFETY: see `engine`; this is the one place the box is reclaimed.
      drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
    Ok(())
  })
}

/// Sets the voxel ids bricks are written with: per surface kind the top and
/// filler id, then stone and the water stand-in (`terrain::Ids::LEN` values).
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainSetIds<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  ids: JIntArray<'l>,
) {
  guard(&mut env, |env| {
    let len = env.get_array_length(&ids).map_err(|e| e.to_string())? as usize;
    let mut buf = vec![0i32; len];
    env.get_int_array_region(&ids, 0, &mut buf).map_err(|e| e.to_string())?;
    let ids =
      Ids::from_slice(&buf).ok_or_else(|| format!("expected {} ids, got {len}", Ids::LEN))?;
    engine(handle)?.set_ids(ids);
    Ok(())
  })
}

/// Tells the engine where the camera is, in blocks.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainCamera<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  x: jint,
  z: jint,
) {
  guard(&mut env, |_| {
    engine(handle)?.set_camera(x, z);
    Ok(())
  })
}

/// Queues a tile (a section column of a level). Returns true if it was newly
/// queued.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainRequest<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  level: jint,
  sx: jint,
  sz: jint,
) -> jboolean {
  guard(&mut env, |_| Ok(engine(handle)?.request(level as u32, sx, sz) as jboolean))
}

/// Queues a tile of the far heightfield. Its key comes back from `terrainPoll`
/// with the sign bit set. Returns true if it was newly queued.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainHeightfieldRequest<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  level: jint,
  sx: jint,
  sz: jint,
) -> jboolean {
  guard(&mut env, |_| Ok(engine(handle)?.request_heightfield(level as u32, sx, sz) as jboolean))
}

/// Takes a finished heightfield tile: writes `terrain::HF_BYTES` bytes
/// (heights, then RGBA) into `out` and forgets the tile. Returns whether the
/// tile was ready.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainHeightfieldTake<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  level: jint,
  sx: jint,
  sz: jint,
  out: JByteBuffer<'l>,
) -> jboolean {
  guard(&mut env, |env| {
    let (ptr, _) = buffer_ptr::<[u8; terrain::HF_BYTES]>(env, &out, terrain::HF_BYTES)?;
    // SAFETY: as in terrainPoll.
    let out = unsafe { &mut *ptr };
    Ok(engine(handle)?.take_heightfield(level as u32, sx, sz, out) as jboolean)
  })
}

/// Writes the keys of tiles finished since the last call into `out` (u64 each,
/// native order) and returns how many.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainPoll<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  out: JByteBuffer<'l>,
) -> jint {
  guard(&mut env, |env| {
    let (ptr, cap) = buffer_ptr::<u64>(env, &out, 8)?;
    // SAFETY: size and alignment were checked, and Java owns the buffer for the
    // duration of the call.
    let out = unsafe { std::slice::from_raw_parts_mut(ptr, cap / 8) };
    Ok(engine(handle)?.poll(out) as jint)
  })
}

/// Builds one brick (4096 u16 voxel ids, native order) from a finished tile.
/// Returns the number of non-air voxels, or -1 if the tile is not ready.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainFetch<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  level: jint,
  sx: jint,
  sy: jint,
  sz: jint,
  out: JByteBuffer<'l>,
) -> jint {
  guard(&mut env, |env| {
    let (ptr, _) = buffer_ptr::<[u16; BRICK_VOXELS]>(env, &out, BRICK_VOXELS * 2)?;
    // SAFETY: as in terrainPoll.
    let out = unsafe { &mut *ptr };
    Ok(engine(handle)?.fetch(level as u32, sx, sy, sz, out).map_or(-1, |n| n as jint))
  })
}

/// Writes the grass colors of a finished tile as RGBA texels (row major, at
/// most 8 x 8) and returns the texels per side, or -1 if the tile is not ready.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainFarTint<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  level: jint,
  sx: jint,
  sz: jint,
  out: JByteBuffer<'l>,
) -> jint {
  guard(&mut env, |env| {
    let (ptr, _) = buffer_ptr::<[u8; 256]>(env, &out, 256)?;
    // SAFETY: as in terrainPoll.
    let out = unsafe { &mut *ptr };
    Ok(engine(handle)?.far_tint(level as u32, sx, sz, out).map_or(-1, |n| n as jint))
  })
}

/// Fills `out` with `{queued or running tiles, finished tiles kept, tiles
/// computed so far}`.
#[no_mangle]
pub extern "system" fn Java_dev_macmv_look_NativeLib_terrainStats<'l>(
  mut env: JNIEnv<'l>,
  _class: JClass<'l>,
  handle: jlong,
  out: JLongArray<'l>,
) {
  guard(&mut env, |env| {
    let (pending, tiles, completed) = engine(handle)?.stats();
    env
      .set_long_array_region(&out, 0, &[pending as jlong, tiles as jlong, completed as jlong])
      .map_err(|e| e.to_string())
  })
}
