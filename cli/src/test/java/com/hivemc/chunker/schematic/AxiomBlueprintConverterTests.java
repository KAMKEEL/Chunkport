package com.hivemc.chunker.schematic;

import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.parser.SimpleMappingsParser;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.io.Writer;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.array.LongArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.LongTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Axiom .bp blueprint reading: the container framing, padded chunk-style
 * bit packing, multi-section composition and the shared palette resolution pipeline.
 */
class AxiomBlueprintConverterTests {
    private static final int MAGIC = 182827830;
    private static final int DV_1_13_2 = 1631;
    private static final int DV_1_21_4 = 4189;

    @BeforeEach
    void clearLevelConvertMappings() throws Exception {
        LevelConvertMappings.load(null);
    }

    // ================= helpers =================

    private static CompoundTag blockState(String name, String... properties) {
        CompoundTag entry = new CompoundTag();
        entry.put("Name", new StringTag(name));
        if (properties.length > 0) {
            CompoundTag props = new CompoundTag();
            for (int i = 0; i < properties.length; i += 2) {
                props.put(properties[i], new StringTag(properties[i + 1]));
            }
            entry.put("Properties", props);
        }
        return entry;
    }

    /** Padded chunk-style packing: min 4 bits, entries never span longs. */
    private static long[] pack(int[] indices, int paletteSize) {
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        int perLong = 64 / bits;
        long[] out = new long[(indices.length + perLong - 1) / perLong];
        for (int i = 0; i < indices.length; i++) {
            out[i / perLong] |= ((long) indices[i]) << ((i % perLong) * bits);
        }
        return out;
    }

    /** Build a 16^3 section at the given section coordinates. Indices are y*256+z*16+x. */
    private static CompoundTag section(int sx, int sy, int sz, List<CompoundTag> palette, int[] indices, boolean omitData) {
        CompoundTag blockStates = new CompoundTag();
        blockStates.put("Palette", new ListTag<>(TagType.COMPOUND, new ArrayList<>(palette)));
        if (!omitData) {
            blockStates.put("Data", new LongArrayTag(pack(indices, palette.size())));
        }

        CompoundTag section = new CompoundTag();
        section.put("X", new IntTag(sx));
        section.put("Y", new IntTag(sy));
        section.put("Z", new IntTag(sz));
        section.put("BlockStates", blockStates);
        return section;
    }

    /** Write a complete .bp container: magic + header + thumbnail + gzipped block data. */
    private static Path writeBlueprint(int dataVersion, List<CompoundTag> sections) throws Exception {
        CompoundTag header = new CompoundTag();
        header.put("Version", new LongTag(1L));
        header.put("Name", new StringTag("test"));
        header.put("Author", new StringTag("junit"));
        byte[] headerBytes = encodeNbt(header);

        CompoundTag blockData = new CompoundTag();
        blockData.put("DataVersion", new IntTag(dataVersion));
        blockData.put("BlockRegion", new ListTag<>(TagType.COMPOUND, new ArrayList<>(sections)));
        blockData.put("BlockEntities", new ListTag<>(TagType.COMPOUND, Arrays.asList()));
        blockData.put("Entities", new ListTag<>(TagType.COMPOUND, Arrays.asList()));

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed);
             DataOutputStream nbtOut = new DataOutputStream(gzip)) {
            Tag.encodeNamed(Writer.toJavaWriter(nbtOut), "", blockData);
        }

        Path file = Files.createTempFile("blueprint", ".bp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(MAGIC);
            out.writeInt(headerBytes.length);
            out.write(headerBytes);
            byte[] thumbnail = new byte[]{1, 2, 3, 4}; // stand-in PNG bytes
            out.writeInt(thumbnail.length);
            out.write(thumbnail);
            byte[] blob = compressed.toByteArray();
            out.writeInt(blob.length);
            out.write(blob);
        }
        return file;
    }

    private static byte[] encodeNbt(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            Tag.encodeNamed(Writer.toJavaWriter(out), "", tag);
        }
        return bytes.toByteArray();
    }

    /** Section-local index for (x, y, z). */
    private static int sectionIndex(int x, int y, int z) {
        return y * 256 + z * 16 + x;
    }

    /** Output index for global (x, y, z) in a schematic of the given dimensions. */
    private static int outputIndex(SchematicData data, int x, int y, int z) {
        return (y * data.getLength() + z) * data.getWidth() + x;
    }

    private static void loadLevelDat(Map<String, Integer> ids) throws Exception {
        CompoundTag itemData = new CompoundTag();
        ids.forEach((key, value) -> itemData.put(key, new IntTag(value)));
        CompoundTag forge = new CompoundTag();
        forge.put("ItemData", itemData);
        CompoundTag level = new CompoundTag();
        level.put("FML", forge);

        File levelDat = File.createTempFile("level", ".dat");
        levelDat.deleteOnExit();
        Tag.writeGZipJavaNBT(levelDat, level);
        LevelConvertMappings.load(levelDat);
    }

    // ================= container + packing =================

    @Test
    void simpleSingleSectionRoundTrip() throws Exception {
        // Stone at (1,0,0) and planks at (0,1,1), everything else air
        List<CompoundTag> palette = List.of(
                blockState("minecraft:air"),
                blockState("minecraft:stone"),
                blockState("minecraft:oak_planks"));
        int[] indices = new int[4096];
        indices[sectionIndex(1, 0, 0)] = 1;
        indices[sectionIndex(0, 1, 1)] = 2;

        Path input = writeBlueprint(DV_1_13_2, List.of(section(0, 0, 0, palette, indices, false)));
        SchematicData data = SchematicConverter.read(input);

        assertEquals(16, data.getWidth());
        assertEquals(16, data.getHeight());
        assertEquals(16, data.getLength());
        assertEquals(1, data.getBlockIds()[outputIndex(data, 1, 0, 0)], "stone at (1,0,0)");
        assertEquals(5, data.getBlockIds()[outputIndex(data, 0, 1, 1)], "planks at (0,1,1)");
        assertEquals(0, data.getBlockIds()[outputIndex(data, 5, 5, 5)]);
    }

    @Test
    void paddedPackingAboveSixteenEntries() throws Exception {
        // 17 palette entries force 5 bits/entry: 12 per long, entry 12 starts long[1]
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        List<CompoundTag> palette = new ArrayList<>();
        palette.add(blockState("minecraft:air"));
        for (String color : colors) {
            palette.add(blockState("minecraft:" + color + "_wool"));
        }

        // First 17 blocks cycle every palette entry, crossing the first long boundary
        int[] indices = new int[4096];
        for (int i = 0; i < 17; i++) {
            indices[i] = i;
        }

        Path input = writeBlueprint(DV_1_13_2, List.of(section(0, 0, 0, palette, indices, false)));
        SchematicData data = SchematicConverter.read(input);

        assertEquals(0, data.getBlockIds()[0], "air");
        for (int i = 1; i < 17; i++) {
            assertEquals(35, data.getBlockIds()[i], "wool at block " + i);
            assertEquals(i - 1, data.getBlockData()[i], "wool color at block " + i);
        }
    }

    @Test
    void singleEntryPaletteWithoutDataArray() throws Exception {
        // Sections with one palette entry may omit the Data array entirely
        List<CompoundTag> palette = List.of(blockState("minecraft:stone"));
        Path input = writeBlueprint(DV_1_13_2, List.of(section(0, 0, 0, palette, new int[0], true)));

        SchematicData data = SchematicConverter.read(input);
        for (int i = 0; i < 4096; i++) {
            assertEquals(1, data.getBlockIds()[i]);
        }
    }

    @Test
    void readsRealAxiomLowercaseKeys() throws Exception {
        // Axiom itself writes lowercase palette/data (like 1.18+ chunk NBT) and
        // uses void_air as the unselected filler - both must be handled
        CompoundTag blockStates = new CompoundTag();
        List<CompoundTag> palette = List.of(
                blockState("minecraft:void_air"),
                blockState("minecraft:stone"));
        int[] indices = new int[4096];
        indices[sectionIndex(3, 2, 1)] = 1;
        blockStates.put("palette", new ListTag<>(TagType.COMPOUND, new ArrayList<>(palette)));
        blockStates.put("data", new LongArrayTag(pack(indices, palette.size())));

        CompoundTag section = new CompoundTag();
        section.put("X", new IntTag(0));
        section.put("Y", new IntTag(0));
        section.put("Z", new IntTag(0));
        section.put("BlockStates", blockStates);

        Path input = writeBlueprint(DV_1_21_4, List.of(section));
        SchematicData data = SchematicConverter.read(input);

        assertEquals(1, data.getBlockIds()[outputIndex(data, 3, 2, 1)], "stone via lowercase palette/data");
        assertEquals(0, data.getBlockIds()[outputIndex(data, 0, 0, 0)], "void_air filler becomes air");
    }

    @Test
    void rejectsWrongMagic() throws Exception {
        Path file = Files.createTempFile("bad", ".bp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(12345678);
            out.writeInt(0);
        }
        assertThrows(java.io.IOException.class, () -> SchematicConverter.read(file));
    }

    // ================= multi-section composition =================

    @Test
    void multiSectionComposition() throws Exception {
        // Stone section at (0,0,0), gold section at (1,0,0) -> 32x16x16
        List<CompoundTag> stone = List.of(blockState("minecraft:stone"));
        List<CompoundTag> gold = List.of(blockState("minecraft:gold_block"));

        Path input = writeBlueprint(DV_1_13_2, List.of(
                section(0, 0, 0, stone, new int[0], true),
                section(1, 0, 0, gold, new int[0], true)));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(32, data.getWidth());
        assertEquals(1, data.getBlockIds()[outputIndex(data, 0, 0, 0)], "stone in first section");
        assertEquals(41, data.getBlockIds()[outputIndex(data, 16, 0, 0)], "gold in second section");
    }

    @Test
    void sparseSectionsLeaveAirGaps() throws Exception {
        // Sections at (0,0,0) and (2,0,0) leave the middle section as air
        List<CompoundTag> stone = List.of(blockState("minecraft:stone"));
        Path input = writeBlueprint(DV_1_13_2, List.of(
                section(0, 0, 0, stone, new int[0], true),
                section(2, 0, 0, stone, new int[0], true)));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(48, data.getWidth());
        assertEquals(1, data.getBlockIds()[outputIndex(data, 0, 0, 0)]);
        assertEquals(0, data.getBlockIds()[outputIndex(data, 24, 0, 0)], "gap section must be air");
        assertEquals(1, data.getBlockIds()[outputIndex(data, 32, 0, 0)]);
    }

    @Test
    void negativeSectionCoordinatesNormalize() throws Exception {
        List<CompoundTag> stone = List.of(blockState("minecraft:stone"));
        Path input = writeBlueprint(DV_1_13_2, List.of(section(-3, -1, -2, stone, new int[0], true)));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(16, data.getWidth());
        assertEquals(1, data.getBlockIds()[0]);
    }

    // ================= palette resolution (shared pipeline) =================

    @Test
    void rotationsResolveThroughProperties() throws Exception {
        List<CompoundTag> palette = List.of(
                blockState("minecraft:air"),
                blockState("minecraft:oak_stairs", "facing", "east", "half", "bottom", "shape", "straight", "waterlogged", "false"),
                blockState("minecraft:oak_log", "axis", "x"));
        int[] indices = new int[4096];
        indices[sectionIndex(0, 0, 0)] = 1;
        indices[sectionIndex(1, 0, 0)] = 2;

        Path input = writeBlueprint(DV_1_13_2, List.of(section(0, 0, 0, palette, indices, false)));
        SchematicData data = SchematicConverter.read(input);

        assertEquals(53, data.getBlockIds()[0]);
        assertEquals(0, data.getBlockData()[0], "east/bottom stairs");
        assertEquals(17, data.getBlockIds()[1]);
        assertEquals(4, data.getBlockData()[1], "x-axis oak log");
    }

    @Test
    void modernDataVersionResolves() throws Exception {
        // 1.21.4 palette identifiers, the DataVersion the .bp ecosystem currently writes
        List<CompoundTag> palette = List.of(blockState("minecraft:smooth_stone"));
        Path input = writeBlueprint(DV_1_21_4, List.of(section(0, 0, 0, palette, new int[0], true)));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(43, data.getBlockIds()[0], "smooth stone folds back to double stone slab");
        assertEquals(8, data.getBlockData()[0]);
    }

    @Test
    void mappingsApplyThroughBlueprints() throws Exception {
        loadLevelDat(Map.of("etfuturum:observer", 16000));
        Path mappingFile = Files.createTempFile("mapping", ".txt");
        Files.writeString(mappingFile, "minecraft:observer -> etfuturum:observer\n");
        MappingsFile rules = SimpleMappingsParser.parse(mappingFile);

        List<CompoundTag> palette = List.of(blockState("minecraft:observer", "facing", "north", "powered", "false"));
        Path input = writeBlueprint(DV_1_13_2, List.of(section(0, 0, 0, palette, new int[0], true)));

        Path output = Files.createTempFile("blueprint-out", ".schematic");
        SchematicConverter.convert(input.toFile(), output.toFile(), true, rules, true);
        SchematicData result = SchematicConverter.read(output);
        assertEquals(16000, result.getBlockIds()[0]);
    }

    @Test
    void directoryConversionPicksUpBlueprints() throws Exception {
        Path inputDir = Files.createTempDirectory("bp-input");
        List<CompoundTag> palette = List.of(blockState("minecraft:stone"));
        Path source = writeBlueprint(DV_1_13_2, List.of(section(0, 0, 0, palette, new int[0], true)));
        Files.copy(source, inputDir.resolve("build.bp"));

        Path outputDir = Files.createTempDirectory("bp-output");
        int count = SchematicConverter.convertDirectory(inputDir, outputDir, true, null, false);

        assertEquals(1, count);
        assertTrue(Files.exists(outputDir.resolve("build.schematic")), "output must use the .schematic extension");
        SchematicData data = SchematicConverter.read(outputDir.resolve("build.schematic"));
        assertEquals(1, data.getBlockIds()[0]);
    }
}
