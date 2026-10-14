package dev.macmv.look.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.macmv.look.EntityRefraction;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33C;

/**
 * Runs the vertex refraction of {@code entity_refract.glsl} on the GPU and checks it against the optics
 * the raycast shader uses: a vertex moved by {@code look_refract} must be on the camera ray that Snell's
 * law bends onto the vertex's real position. Same display requirements as {@link RenderTest}.
 */
class EntityRefractionTest {
  private static final float IOR = 1.33f;
  private static final float SURFACE = 62f;
  private static final int CAM_X = 10, CAM_Y = 64, CAM_Z = 20;
  private static final float FRAC_X = 0.5f, FRAC_Y = 0.25f, FRAC_Z = 0.5f;
  private static final float TIME = 37.3f;
  private static final float PIXEL_ANGLE = 0.002f;

  private static GlContext gl;
  private static int program;
  private static int vao;
  private static int inBuf;
  private static int outBuf;
  private static int waterTex;

  @BeforeAll
  static void setUp() {
    String[] why = new String[1];
    gl = GlContext.tryCreate(64, 64, why);
    if (gl == null && System.getenv("LOOK_REQUIRE_GL") != null) {
      throw new IllegalStateException("LOOK_REQUIRE_GL is set but there is no GL: " + why[0]);
    }
    assumeTrue(gl != null, () -> "no GL context: " + why[0]);

    String vs = "#version 330 core\n" + EntityRefraction.glsl()
        + "layout(location = 0) in vec3 e;\nout vec3 result;\nvoid main() { result = look_refract(e); }\n";
    int v = GL33C.glCreateShader(GL33C.GL_VERTEX_SHADER);
    GL33C.glShaderSource(v, vs);
    GL33C.glCompileShader(v);
    if (GL33C.glGetShaderi(v, GL33C.GL_COMPILE_STATUS) == 0) {
      throw new IllegalStateException("entity_refract.glsl: " + GL33C.glGetShaderInfoLog(v));
    }
    int fs = GL33C.glCreateShader(GL33C.GL_FRAGMENT_SHADER);
    GL33C.glShaderSource(fs, "#version 330 core\nvoid main() {}\n");
    GL33C.glCompileShader(fs);
    program = GL33C.glCreateProgram();
    GL33C.glAttachShader(program, v);
    GL33C.glAttachShader(program, fs);
    GL33C.glTransformFeedbackVaryings(program, new CharSequence[] {"result"}, GL33C.GL_INTERLEAVED_ATTRIBS);
    GL33C.glLinkProgram(program);
    if (GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == 0) {
      throw new IllegalStateException("link: " + GL33C.glGetProgramInfoLog(program));
    }

    // Flat water at SURFACE over the whole window, which is centered on the camera.
    int size = 128;
    FloatBuffer heights = BufferUtils.createFloatBuffer(size * size);
    for (int i = 0; i < size * size; i++) heights.put(SURFACE);
    heights.flip();
    waterTex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, waterTex);
    GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_R32F, size, size, 0, GL33C.GL_RED, GL33C.GL_FLOAT, heights);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);

    vao = GL33C.glGenVertexArrays();
    inBuf = GL33C.glGenBuffers();
    outBuf = GL33C.glGenBuffers();
  }

  @AfterAll
  static void tearDown() {
    if (gl != null) gl.close();
  }

  /** look_refract of each camera-relative position. */
  private static Vector3f[] refract(Vector3f[] in, float waves) {
    GL33C.glUseProgram(program);
    GL33C.glUniform3i(GL33C.glGetUniformLocation(program, "LookCamBlock"), CAM_X, CAM_Y, CAM_Z);
    GL33C.glUniform3f(GL33C.glGetUniformLocation(program, "LookCamFrac"), FRAC_X, FRAC_Y, FRAC_Z);
    GL33C.glUniform2i(GL33C.glGetUniformLocation(program, "LookWindow"), CAM_X - 64, CAM_Z - 64);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "LookWater"), 0);
    GL33C.glUniform1f(GL33C.glGetUniformLocation(program, "uWaterWaves"), waves);
    GL33C.glUniform1f(GL33C.glGetUniformLocation(program, "LookTime"), TIME);
    GL33C.glUniform1f(GL33C.glGetUniformLocation(program, "LookPixelAngle"), PIXEL_ANGLE);

    FloatBuffer data = BufferUtils.createFloatBuffer(in.length * 3);
    for (Vector3f p : in) data.put(p.x).put(p.y).put(p.z);
    data.flip();
    GL33C.glBindVertexArray(vao);
    GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, inBuf);
    GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, data, GL33C.GL_STATIC_DRAW);
    GL33C.glEnableVertexAttribArray(0);
    GL33C.glVertexAttribPointer(0, 3, GL33C.GL_FLOAT, false, 0, 0L);

    GL33C.glBindBuffer(GL33C.GL_TRANSFORM_FEEDBACK_BUFFER, outBuf);
    GL33C.glBufferData(GL33C.GL_TRANSFORM_FEEDBACK_BUFFER, (long) in.length * 12, GL33C.GL_STATIC_READ);
    GL33C.glBindBufferBase(GL33C.GL_TRANSFORM_FEEDBACK_BUFFER, 0, outBuf);

    GL33C.glEnable(GL33C.GL_RASTERIZER_DISCARD);
    GL33C.glBeginTransformFeedback(GL33C.GL_POINTS);
    GL33C.glDrawArrays(GL33C.GL_POINTS, 0, in.length);
    GL33C.glEndTransformFeedback();
    GL33C.glDisable(GL33C.GL_RASTERIZER_DISCARD);
    GL33C.glFinish();

    ByteBuffer raw = ByteBuffer.allocateDirect(in.length * 12).order(ByteOrder.nativeOrder());
    GL33C.glGetBufferSubData(GL33C.GL_TRANSFORM_FEEDBACK_BUFFER, 0, raw);
    Vector3f[] out = new Vector3f[in.length];
    for (int i = 0; i < in.length; i++) out[i] = new Vector3f(raw.getFloat(), raw.getFloat(), raw.getFloat());
    return out;
  }

  /** GLSL's refract(), for the reference. */
  private static Vector3f glslRefract(Vector3f i, Vector3f n, float eta) {
    float d = n.dot(i);
    float k = 1f - eta * eta * (1f - d * d);
    if (k < 0f) return new Vector3f();
    return new Vector3f(i).mul(eta).fma(-(eta * d + (float) Math.sqrt(k)), n);
  }

  /** The waves of water_waves.glsl, in Java. */
  private static float[] waveSlope(float px, float pz, float time, float foot, float scale) {
    float[][] k = {{3, 1}, {-2, 4}, {7, -3}, {-9, -8}, {14, 11}};
    float[] s = {0.050f, 0.045f, 0.040f, 0.030f, 0.025f};
    float[] speed = {1, 2, 3, 5, 6};
    float[] phase = {0.0f, 1.7f, 4.1f, 2.3f, 5.2f};
    float gx = 0, gz = 0;
    for (int i = 0; i < 5; i++) {
      float kx = k[i][0] * (float) (2 * Math.PI / 64), kz = k[i][1] * (float) (2 * Math.PI / 64);
      float kl = (float) Math.hypot(kx, kz);
      float t = Math.min(Math.max((foot * kl - 0.4f) / (1.5f - 0.4f), 0f), 1f);
      float fade = 1f - t * t * (3f - 2f * t);
      float ph = kx * px + kz * pz - speed[i] * (float) (2 * Math.PI / 128) * time + phase[i];
      float amp = s[i] * fade * (float) Math.cos(ph);
      gx += kx / kl * amp;
      gz += kz / kl * amp;
    }
    return new float[] {gx * scale, gz * scale};
  }

  /** Directions from the camera (unit, pointing down) over a spread of azimuths and depression angles. */
  private static Vector3f[] directions() {
    java.util.List<Vector3f> out = new java.util.ArrayList<>();
    for (int az = 0; az < 360; az += 23) {
      for (int dep = 12; dep <= 85; dep += 9) {
        double a = Math.toRadians(az), e = Math.toRadians(dep);
        out.add(new Vector3f((float) (Math.cos(e) * Math.cos(a)), (float) -Math.sin(e), (float) (Math.cos(e) * Math.sin(a))));
      }
    }
    return out.toArray(new Vector3f[0]);
  }

  /** Flat water: a vertex lands on the camera ray that Snell's law bends onto it, at the bent path's length. */
  @Test
  void flatSurfaceMatchesSnell() {
    float camY = CAM_Y + FRAC_Y;
    Vector3f up = new Vector3f(0, 1, 0);
    Vector3f[] dirs = directions();
    Vector3f[] verts = new Vector3f[dirs.length];
    float[] path = new float[dirs.length];
    for (int i = 0; i < dirs.length; i++) {
      Vector3f d = dirs[i];
      Vector3f p = new Vector3f(d).mul((SURFACE - camY) / d.y);
      Vector3f t = glslRefract(d, up, 1f / IOR);
      float depth = 4f + (i % 5) * 2f;   // 4 to 12 blocks under the surface
      Vector3f e = new Vector3f(p).fma(-depth / t.y, t);
      verts[i] = e;
      path[i] = p.length() + e.distance(p);
    }

    Vector3f[] got = refract(verts, 0f);
    for (int i = 0; i < dirs.length; i++) {
      Vector3f g = got[i];
      assertEquals(path[i], g.length(), 0.02f * path[i], "path length of " + dirs[i]);
      Vector3f dir = new Vector3f(g).normalize();
      assertTrue(dir.distance(dirs[i]) < 2e-3f, "direction of " + dirs[i] + " came out " + dir);
    }
  }

  /** With waves: the camera ray through the result, bent by the wave normal there, passes through the vertex. */
  @Test
  void wavySurfaceHitsTheVertex() {
    float camY = CAM_Y + FRAC_Y;
    // Vertices below the surface over a spread of directions, from the flat solution's inverse.
    Vector3f up = new Vector3f(0, 1, 0);
    Vector3f[] dirs = directions();
    Vector3f[] verts = new Vector3f[dirs.length];
    for (int i = 0; i < dirs.length; i++) {
      Vector3f d = dirs[i];
      Vector3f p = new Vector3f(d).mul((SURFACE - camY) / d.y);
      Vector3f t = glslRefract(d, up, 1f / IOR);
      verts[i] = new Vector3f(p).fma(-(3f + (i % 4) * 2f) / t.y, t);
    }

    Vector3f[] got = refract(verts, 1f);
    float worst = 0;
    for (int i = 0; i < dirs.length; i++) {
      Vector3f d = new Vector3f(got[i]).normalize();
      Vector3f p = new Vector3f(d).mul((SURFACE - camY) / d.y);
      float foot = p.length() * PIXEL_ANGLE / Math.max(Math.abs(d.y), 0.05f);
      float[] g = waveSlope((CAM_X & 4095) + FRAC_X + p.x, (CAM_Z & 4095) + FRAC_Z + p.z, TIME, foot, 1f);
      Vector3f n = new Vector3f(-g[0], 1, -g[1]).normalize();
      Vector3f t = glslRefract(d, n, 1f / IOR);
      Vector3f q = new Vector3f(p).fma((verts[i].y - p.y) / t.y, t);
      float miss = (float) Math.hypot(q.x - verts[i].x, q.z - verts[i].z);
      worst = Math.max(worst, miss);
    }
    assertTrue(worst < 0.03f, "the bent ray misses the vertex by " + worst + " blocks");
  }

  /** Every vanilla core vertex shader that gets patched still compiles, with its imports pasted in. */
  @Test
  void patchedVanillaShadersCompile() throws Exception {
    var jar = new java.util.zip.ZipFile(new java.io.File(
        net.minecraft.client.renderer.ShaderInstance.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    int patched = 0;
    for (var entries = jar.entries(); entries.hasMoreElements(); ) {
      String path = entries.nextElement().getName();
      if (!path.startsWith("assets/minecraft/shaders/core/") || !path.endsWith(".vsh")) continue;
      String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
      String source = new String(jar.getInputStream(jar.getEntry(path)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

      String out = EntityRefraction.patch(com.mojang.blaze3d.shaders.Program.Type.VERTEX, name, source);
      if (out.equals(source)) continue;
      patched++;
      assertTrue(out.contains("look_eye(ModelViewMat, "), name);
      assertTrue(!out.contains("ModelViewMat * vec4("), name + " still has an unpatched position");

      var imports = java.util.regex.Pattern.compile("(?m)^#moj_import <([^>]+)>").matcher(out);
      out = imports.replaceAll(m -> {
        try {
          var e = jar.getEntry("assets/minecraft/shaders/include/" + m.group(1));
          String text = new String(jar.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          return java.util.regex.Matcher.quoteReplacement(text.replaceAll("(?m)^#version.*$", ""));
        } catch (java.io.IOException ex) {
          throw new java.io.UncheckedIOException(ex);
        }
      });
      int sh = GL33C.glCreateShader(GL33C.GL_VERTEX_SHADER);
      GL33C.glShaderSource(sh, out);
      GL33C.glCompileShader(sh);
      assertTrue(GL33C.glGetShaderi(sh, GL33C.GL_COMPILE_STATUS) != 0, name + ": " + GL33C.glGetShaderInfoLog(sh));
      GL33C.glDeleteShader(sh);
    }
    jar.close();
    assertTrue(patched >= 30, "only " + patched + " vanilla shaders were patched");
  }

  /** Every vanilla core fragment shader that gets patched still compiles, with the vertex stage's outputs. */
  @Test
  void patchedVanillaFragmentShadersCompile() throws Exception {
    var jar = new java.util.zip.ZipFile(new java.io.File(
        net.minecraft.client.renderer.ShaderInstance.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    int patched = 0;
    for (var entries = jar.entries(); entries.hasMoreElements(); ) {
      String path = entries.nextElement().getName();
      if (!path.startsWith("assets/minecraft/shaders/core/") || !path.endsWith(".fsh")) continue;
      String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
      String source = new String(jar.getInputStream(jar.getEntry(path)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      String vsource = new String(jar.getInputStream(jar.getEntry(path.replace(".fsh", ".vsh"))).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

      String vs = EntityRefraction.patch(com.mojang.blaze3d.shaders.Program.Type.VERTEX, name, vsource);
      String fs = EntityRefraction.patch(com.mojang.blaze3d.shaders.Program.Type.FRAGMENT, name, source);
      if (fs.equals(source)) continue;
      patched++;
      assertTrue(fs.contains("look_shade"), name);

      int v = compileVanilla(jar, GL33C.GL_VERTEX_SHADER, vs, name);
      int f = compileVanilla(jar, GL33C.GL_FRAGMENT_SHADER, fs, name);
      int prog = GL33C.glCreateProgram();
      GL33C.glAttachShader(prog, v);
      GL33C.glAttachShader(prog, f);
      GL33C.glLinkProgram(prog);
      assertTrue(GL33C.glGetProgrami(prog, GL33C.GL_LINK_STATUS) != 0, name + ": " + GL33C.glGetProgramInfoLog(prog));
      GL33C.glDeleteProgram(prog);
    }
    jar.close();
    assertTrue(patched >= 15, "only " + patched + " vanilla fragment shaders were patched");
  }

  private static int compileVanilla(java.util.zip.ZipFile jar, int type, String source, String name) {
    var imports = java.util.regex.Pattern.compile("(?m)^#moj_import <([^>]+)>").matcher(source);
    source = imports.replaceAll(m -> {
      try {
        var e = jar.getEntry("assets/minecraft/shaders/include/" + m.group(1));
        String text = new String(jar.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return java.util.regex.Matcher.quoteReplacement(text.replaceAll("(?m)^#version.*$", ""));
      } catch (java.io.IOException ex) {
        throw new java.io.UncheckedIOException(ex);
      }
    });
    int sh = GL33C.glCreateShader(type);
    GL33C.glShaderSource(sh, source);
    GL33C.glCompileShader(sh);
    assertTrue(GL33C.glGetShaderi(sh, GL33C.GL_COMPILE_STATUS) != 0, name + ": " + GL33C.glGetShaderInfoLog(sh));
    return sh;
  }

  /** Above the surface, and with no water in the column, nothing moves. */
  @Test
  void untouchedOutsideTheWater() {
    Vector3f[] verts = {new Vector3f(3, 5, 2), new Vector3f(-4, -1, 6)};   // above the surface
    Vector3f[] got = refract(verts, 1f);
    for (int i = 0; i < verts.length; i++) assertTrue(got[i].distance(verts[i]) < 1e-5f, "moved " + verts[i]);
  }
}
