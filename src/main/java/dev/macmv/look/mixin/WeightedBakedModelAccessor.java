package dev.macmv.look.mixin;

import java.util.List;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.util.random.WeightedEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the weighted variants of a blockstate model, so each one can be baked on its own. */
@Mixin(WeightedBakedModel.class)
public interface WeightedBakedModelAccessor {
  @Accessor("list")
  List<WeightedEntry.Wrapper<BakedModel>> look$variants();
}
