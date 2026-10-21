package net.reversteam.nbtflattener.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.reversteam.nbtflattener.NbtFlattenerContext;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

@Mixin(NbtIo.class)
public class NbtIoMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "readCompressed(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;", at = @At("HEAD"), cancellable = true, remap = true)
    private static void onReadCompressed(InputStream is, CallbackInfoReturnable<CompoundTag> cir) {
        // Only intercepting level.dat (for now?)
        boolean isLevelDat = false;
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            if (el.getClassName().contains("LevelStorageSource")) {
                isLevelDat = true;
                break;
            }
        }
        if (!isLevelDat)
            return;

        try {
            // Buffer the stream (might read it multiple times)
            byte[] bytes = is.readAllBytes();

            try {
                // Attempt normal read
                cir.setReturnValue(NbtIo.readCompressed(new ByteArrayInputStream(bytes)));
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("depth > 512")) {
                    LOGGER.warn("[NBT Flattener] Detected depth > 512 in level.dat. Bypassing limit and applying smart deduplication...");

                    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                    DataInputStream dataInputStream = new DataInputStream(new GZIPInputStream(bais));

                    NbtFlattenerContext.setBypass(true);
                    try {

                        CompoundTag corruptedTag = NbtIo.read(dataInputStream, NbtAccounter.UNLIMITED);

                        Tag prunedTag = smartPruneSequences(corruptedTag);

                        // Return the cleaned tag to the game
                        cir.setReturnValue((CompoundTag) prunedTag);

                    } catch (RuntimeException e2) {
                        NbtFlattenerContext.setBypass(false);
                        throw e2;
                    }

                    NbtFlattenerContext.setBypass(false);

                } else {
                    throw e;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to buffer level.dat for NBT pruning", e);
        }
    }

    // Recursively traverses the NBT. When it finds a "minecraft:sequence",
    // it flattens any nested sequences inside it and removes exact duplicates.
    private static Tag smartPruneSequences(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            CompoundTag result = new CompoundTag();
            for (String key : compound.getAllKeys()) {
                result.put(key, smartPruneSequences(compound.get(key)));
            }

            // Target specifically nested sequences (terrablender/mcreator bug)
            if ("minecraft:sequence".equals(result.getString("type"))) {
                ListTag originalList = result.getList("sequence", Tag.TAG_COMPOUND);
                if (originalList.size() > 1) {
                    ListTag newList = new ListTag();
                    Set<String> seen = new HashSet<>();
                    flattenAndDeduplicate(originalList, newList, seen);

                    if (newList.size() < originalList.size()) {
                        int removed = originalList.size() - newList.size();
                        LOGGER.info("[NBT Flattener] Flattened and deduplicated a 'minecraft:sequence'! Removed {} redundant nested rules.", removed);
                    }
                    result.put("sequence", newList);
                }
            }
            return result;

        } else if (tag instanceof ListTag list) {
            ListTag result = new ListTag();
            for (Tag t : list) {
                result.add(smartPruneSequences(t));
            }
            return result;
        }
        return tag;
    }

    // Unwraps nested "minecraft:sequence" tags into a single flat list,
    // and ignores any tags that are exact duplicates of ones we've already seen.
    private static void flattenAndDeduplicate(ListTag list, ListTag target, Set<String> seen) {
        for (Tag t : list) {
            if (t instanceof CompoundTag c && "minecraft:sequence".equals(c.getString("type"))) {
                // If it's another sequence, recursively flatten it
                ListTag nestedList = c.getList("sequence", Tag.TAG_COMPOUND);
                flattenAndDeduplicate(nestedList, target, seen);
            } else {
                // Use SNBT string representation to detect exact duplicates
                String serialized = t.getAsString();
                if (seen.add(serialized)) {
                    target.add(t); // It's unique, keep it
                }
            }
        }
    }
}