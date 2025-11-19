package com.hivemc.chunker.schematic;

/**
 * Represents the contents of a schematic file using legacy block IDs and data
 * values compatible with WorldEdit for Minecraft 1.7.10.
 */
public class SchematicData {
    private final short width;
    private final short height;
    private final short length;
    private final int[] blockIds;
    private final int[] blockData;

    public SchematicData(short width, short height, short length, int[] blockIds, int[] blockData) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.blockIds = blockIds;
        this.blockData = blockData;
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
}
