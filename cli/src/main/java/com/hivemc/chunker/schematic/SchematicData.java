package com.hivemc.chunker.schematic;

import org.jetbrains.annotations.Nullable;

/**
 * Represents the contents of a schematic file using legacy block IDs and data
 * values compatible with WorldEdit for Minecraft 1.7.10.
 */
public final class SchematicData {
    private final short width;
    private final short height;
    private final short length;
    private final int[] blockIds;
    private final int[] blockData;
    /** WEOffset (minimum point relative to the copy origin), null when absent. */
    private final int @Nullable [] offset;
    /** WEOrigin (world position of the minimum point), null when absent. */
    private final int @Nullable [] origin;

    public SchematicData(short width, short height, short length, int[] blockIds, int[] blockData) {
        this(width, height, length, blockIds, blockData, null, null);
    }

    public SchematicData(short width, short height, short length, int[] blockIds, int[] blockData,
                         int @Nullable [] offset, int @Nullable [] origin) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.blockIds = blockIds;
        this.blockData = blockData;
        this.offset = offset;
        this.origin = origin;
    }

    public short getWidth() {
        return width;
    }

    public short getHeight() {
        return height;
    }

    public short getLength() {
        return length;
    }

    public int[] getBlockIds() {
        return blockIds;
    }

    public int[] getBlockData() {
        return blockData;
    }

    /**
     * Get the paste offset (WEOffset, minimum point relative to the copy origin).
     *
     * @return an [x, y, z] array or null when the input carried no offset.
     */
    public int @Nullable [] getOffset() {
        return offset;
    }

    /**
     * Get the world origin (WEOrigin, world position of the minimum point).
     *
     * @return an [x, y, z] array or null when the input carried no origin.
     */
    public int @Nullable [] getOrigin() {
        return origin;
    }

    /**
     * Create a copy of this schematic with different block contents but the same
     * dimensions and offsets.
     */
    public SchematicData withBlocks(int[] blockIds, int[] blockData) {
        return new SchematicData(width, height, length, blockIds, blockData, offset, origin);
    }
}
