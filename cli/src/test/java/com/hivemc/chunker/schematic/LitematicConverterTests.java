package com.hivemc.chunker.schematic;

import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.parser.SimpleMappingsParser;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.array.LongArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Litematica .litematic reading: bit-packed BlockStates decoding,
 * negative region sizes, multi-region composition and the shared palette
 * resolution pipeline.
 */
class LitematicConverterTests {
    private static final int DV_1_13_2 = 1631;
    private static final int DV_1_21_1 = 3955;

    @BeforeEach
    void clearLevelConvertMappings() throws Exception {
        LevelConvertMappings.load(null);
    }

    // ================= helpers =================

    /** Build a palette entry compound: {Name, Properties?}. */
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

    /** Litematica bit width for a palette size: max(2, ceil(log2(size))). */
    private static int bitsFor(int paletteSize) {
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
    }

    /** Pack palette indices the way Litematica does (entries span long boundaries). */
    private static long[] pack(int[] indices, int bits) {
        long[] out = new long[(int) (((long) indices.length * bits + 63) / 64)];
        for (int i = 0; i < indices.length; i++) {
            long startOffset = (long) i * bits;
            int start = (int) (startOffset >> 6);
            int end = (int) (((long) (i + 1) * bits - 1) >> 6);
            int shift = (int) (startOffset & 0x3F);
            out[start] |= ((long) indices[i]) << shift;
            if (end != start) {
                out[end] |= ((long) indices[i]) >>> (64 - shift);
            }
        }
        return out;
    }

    /** Build a region compound. Indices are in YZX order over the ABSOLUTE size. */
    private static CompoundTag region(int posX, int posY, int posZ, int sizeX, int sizeY, int sizeZ,
                                      List<CompoundTag> palette, int[] indices) {
        CompoundTag position = new CompoundTag();
        position.put("x", new IntTag(posX));
        position.put("y", new IntTag(posY));
        position.put("z", new IntTag(posZ));

        CompoundTag size = new CompoundTag();
        size.put("x", new IntTag(sizeX));
        size.put("y", new IntTag(sizeY));
        size.put("z", new IntTag(sizeZ));

        CompoundTag region = new CompoundTag();
        region.put("Position", position);
        region.put("Size", size);
        region.put("BlockStatePalette", new ListTag<>(TagType.COMPOUND, new ArrayList<>(palette)));
        region.put("BlockStates", new LongArrayTag(pack(indices, bitsFor(palette.size()))));
        region.put("TileEntities", new ListTag<>(TagType.COMPOUND, Arrays.asList()));
        region.put("Entities", new ListTag<>(TagType.COMPOUND, Arrays.asList()));
        return region;
    }

    private static Path writeLitematic(int dataVersion, LinkedHashMap<String, CompoundTag> regions) throws Exception {
        CompoundTag regionsTag = new CompoundTag();
        regions.forEach(regionsTag::put);

        CompoundTag root = new CompoundTag();
        root.put("Version", new IntTag(6));
        if (dataVersion > 0) {
            root.put("MinecraftDataVersion", new IntTag(dataVersion));
        }
        root.put("Regions", regionsTag);

        Path file = Files.createTempFile("litematic", ".litematic");
        Tag.writeGZipJavaNBT(file.toFile(), root);
        return file;
    }

    private static Path singleRegion(int dataVersion, CompoundTag region) throws Exception {
        LinkedHashMap<String, CompoundTag> regions = new LinkedHashMap<>();
        regions.put("main", region);
        return writeLitematic(dataVersion, regions);
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

    private static MappingsFile mappings(String rules) throws Exception {
        Path mappingFile = Files.createTempFile("mapping", ".txt");
        Files.writeString(mappingFile, rules);
        return SimpleMappingsParser.parse(mappingFile);
    }

    private static SchematicData convertAndRead(Path input, MappingsFile mappingsFile, boolean legacySimpleMappings) throws Exception {
        Path output = Files.createTempFile("litematic-out", ".schematic");
        SchematicConverter.convert(input.toFile(), output.toFile(), true, mappingsFile, legacySimpleMappings);
        return SchematicConverter.read(output);
    }

    // ================= bit unpacking =================

    @Test
    void simpleTwoBitRoundTrip() throws Exception {
        // Two palette entries still use the 2-bit minimum
        List<CompoundTag> palette = List.of(blockState("minecraft:air"), blockState("minecraft:stone"));
        int[] indices = {0, 1, 1, 0, 1, 0, 0, 1};
        Path input = singleRegion(DV_1_13_2, region(0, 0, 0, 2, 2, 2, palette, indices));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(2, data.getWidth());
        assertEquals(2, data.getHeight());
        assertEquals(2, data.getLength());
        for (int i = 0; i < 8; i++) {
            assertEquals(indices[i] == 1 ? 1 : 0, data.getBlockIds()[i], "wrong block at index " + i);
        }
    }

    @Test
    void singleEntryPaletteUsesTwoBits() throws Exception {
        // paletteSize=1 -> bits stays at the 2-bit minimum, all blocks are entry 0
        List<CompoundTag> palette = List.of(blockState("minecraft:stone"));
        int[] indices = new int[27];
        Path input = singleRegion(DV_1_13_2, region(0, 0, 0, 3, 3, 3, palette, indices));

        SchematicData data = SchematicConverter.read(input);
        for (int i = 0; i < 27; i++) {
            assertEquals(1, data.getBlockIds()[i]);
        }
    }

    @Test
    void entriesSpanningLongBoundaries() throws Exception {
        // Wool colors force distinct outputs per palette index. Test bit widths
        // 2..7 with patterns long enough to straddle several long boundaries.
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

        for (int paletteSize : new int[]{3, 4, 5, 9, 16, 33, 65}) {
            List<CompoundTag> palette = new ArrayList<>();
            for (int i = 0; i < paletteSize; i++) {
                // Cycle wool colors, data value = i % 16
                palette.add(blockState("minecraft:" + colors[i % 16] + "_wool"));
            }

            // 100 blocks cycling through the palette, guaranteed to straddle longs
            int[] indices = new int[100];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = i % paletteSize;
            }
            Path input = singleRegion(DV_1_13_2, region(0, 0, 0, 100, 1, 1, palette, indices));

            SchematicData data = SchematicConverter.read(input);
            for (int i = 0; i < indices.length; i++) {
                assertEquals(35, data.getBlockIds()[i], "palette size " + paletteSize + " block " + i);
                assertEquals(indices[i] % 16, data.getBlockData()[i],
                        "palette size " + paletteSize + " (bits=" + bitsFor(paletteSize) + ") block " + i);
            }
        }
    }

    // ================= region geometry =================

    @Test
    void negativeRegionSizeNormalized() throws Exception {
        // Position (2,0,2) with Size (-2,1,-2) covers x=1..2, z=1..2
        List<CompoundTag> palette = List.of(blockState("minecraft:air"), blockState("minecraft:stone"));
        int[] indices = {1, 1, 1, 1};
        Path input = singleRegion(DV_1_13_2, region(2, 0, 2, -2, 1, -2, palette, indices));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(2, data.getWidth());
        assertEquals(1, data.getHeight());
        assertEquals(2, data.getLength());
        for (int i = 0; i < 4; i++) {
            assertEquals(1, data.getBlockIds()[i]);
        }
    }

    @Test
    void multiRegionComposition() throws Exception {
        // Region A: stone at (0,0,0), Region B: gold block at (3,0,0) -> 4x1x1 with an air gap
        List<CompoundTag> stone = List.of(blockState("minecraft:stone"));
        List<CompoundTag> gold = List.of(blockState("minecraft:gold_block"));

        LinkedHashMap<String, CompoundTag> regions = new LinkedHashMap<>();
        regions.put("a", region(0, 0, 0, 1, 1, 1, stone, new int[]{0}));
        regions.put("b", region(3, 0, 0, 1, 1, 1, gold, new int[]{0}));
        Path input = writeLitematic(DV_1_13_2, regions);

        SchematicData data = SchematicConverter.read(input);
        assertEquals(4, data.getWidth());
        assertEquals(1, data.getHeight());
        assertEquals(1, data.getLength());
        assertEquals(1, data.getBlockIds()[0], "stone from region a");
        assertEquals(0, data.getBlockIds()[1], "gap must be air");
        assertEquals(0, data.getBlockIds()[2], "gap must be air");
        assertEquals(41, data.getBlockIds()[3], "gold from region b");
    }

    @Test
    void negativeRegionPositionsNormalizeToZero() throws Exception {
        // Regions can sit at negative world coordinates, the output is re-based to 0
        List<CompoundTag> palette = List.of(blockState("minecraft:stone"));
        Path input = singleRegion(DV_1_13_2, region(-10, -5, -10, 2, 1, 1, palette, new int[]{0, 0}));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(2, data.getWidth());
        assertEquals(1, data.getBlockIds()[0]);
        assertEquals(1, data.getBlockIds()[1]);
    }

    @Test
    void yzxOrderPreserved() throws Exception {
        // Marker blocks at distinct positions of a 2x2x2 region
        List<CompoundTag> palette = List.of(
                blockState("minecraft:air"),
                blockState("minecraft:stone"),
                blockState("minecraft:oak_planks"));
        // stone at (x=1,z=0,y=0) -> index 1; planks at (x=0,z=1,y=1) -> index 6
        int[] indices = {0, 1, 0, 0, 0, 0, 2, 0};
        Path input = singleRegion(DV_1_13_2, region(0, 0, 0, 2, 2, 2, palette, indices));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(1, data.getBlockIds()[1], "stone at (1,0,0)");
        assertEquals(5, data.getBlockIds()[6], "planks at (0,1,1)");
    }

    // ================= palette resolution (shared pipeline) =================

    @Test
    void rotationsResolveThroughProperties() throws Exception {
        List<CompoundTag> palette = List.of(
                blockState("minecraft:oak_stairs", "facing", "east", "half", "bottom", "shape", "straight", "waterlogged", "false"),
                blockState("minecraft:oak_stairs", "facing", "north", "half", "top", "shape", "straight", "waterlogged", "false"),
                blockState("minecraft:wall_torch", "facing", "west"),
                blockState("minecraft:oak_log", "axis", "x"));
        int[] indices = {0, 1, 2, 3};
        Path input = singleRegion(DV_1_13_2, region(0, 0, 0, 4, 1, 1, palette, indices));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(53, data.getBlockIds()[0]);
        assertEquals(0, data.getBlockData()[0], "east/bottom stairs");
        assertEquals(53, data.getBlockIds()[1]);
        assertEquals(7, data.getBlockData()[1], "north/top stairs");
        assertEquals(50, data.getBlockIds()[2]);
        assertEquals(2, data.getBlockData()[2], "west wall torch");
        assertEquals(17, data.getBlockIds()[3]);
        assertEquals(4, data.getBlockData()[3], "x-axis oak log");
    }

    @Test
    void modernDataVersionResolves() throws Exception {
        // 1.21.1 palette identifiers (matches real Litematica exports)
        List<CompoundTag> palette = List.of(
                blockState("minecraft:smooth_stone"),
                blockState("minecraft:cracked_stone_bricks"));
        Path input = singleRegion(DV_1_21_1, region(0, 0, 0, 2, 1, 1, palette, new int[]{0, 1}));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(43, data.getBlockIds()[0], "smooth stone folds back to double stone slab");
        assertEquals(8, data.getBlockData()[0]);
        assertEquals(98, data.getBlockIds()[1], "cracked stone bricks");
        assertEquals(2, data.getBlockData()[1]);
    }

    @Test
    void missingDataVersionDefaultsTo113() throws Exception {
        List<CompoundTag> palette = List.of(blockState("minecraft:stone_bricks"));
        Path input = singleRegion(0, region(0, 0, 0, 1, 1, 1, palette, new int[]{0}));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(98, data.getBlockIds()[0]);
    }

    @Test
    void unmappedBlocksBecomeAir() throws Exception {
        List<CompoundTag> palette = List.of(
                blockState("minecraft:ochre_froglight", "axis", "y"),
                blockState("minecraft:stone"));
        Path input = singleRegion(DV_1_21_1, region(0, 0, 0, 2, 1, 1, palette, new int[]{0, 1}));

        SchematicData data = SchematicConverter.read(input);
        assertEquals(0, data.getBlockIds()[0], "froglight has no 1.7.10 equivalent");
        assertEquals(1, data.getBlockIds()[1]);
    }

    // ================= mapping rule formats =================

    @Test
    void namespaceMappingApplies() throws Exception {
        loadLevelDat(Map.of("etfuturum:observer", 16000));
        MappingsFile rules = mappings("minecraft:observer -> etfuturum:observer\n");

        List<CompoundTag> palette = List.of(blockState("minecraft:observer", "facing", "north", "powered", "false"));
        Path input = singleRegion(DV_1_13_2, region(0, 0, 0, 1, 1, 1, palette, new int[]{0}));

        SchematicData result = convertAndRead(input, rules, true);
        assertEquals(16000, result.getBlockIds()[0]);
    }

    @Test
    void numericIdRuleApplies() throws Exception {
        // Numeric rules fire on the resolved legacy ID, same as Sponge inputs
        MappingsFile rules = mappings("52 -> minecraft:air\n");

        List<CompoundTag> palette = List.of(blockState("minecraft:spawner"), blockState("minecraft:stone"));
        Path input = singleRegion(DV_1_13_2, region(0, 0, 0, 2, 1, 1, palette, new int[]{0, 1}));

        SchematicData result = convertAndRead(input, rules, true);
        assertEquals(0, result.getBlockIds()[0], "spawner resolves to 52 then the numeric rule fires");
        assertEquals(1, result.getBlockIds()[1]);
    }

    @Test
    void dataSpecificMappingApplies() throws Exception {
        loadLevelDat(Map.of("uptodate:stone", 15137));
        MappingsFile rules = mappings("minecraft:stone[data=1] -> uptodate:stone[data=1]\n");

        List<CompoundTag> palette = List.of(blockState("minecraft:granite"), blockState("minecraft:stone"));
        Path input = singleRegion(DV_1_13_2, region(0, 0, 0, 2, 1, 1, palette, new int[]{0, 1}));

        SchematicData result = convertAndRead(input, rules, true);
        assertEquals(15137, result.getBlockIds()[0], "granite (stone[data=1]) must remap");
        assertEquals(1, result.getBlockData()[0]);
        assertEquals(1, result.getBlockIds()[1], "plain stone must not remap");
        assertEquals(0, result.getBlockData()[1]);
    }

    // ================= file handling =================

    @Test
    void directoryConversionPicksUpLitematics() throws Exception {
        Path inputDir = Files.createTempDirectory("lit-input");
        List<CompoundTag> palette = List.of(blockState("minecraft:stone"));
        CompoundTag regionTag = region(0, 0, 0, 1, 1, 1, palette, new int[]{0});
        LinkedHashMap<String, CompoundTag> regions = new LinkedHashMap<>();
        regions.put("main", regionTag);

        CompoundTag regionsTag = new CompoundTag();
        regions.forEach(regionsTag::put);
        CompoundTag root = new CompoundTag();
        root.put("Version", new IntTag(6));
        root.put("MinecraftDataVersion", new IntTag(DV_1_13_2));
        root.put("Regions", regionsTag);
        Tag.writeGZipJavaNBT(inputDir.resolve("build.litematic").toFile(), root);

        Path outputDir = Files.createTempDirectory("lit-output");
        int count = SchematicConverter.convertDirectory(inputDir, outputDir, true, null, false);

        assertEquals(1, count);
        assertTrue(Files.exists(outputDir.resolve("build.schematic")), "output must use the .schematic extension");
        SchematicData data = SchematicConverter.read(outputDir.resolve("build.schematic"));
        assertEquals(1, data.getBlockIds()[0]);
    }
}
