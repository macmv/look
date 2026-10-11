#version 330 core

// uView / uProj are vanilla's matrices for this frame. Everything is camera-relative, so the ray
// origin comes out near zero and uCamPos is only needed to get back to absolute world coordinates.
uniform mat4 uInvViewProj;
uniform mat4 uView;
uniform mat4 uProj;
uniform vec3 uCamPos;

in vec2 vNdc;
out vec4 fragColor;

void main() {
  vec4 n = uInvViewProj * vec4(vNdc, -1.0, 1.0);
  vec4 f = uInvViewProj * vec4(vNdc, 1.0, 1.0);
  vec3 origin = n.xyz / n.w;
  vec3 dir = normalize(f.xyz / f.w - origin);

  // TODO: replace this debug plane with the voxel traversal.
  float planeY = 64.0 - uCamPos.y;
  if (abs(dir.y) < 1e-6) discard;

  float t = (planeY - origin.y) / dir.y;
  if (t < 0.0) discard;

  vec3 hit = origin + dir * t;

  // Same depth vanilla would have written for this point.
  vec4 clip = uProj * (uView * vec4(hit, 1.0));
  float depth = clip.z / clip.w * 0.5 + 0.5;
  if (depth > 1.0) discard;
  gl_FragDepth = depth;

  vec3 world = hit + uCamPos;
  float checker = mod(floor(world.x) + floor(world.z), 2.0);
  fragColor = vec4(mix(vec3(0.30, 0.60, 0.30), vec3(0.20, 0.45, 0.20), checker), 1.0);
}
