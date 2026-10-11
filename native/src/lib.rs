//! JNI entry points for the look mod. Keep this layer thin: marshal arguments, call into the
//! pure modules, and never let a panic cross the JNI boundary.

mod brick;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteBuffer, JClass};
use jni::sys::jstring;
use jni::JNIEnv;

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

/// Reads a 16^3 brick of u16 block ids (native endian, direct buffer) and writes 64 u64
/// occupancy masks (direct buffer, native endian).
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

    // SAFETY: sizes and alignment were checked above, and Java owns both buffers for the duration
    // of this call. The two buffers are distinct allocations.
    let ids = unsafe { &*(ids_ptr as *const [u16; brick::BRICK_VOLUME]) };
    let out = unsafe { &mut *(out_ptr as *mut [u64; brick::CELL_COUNT]) };

    brick::build_occupancy(ids, out);
    Ok(())
  })
}
