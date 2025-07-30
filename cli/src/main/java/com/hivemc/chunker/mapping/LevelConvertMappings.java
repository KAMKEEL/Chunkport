package com.hivemc.chunker.mapping;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores legacy ID mappings loaded from a {@code level.dat} file for use during
 * conversion. The mappings relate a namespaced identifier to its numeric ID.
 */
public final class LevelConvertMappings {
    private static final Map<String, Integer> LEGACY_IDS = new HashMap<>();
    private static final Map<Integer, String> LEGACY_IDENTIFIERS = new HashMap<>();

    private LevelConvertMappings() {
    }

    /**
     * Load legacy ID mappings from the supplied level.dat.
     *
     * @param levelDat the level.dat to read, may be null.
     * @throws IOException if reading the file fails.
     */
    public static void load(File levelDat) throws IOException {
        LEGACY_IDS.clear();
        LEGACY_IDENTIFIERS.clear();
        if (levelDat != null) {
            Map<String, Integer> loaded = readLegacyIDs(levelDat);
            LEGACY_IDS.putAll(loaded);
            for (Map.Entry<String, Integer> e : loaded.entrySet()) {
                LEGACY_IDENTIFIERS.put(e.getValue(), e.getKey());
            }
        }
    }

    /**
     * Get the number of legacy ID mappings loaded.
     *
     * @return the count of mappings.
     */
    public static int size() {
        return LEGACY_IDS.size();
    }

    /**
     * Get the numeric ID for a namespaced identifier.
     *
     * @param identifier the identifier to resolve.
     * @return the numeric ID or null if not present.
     */
    public static Integer getLegacyId(String identifier) {
        return LEGACY_IDS.get(identifier);
    }

    /**
     * Check whether any mappings are loaded.
     *
     * @return true if mappings have been loaded.
     */
    public static boolean isLoaded() {
        return !LEGACY_IDS.isEmpty();
    }

    /**
     * Get the namespaced identifier for a numeric ID.
     *
     * @param id the numeric ID.
     * @return the identifier or null if not present.
     */
    public static String getLegacyIdentifier(int id) {
        return LEGACY_IDENTIFIERS.get(id);
    }

    private static Map<String, Integer> readLegacyIDs(File levelDat) throws IOException {
        Map<String, Integer> map = new HashMap<>();

        // 1) raw root, no "Data" unwrapping
        CompoundTag root = Tag.readRawJavaNBT(levelDat);
        if (root == null) return map;

        // 2) find FML/Forge
        CompoundTag meta = root.contains("FML") ? root.getCompound("FML")
                : root.contains("Forge") ? root.getCompound("Forge")
                : null;
        if (meta == null) return map;

        // 3) grab the raw ItemData tag (could be list or compound)
        Tag<?> itemTag = meta.get("ItemData");
        if (itemTag == null) itemTag = meta.get("Item Data");
        if (itemTag == null) {
            throw new IOException("Missing ItemData in level.dat");
        }

        // CASE 1: top-level list of compounds
        if (itemTag instanceof ListTag<?,?> listRaw) {
            for (Tag<?> elem : listRaw) {
                if (elem instanceof CompoundTag entry) {
                    String key = entry.getString("K", null);
                    int    val = entry.getInt   ("V", -1);
                    if (key != null && val >= 0) {
                        String cleaned = key.trim().replace("\u0002", "").replace("\u0003", "").replace("\u0001", "");
                        map.put(cleaned, val);
                    }
                }
            }

        // CASE 2 & 3: a compound under "ItemData"
        } else if (itemTag instanceof CompoundTag itemData) {
            // first see if it has its own nested list
            Tag<?> nested = itemData.get("ItemData");
            if (nested instanceof ListTag<?,?> nestedRaw) {
                for (Tag<?> elem : nestedRaw) {
                    if (elem instanceof CompoundTag entry) {
                        String key = entry.getString("K", null);
                        int    val = entry.getInt   ("V", -1);
                        if (key != null && val >= 0) {
                            String cleaned = key.trim().replace("\u0002", "").replace("\u0003", "").replace("\u0001", "");
                            map.put(cleaned, val);
                        }
                    }
                }
            } else {
                // fallback: any IntTag directly under itemData is name→id
                for (Map.Entry<String, Tag<?>> e : itemData.getValue().entrySet()) {
                    if (e.getValue() instanceof IntTag it) {
                        String cleaned = e.getKey().replace("\u0002", "").replace("\u0003", "").replace("\u0001", "");
                        map.put(cleaned, it.getValue());
                    }
                }
            }

        } else {
            throw new IOException(
                    "Unexpected ItemData tag type: " + itemTag.getClass().getSimpleName()
            );
        }

        return map;
    }
}
