package com.hivemc.chunker.schematic;

import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.identifier.Identifier;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.io.Reader;
import com.hivemc.chunker.nbt.io.Writer;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.array.ByteArrayTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.ShortTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import com.hivemc.chunker.nbt.tags.TagWithName;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalInt;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal converter capable of reading Sponge .schem files or classic
 * WorldEdit .schematic files and writing them back to the classic schematic
 * format used by WorldEdit GTNH (1.7.10) with support for extended block IDs.
 */
public final class SchematicConverter {
    private static final String SCHEMATIC_ROOT_NAME = "Schematic";

    private SchematicConverter() {
    }

    /**
     * Convert the input schematic (classic or Sponge) into a classic
     * WorldEdit schematic written to {@code output}.
     *
     * @param input      the input schematic file.
     * @param output     the destination schematic file.
     * @param allowNeids whether NotEnoughIDs encoding (AddBlocks2) should be
     *                   emitted when blocks exceed 4095.
     * @throws IOException if reading or writing fails.
     */
    public static void convert(File input, File output, boolean allowNeids) throws IOException {
        convert(input, output, allowNeids, null, false);
    }

    public static void convert(File input, File output, boolean allowNeids, MappingsFile mappingsFile, boolean legacySimpleMappings) throws IOException {
        SchematicData data = read(input.toPath());
        if (mappingsFile != null) {
            data = applyMappings(data, mappingsFile, legacySimpleMappings);
        }
        writeClassic(output.toPath(), data, allowNeids);
    }

    /**
     * Read a schematic from either Sponge .schem or classic .schematic.
     */
    public static SchematicData read(Path path) throws IOException {
        CompoundTag root = readRoot(path);
        if (root == null) {
            throw new IOException("Invalid schematic: no root tag present");
        }

        if (root.contains("Palette") && root.contains("BlockData")) {
            return readSponge(root);
        }
        return readClassic(root);
    }

    private static SchematicData readClassic(CompoundTag root) throws IOException {
        short width = root.getShort("Width", (short) -1);
        short height = root.getShort("Height", (short) -1);
        short length = root.getShort("Length", (short) -1);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("Invalid schematic dimensions");
        }

        byte[] blocks = root.getByteArray("Blocks");
        byte[] blockData = root.getByteArray("Data");
        if (blocks.length != blockData.length) {
            throw new IOException("Mismatched block/data lengths");
        }

        byte[] addBlocks = root.contains("AddBlocks") ? root.getByteArray("AddBlocks") : null;
        byte[] addBlocks2 = root.contains("AddBlocks2") ? root.getByteArray("AddBlocks2") : null;
        byte[] addData = root.contains("AddData") ? root.getByteArray("AddData") : null;

        int[] blockIds = new int[blocks.length];
        int[] meta = new int[blocks.length];
        for (int index = 0; index < blocks.length; index++) {
            int id = blocks[index] & 0xFF;
            if (addBlocks != null && (index >> 1) < addBlocks.length) {
                int add = (index & 1) == 0 ? addBlocks[index >> 1] & 0x0F : (addBlocks[index >> 1] & 0xF0) >> 4;
                id |= add << 8;
            }
            if (addBlocks2 != null && (index >> 1) < addBlocks2.length) {
                int add = (index & 1) == 0 ? addBlocks2[index >> 1] & 0x0F : (addBlocks2[index >> 1] & 0xF0) >> 4;
                id |= add << 12;
            }

            int data = blockData[index] & 0xFF;
            if (addData != null && index < addData.length) {
                data |= (addData[index] & 0xFF) << 8;
            }
            blockIds[index] = id;
            meta[index] = data;
        }

        return new SchematicData(width, height, length, blockIds, meta);
    }

    private static CompoundTag readRoot(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             GZIPInputStream gzip = new GZIPInputStream(input);
             DataInputStream dataInputStream = new DataInputStream(gzip)) {
            TagWithName<CompoundTag> pair = Tag.decodeNamed(Reader.toJavaReader(dataInputStream), CompoundTag.class);
            return pair.tag();
        }
    }

    private static SchematicData readSponge(CompoundTag root) throws IOException {
        short width = root.getShort("Width", (short) -1);
        short height = root.getShort("Height", (short) -1);
        short length = root.getShort("Length", (short) -1);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("Invalid schematic dimensions");
        }

        CompoundTag paletteTag = root.getCompound("Palette");
        byte[] blockData = root.getByteArray("BlockData");
        int[] blockIds = new int[blockData.length];
        int[] meta = new int[blockData.length];

        for (Map.Entry<String, Tag<?>> entry : paletteTag.getValue().entrySet()) {
            if (entry.getValue() instanceof IntTag indexTag) {
                int index = indexTag.getValue();
                if (index < 0) continue;
                String name = entry.getKey();
                int id = resolveLegacyId(name);
                PaletteMapping mapping = new PaletteMapping(index, id, resolveMetaFromState(name));
                mapping.apply(blockIds, meta, blockData);
            }
        }

        return new SchematicData(width, height, length, blockIds, meta);
    }

    private static int resolveLegacyId(String name) {
        Integer id = LevelConvertMappings.getLegacyId(name);
        return id != null ? id : 0;
    }

    private static int resolveMetaFromState(String state) {
        int bracket = state.indexOf('[');
        if (bracket == -1) return 0;
        // Simple heuristic: look for ":<meta>" suffix inside the state string (e.g. "minecraft:stone[type=1]")
        int equals = state.lastIndexOf('=');
        if (equals == -1) return 0;
        try {
            String value = state.substring(equals + 1, state.length() - 1);
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static SchematicData applyMappings(SchematicData data, MappingsFile mappingsFile, boolean legacySimpleMappings) {
        int[] ids = Arrays.copyOf(data.getBlockIds(), data.getBlockIds().length);
        int[] meta = Arrays.copyOf(data.getBlockData(), data.getBlockData().length);

        for (int i = 0; i < ids.length; i++) {
            String identifier = LevelConvertMappings.getLegacyIdentifier(ids[i]);
            if (identifier == null) {
                continue;
            }

            OptionalInt metaValue = legacySimpleMappings ? OptionalInt.of(meta[i]) : OptionalInt.empty();
            Identifier input = Identifier.fromData(identifier, metaValue);
            mappingsFile.convertBlock(input).ifPresent(converted -> {
                Integer newId = LevelConvertMappings.getLegacyId(converted.getIdentifier());
                if (newId != null) {
                    ids[i] = newId;
                    meta[i] = converted.getDataValue().orElse(meta[i]);
                }
            });
        }

        return new SchematicData(data.getWidth(), data.getHeight(), data.getLength(), ids, meta);
    }

    /**
     * Write the schematic to classic WorldEdit schematic format.
     */
    public static void writeClassic(Path output, SchematicData schematic, boolean allowNeids) throws IOException {
        int volume = schematic.getBlockIds().length;
        byte[] blocks = new byte[volume];
        byte[] data = new byte[volume];
        byte[] addBlocks = null;
        byte[] addBlocks2 = null;
        byte[] addData = null;

        for (int i = 0; i < volume; i++) {
            int id = schematic.getBlockIds()[i];
            int meta = schematic.getBlockData()[i];
            blocks[i] = (byte) (id & 0xFF);
            if (id > 255) {
                if (addBlocks == null) addBlocks = new byte[(volume >> 1) + 1];
                addBlocks[i >> 1] = (byte) (((i & 1) == 0) ? addBlocks[i >> 1] & 0xF0 | (id >> 8) & 0xF
                        : addBlocks[i >> 1] & 0xF | ((id >> 8) & 0xF) << 4);
            }
            if (allowNeids && id > 4095) {
                if (addBlocks2 == null) addBlocks2 = new byte[(volume >> 1) + 1];
                addBlocks2[i >> 1] = (byte) (((i & 1) == 0) ? addBlocks2[i >> 1] & 0xF0 | (id >> 12) & 0xF
                        : addBlocks2[i >> 1] & 0xF | ((id >> 12) & 0xF) << 4);
            }

            data[i] = (byte) (meta & 0xFF);
            if (meta > 15) {
                if (addData == null) addData = new byte[volume];
                addData[i] = (byte) ((meta >> 8) & 0xFF);
            }
        }

        CompoundTag root = new CompoundTag();
        root.put("Width", new ShortTag(schematic.getWidth()));
        root.put("Height", new ShortTag(schematic.getHeight()));
        root.put("Length", new ShortTag(schematic.getLength()));
        root.put("Materials", new StringTag("Alpha"));
        root.put("Blocks", new ByteArrayTag(blocks));
        root.put("Data", new ByteArrayTag(data));
        root.put("Entities", new ListTag<>(TagType.COMPOUND, Arrays.asList()));
        root.put("TileEntities", new ListTag<>(TagType.COMPOUND, Arrays.asList()));

        if (addBlocks != null) {
            root.put("AddBlocks", new ByteArrayTag(addBlocks));
        }

        if (addBlocks2 != null) {
            root.put("AddBlocks2", new ByteArrayTag(addBlocks2));
        }

        if (addData != null) {
            root.put("AddData", new ByteArrayTag(addData));
        }

        Files.createDirectories(output.getParent());
        writeWithRootName(output, root);
    }

    private static void writeWithRootName(Path output, CompoundTag root) throws IOException {
        try (var fileOutputStream = Files.newOutputStream(output);
             var gzipOutputStream = new GZIPOutputStream(fileOutputStream);
             var bufferedOutputStream = new BufferedOutputStream(gzipOutputStream);
             var writerStream = new DataOutputStream(bufferedOutputStream)) {
            Tag.encodeNamed(Writer.toJavaWriter(writerStream), SCHEMATIC_ROOT_NAME, root);
            writerStream.flush();
        }
    }

    private record PaletteMapping(int paletteIndex, int blockId, int meta) {
        void apply(int[] ids, int[] data, byte[] paletteData) {
            for (int i = 0; i < paletteData.length; i++) {
                if ((paletteData[i] & 0xFF) == paletteIndex) {
                    ids[i] = blockId;
                    data[i] = meta;
                }
            }
        }
    }
}
