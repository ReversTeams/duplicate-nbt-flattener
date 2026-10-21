package net.reversteam.nbtflattener.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.reversteam.nbtflattener.NbtFlattenerContext;

@Mixin(targets = "net.minecraft.nbt.ListTag$1")
public class ListTagTypeMixin {
    @ModifyConstant(method = "load", constant = @Constant(intValue = 512))
    private int increaseDepthLimit(int original) {
        return NbtFlattenerContext.shouldBypass() ? NbtFlattenerContext.EXPANDED_DEPTH : 512;
    }
}