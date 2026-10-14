package dev.macmv.look.mixin;

import com.mojang.blaze3d.shaders.Program;
import dev.macmv.look.EntityRefraction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Program.class)
public abstract class ProgramMixin {
  /**
   * The source of each core shader, as read from the resource pack and before the preprocessor and the
   * compiler see it. {@link EntityRefraction#patch} moves the vertices of the ones that draw entities,
   * particles and the like through the water.
   */
  @ModifyVariable(
      method = "compileShaderInternal",
      at = @At(
          value = "INVOKE_ASSIGN",
          target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;",
          remap = false),
      index = 5)
  private static String look$patchSource(String source, Program.Type type, String name) {
    return EntityRefraction.patch(type, name, source);
  }
}
