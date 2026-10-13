package dev.macmv.look;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.macmv.look.Materials.Material;
import org.junit.jupiter.api.Test;

class MaterialsTest {
  private static Material m(String name) {
    return Materials.classify(name, 0, false);
  }

  @Test
  void packRoundTrips() {
    Material in = new Material(0.5f, 0.25f, 2f, true, true, true, 3f / 7f, true, true);
    Material out = Materials.unpack(Materials.pack(in));
    assertEquals(in.roughness(), out.roughness(), 1 / 255f);
    assertEquals(in.f0(), out.f0(), 1 / 255f);
    assertEquals(in.emission(), out.emission(), Materials.EMISSION_MAX / 255f);
    assertEquals(in.wetness(), out.wetness(), 1e-4f);
    assertTrue(out.metal() && out.subsurface() && out.flicker() && out.texelEmission() && out.texelSmoothness());
    assertFalse(Materials.unpack(Materials.pack(Materials.DEFAULT)).metal());
  }

  @Test
  void metals() {
    for (String n : new String[] {"iron_block", "gold_block", "copper_block", "cut_copper", "weathered_copper", "netherite_block", "anvil"}) {
      assertTrue(m(n).metal(), n);
      assertTrue(m(n).roughness() < 0.7f, n);
    }
    assertTrue(m("gold_block").roughness() < m("oxidized_copper").roughness());
    assertFalse(m("iron_ore").metal());
    assertFalse(m("raw_iron_block").metal());
    assertFalse(m("sea_lantern").metal());
    assertFalse(m("jack_o_lantern").metal());
  }

  @Test
  void smoothMaterials() {
    for (String n : new String[] {"glass", "red_stained_glass", "ice", "blue_ice", "diamond_block", "obsidian", "polished_andesite", "quartz_block"}) {
      assertTrue(m(n).roughness() < 0.4f, n);
    }
    assertEquals(0f, m("glass").wetness());
    assertTrue(m("stone").roughness() > 0.8f);
  }

  @Test
  void foliageIsSubsurface() {
    assertTrue(m("oak_leaves").subsurface());
    assertTrue(m("white_wool").subsurface());
    assertTrue(Materials.classify("short_grass", 0, true).subsurface());
    assertTrue(Materials.classify("poppy", 0, true).subsurface());
    assertFalse(m("grass_block").subsurface());
    assertFalse(m("stone").subsurface());
    assertFalse(m("bedrock").subsurface());
  }

  @Test
  void orePartlyGlows() {
    Material ore = m("diamond_ore");
    assertTrue(ore.texelSmoothness());
    assertEquals(0f, ore.emission());
    Material red = m("redstone_ore");
    assertTrue(red.emission() > 0f && red.texelEmission());
    assertTrue(m("deepslate_redstone_ore").emission() > 0f);
  }

  @Test
  void glowingBlocks() {
    for (String n : new String[] {"glowstone", "sea_lantern", "shroomlight", "ochre_froglight", "pearlescent_froglight", "verdant_froglight", "lava", "magma_block"}) {
      assertTrue(Materials.classify(n, 15, false).emission() >= 1f, n);
    }
    assertTrue(Materials.classify("glowstone", 15, false).emission() > Materials.classify("sea_lantern", 15, false).emission() * 0.9f);
    assertFalse(Materials.classify("glowstone", 15, false).texelEmission());
  }

  @Test
  void flamesFlickerAndGlowByTexel() {
    for (String n : new String[] {"torch", "wall_torch", "soul_torch", "campfire", "lantern", "soul_lantern", "candle"}) {
      Material mat = Materials.classify(n, 14, false);
      assertTrue(mat.emission() > 0f, n);
      assertTrue(mat.texelEmission(), n);
      assertTrue(mat.flicker(), n);
    }
    assertFalse(Materials.classify("end_rod", 14, false).flicker());
    assertTrue(Materials.classify("end_rod", 14, false).emission() > 0f);
  }

  @Test
  void unlitStatesDoNotGlow() {
    assertEquals(0f, Materials.classify("redstone_lamp", 0, false).emission());
    assertEquals(0f, Materials.classify("candle", 0, false).emission());
    assertEquals(0f, Materials.classify("campfire", 0, false).emission());
    assertEquals(0f, m("stone").emission());
    assertEquals(0f, m("oak_planks").emission());
  }

  @Test
  void unknownLightSourcesGlow() {
    Material mat = Materials.classify("some_modded_lamp", 12, false);
    assertTrue(mat.emission() > 0f);
    assertTrue(mat.texelEmission());
  }
}
