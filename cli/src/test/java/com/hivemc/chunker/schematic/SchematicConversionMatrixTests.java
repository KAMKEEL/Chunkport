package com.hivemc.chunker.schematic;

import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.parser.SimpleMappingsParser;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.array.ByteArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.ShortTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conversion matrix tests covering the different input formats (classic 1.12
 * .schematic vs Sponge 1.13+ .schem), the legacy ID encoding systems
 * (base IDs, AddBlocks, NEID AddBlocks2, AddData) and rotation/orientation
 * data preservation for the 1.7.10 output format.
 *
 * Legacy data value references: https://minecraft.wiki (pre-flattening values).
 */
class SchematicConversionMatrixTests {
    // Java DataVersions used to exercise version-aware palette resolution
    private static final int DV_1_13_2 = 1631;
    private static final int DV_1_14 = 1952;
    private static final int DV_1_16_5 = 2586;
    private static final int DV_1_17_1 = 2730;
    private static final int DV_1_19_2 = 3120;

    @BeforeEach
    void clearLevelConvertMappings() throws Exception {
        // Level convert mappings are global, make sure no other test leaks into these
        LevelConvertMappings.load(null);
    }

    // ================= helpers =================

    /** Write a Sponge v2 .schem with the given palette and block indices (YZX order). */
    private static Path writeSponge(int dataVersion, int w, int h, int l, LinkedHashMap<String, Integer> palette, int[] indices) throws Exception {
        CompoundTag paletteTag = new CompoundTag();
        palette.forEach((key, value) -> paletteTag.put(key, new IntTag(value)));

        ByteArrayOutputStream blockData = new ByteArrayOutputStream();
        for (int index : indices) {
            int value = index;
            while ((value & ~0x7F) != 0) {
                blockData.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            blockData.write(value);
        }

        CompoundTag root = new CompoundTag();
        root.put("Version", new IntTag(2));
        if (dataVersion > 0) {
            root.put("DataVersion", new IntTag(dataVersion));
        }
        root.put("Width", new ShortTag((short) w));
        root.put("Height", new ShortTag((short) h));
        root.put("Length", new ShortTag((short) l));
        root.put("Palette", paletteTag);
        root.put("PaletteMax", new IntTag(palette.size()));
        root.put("BlockData", new ByteArrayTag(blockData.toByteArray()));

        Path file = Files.createTempFile("matrix", ".schem");
        Tag.writeGZipJavaNBT(file.toFile(), root);
        return file;
    }

    /** Read a single-block Sponge schematic and return {id, meta}. */
    private static int[] single(int dataVersion, String paletteEntry) throws Exception {
        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        palette.put(paletteEntry, 0);
        Path input = writeSponge(dataVersion, 1, 1, 1, palette, new int[]{0});
        SchematicData data = SchematicConverter.read(input);
        return new int[]{data.getBlockIds()[0], data.getBlockData()[0]};
    }

    private static void assertBlock(int expectedId, int expectedMeta, int dataVersion, String paletteEntry) throws Exception {
        int[] result = single(dataVersion, paletteEntry);
        assertEquals(expectedId, result[0], paletteEntry + " -> wrong block ID");
        assertEquals(expectedMeta, result[1], paletteEntry + " -> wrong data value");
    }

    /** Write a level.dat (plug.dat style) with the given identifier -> ID entries and load it. */
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

    /** Convert input with optional mappings and read the classic result back. */
    private static SchematicData convertAndRead(Path input, MappingsFile mappingsFile, boolean legacySimpleMappings) throws Exception {
        Path output = Files.createTempFile("matrix-out", ".schematic");
        SchematicConverter.convert(input.toFile(), output.toFile(), true, mappingsFile, legacySimpleMappings);
        return SchematicConverter.read(output);
    }

    // ================= rotations: Sponge 1.13+ -> 1.7.10 =================

    @Nested
    class SpongeRotations {
        @Test
        void stairsFacingAllDirections() throws Exception {
            // Legacy stairs: 0=east, 1=west, 2=south, 3=north; +4 = upside down
            assertBlock(53, 0, DV_1_13_2, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
            assertBlock(53, 1, DV_1_13_2, "minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
            assertBlock(53, 2, DV_1_13_2, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
            assertBlock(53, 3, DV_1_13_2, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        }

        @Test
        void stairsUpsideDown() throws Exception {
            assertBlock(53, 4, DV_1_13_2, "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
            assertBlock(53, 7, DV_1_13_2, "minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]");
        }

        @Test
        void stoneStairsVariants() throws Exception {
            // Different stair materials keep their own IDs
            assertBlock(67, 0, DV_1_13_2, "minecraft:cobblestone_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
            assertBlock(108, 2, DV_1_13_2, "minecraft:brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
            assertBlock(109, 3, DV_1_13_2, "minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        }

        @Test
        void logAxes() throws Exception {
            // Legacy log: species (0-3) | axis bits (0=y, 4=x, 8=z)
            assertBlock(17, 0, DV_1_13_2, "minecraft:oak_log[axis=y]");
            assertBlock(17, 4, DV_1_13_2, "minecraft:oak_log[axis=x]");
            assertBlock(17, 8, DV_1_13_2, "minecraft:oak_log[axis=z]");
            assertBlock(17, 1, DV_1_13_2, "minecraft:spruce_log[axis=y]");
            assertBlock(17, 7, DV_1_13_2, "minecraft:jungle_log[axis=x]");
            assertBlock(17, 10, DV_1_13_2, "minecraft:birch_log[axis=z]");
        }

        @Test
        void torchPlacement() throws Exception {
            // Legacy torch: 1=east, 2=west, 3=south, 4=north, 5=standing
            assertBlock(50, 5, DV_1_13_2, "minecraft:torch");
            assertBlock(50, 1, DV_1_13_2, "minecraft:wall_torch[facing=east]");
            assertBlock(50, 2, DV_1_13_2, "minecraft:wall_torch[facing=west]");
            assertBlock(50, 3, DV_1_13_2, "minecraft:wall_torch[facing=south]");
            assertBlock(50, 4, DV_1_13_2, "minecraft:wall_torch[facing=north]");
        }

        @Test
        void ladderFacing() throws Exception {
            // Legacy ladder: 2=north, 3=south, 4=west, 5=east
            assertBlock(65, 2, DV_1_13_2, "minecraft:ladder[facing=north,waterlogged=false]");
            assertBlock(65, 3, DV_1_13_2, "minecraft:ladder[facing=south,waterlogged=false]");
            assertBlock(65, 4, DV_1_13_2, "minecraft:ladder[facing=west,waterlogged=false]");
            assertBlock(65, 5, DV_1_13_2, "minecraft:ladder[facing=east,waterlogged=false]");
        }

        @Test
        void chestFacing() throws Exception {
            // Legacy chest: 2=north, 3=south, 4=west, 5=east
            assertBlock(54, 2, DV_1_13_2, "minecraft:chest[facing=north,type=single,waterlogged=false]");
            assertBlock(54, 5, DV_1_13_2, "minecraft:chest[facing=east,type=single,waterlogged=false]");
        }

        @Test
        void fenceGateFacing() throws Exception {
            // Legacy fence gate: 0=south, 1=west, 2=north, 3=east; +4 = open
            assertBlock(107, 0, DV_1_13_2, "minecraft:oak_fence_gate[facing=south,in_wall=false,open=false,powered=false]");
            assertBlock(107, 1, DV_1_13_2, "minecraft:oak_fence_gate[facing=west,in_wall=false,open=false,powered=false]");
            assertBlock(107, 2, DV_1_13_2, "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]");
            assertBlock(107, 3, DV_1_13_2, "minecraft:oak_fence_gate[facing=east,in_wall=false,open=false,powered=false]");
            assertBlock(107, 7, DV_1_13_2, "minecraft:oak_fence_gate[facing=east,in_wall=false,open=true,powered=false]");
        }

        @Test
        void slabHalves() throws Exception {
            // Legacy wooden slab: species | 8 = top half; double slab uses its own ID
            assertBlock(126, 0, DV_1_13_2, "minecraft:oak_slab[type=bottom,waterlogged=false]");
            assertBlock(126, 8, DV_1_13_2, "minecraft:oak_slab[type=top,waterlogged=false]");
            assertBlock(126, 9, DV_1_13_2, "minecraft:spruce_slab[type=top,waterlogged=false]");
            assertBlock(125, 0, DV_1_13_2, "minecraft:oak_slab[type=double,waterlogged=false]");
        }

        @Test
        void railShapes() throws Exception {
            // Legacy rail: 0=north_south, 1=east_west
            assertBlock(66, 0, DV_1_13_2, "minecraft:rail[shape=north_south]");
            assertBlock(66, 1, DV_1_13_2, "minecraft:rail[shape=east_west]");
        }

        @Test
        void waterloggedBlocksDropWater() throws Exception {
            // Waterlogging doesn't exist in 1.7.10, the block itself must survive
            assertBlock(53, 0, DV_1_13_2, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=true]");
            assertBlock(65, 5, DV_1_13_2, "minecraft:ladder[facing=east,waterlogged=true]");
        }
    }

    // ================= data values: Sponge 1.13+ -> 1.7.10 =================

    @Nested
    class SpongeDataValues {
        @Test
        void allSixteenWoolColors() throws Exception {
            String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
            for (int data = 0; data < 16; data++) {
                assertBlock(35, data, DV_1_13_2, "minecraft:" + colors[data] + "_wool");
            }
        }

        @Test
        void plankSpecies() throws Exception {
            assertBlock(5, 0, DV_1_13_2, "minecraft:oak_planks");
            assertBlock(5, 1, DV_1_13_2, "minecraft:spruce_planks");
            assertBlock(5, 2, DV_1_13_2, "minecraft:birch_planks");
            assertBlock(5, 3, DV_1_13_2, "minecraft:jungle_planks");
            assertBlock(5, 4, DV_1_13_2, "minecraft:acacia_planks");
            assertBlock(5, 5, DV_1_13_2, "minecraft:dark_oak_planks");
        }

        @Test
        void stoneVariantsFlattenToDataValues() throws Exception {
            // 1.13 split stone variants into their own identifiers, they must fold back
            assertBlock(1, 0, DV_1_13_2, "minecraft:stone");
            assertBlock(1, 1, DV_1_13_2, "minecraft:granite");
            assertBlock(1, 2, DV_1_13_2, "minecraft:polished_granite");
            assertBlock(1, 3, DV_1_13_2, "minecraft:diorite");
            assertBlock(1, 5, DV_1_13_2, "minecraft:andesite");
        }

        @Test
        void terracottaColors() throws Exception {
            assertBlock(159, 1, DV_1_13_2, "minecraft:orange_terracotta");
            assertBlock(159, 14, DV_1_13_2, "minecraft:red_terracotta");
        }

        @Test
        void snowLayers() throws Exception {
            assertBlock(78, 0, DV_1_13_2, "minecraft:snow[layers=1]");
            assertBlock(78, 2, DV_1_13_2, "minecraft:snow[layers=3]");
            assertBlock(78, 7, DV_1_13_2, "minecraft:snow[layers=8]");
        }

        @Test
        void sandstoneTypes() throws Exception {
            assertBlock(24, 0, DV_1_13_2, "minecraft:sandstone");
            assertBlock(24, 1, DV_1_13_2, "minecraft:chiseled_sandstone");
            assertBlock(24, 2, DV_1_13_2, "minecraft:cut_sandstone");
        }
    }

    // ================= version-aware palette resolution =================

    @Nested
    class DataVersionAwareness {
        @Test
        void stoneSlabRenamedAcrossVersions() throws Exception {
            // 1.13 calls it stone_slab, 1.14+ renamed it to smooth_stone_slab, same legacy block
            assertBlock(44, 0, DV_1_13_2, "minecraft:stone_slab[type=bottom,waterlogged=false]");
            assertBlock(44, 8, DV_1_13_2, "minecraft:stone_slab[type=top,waterlogged=false]");
            assertBlock(44, 0, DV_1_14, "minecraft:smooth_stone_slab[type=bottom,waterlogged=false]");
            assertBlock(44, 8, DV_1_16_5, "minecraft:smooth_stone_slab[type=top,waterlogged=false]");
        }

        @Test
        void modernVersionsStillResolveCommonBlocks() throws Exception {
            // The same block must resolve regardless of which 1.13+ version wrote the schem
            for (int dataVersion : new int[]{DV_1_13_2, DV_1_14, DV_1_16_5, DV_1_17_1, DV_1_19_2}) {
                assertBlock(53, 3, dataVersion, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
                assertBlock(35, 14, dataVersion, "minecraft:red_wool");
                assertBlock(98, 0, dataVersion, "minecraft:stone_bricks");
            }
        }

        @Test
        void missingDataVersionDefaultsTo113() throws Exception {
            // Sponge v1 files carry no DataVersion, 1.13 identifiers must still work
            assertBlock(53, 0, 0, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }

        @Test
        void blocksNewerThanTargetBecomeAir() throws Exception {
            // No mapping rule and no 1.7.10 equivalent -> air, not garbage IDs
            assertBlock(0, 0, DV_1_14, "minecraft:lectern[facing=west,has_book=false,powered=false]");
            assertBlock(0, 0, DV_1_14, "minecraft:grindstone[face=wall,facing=west]");
        }
    }

    // ================= ID systems: classic encoding round trips =================

    @Nested
    class ClassicIdEncoding {
        @Test
        void baseIdsAndMetaPassThrough() throws Exception {
            // Rotated vanilla blocks in a classic schematic must survive untouched
            int[] ids = {53, 53, 53, 53, 17, 17, 65, 50, 35, 126};
            int[] meta = {0, 1, 4, 7, 4, 8, 5, 1, 14, 9};

            Path input = Files.createTempFile("classic", ".schematic");
            SchematicConverter.writeClassic(input, new SchematicData((short) 10, (short) 1, (short) 1, ids, meta), true);

            SchematicData result = convertAndRead(input, null, false);
            assertArrayEquals(ids, result.getBlockIds());
            assertArrayEquals(meta, result.getBlockData());
        }

        @Test
        void addBlocksNibblePackingEvenAndOdd() throws Exception {
            // IDs 256-4095 use the AddBlocks nibble array, both nibble positions matter
            int[] ids = {300, 301, 4095, 255, 256, 1};
            int[] meta = {0, 0, 0, 0, 0, 0};

            Path file = Files.createTempFile("addblocks", ".schematic");
            SchematicConverter.writeClassic(file, new SchematicData((short) 6, (short) 1, (short) 1, ids, meta), false);

            SchematicData result = SchematicConverter.read(file);
            assertArrayEquals(ids, result.getBlockIds());
        }

        @Test
        void neidAddBlocks2EvenAndOdd() throws Exception {
            // IDs above 4095 need NEID AddBlocks2 encoding
            int[] ids = {5000, 15137, 16572, 4095, 65000, 42};
            int[] meta = {0, 0, 0, 0, 0, 0};

            Path file = Files.createTempFile("neid", ".schematic");
            SchematicConverter.writeClassic(file, new SchematicData((short) 6, (short) 1, (short) 1, ids, meta), true);

            SchematicData result = SchematicConverter.read(file);
            assertArrayEquals(ids, result.getBlockIds());
        }

        @Test
        void addDataForExtendedMeta() throws Exception {
            // Meta above 15 needs the AddData array
            int[] ids = {1, 2, 3};
            int[] meta = {300, 15, 4095};

            Path file = Files.createTempFile("adddata", ".schematic");
            SchematicConverter.writeClassic(file, new SchematicData((short) 3, (short) 1, (short) 1, ids, meta), true);

            SchematicData result = SchematicConverter.read(file);
            assertArrayEquals(meta, result.getBlockData());
        }
    }

    // ================= mappings: classic 1.12 -> 1.7.10 =================

    @Nested
    class ClassicMappings {
        @Test
        void dataSpecificRuleOnlyRewritesMatchingData() throws Exception {
            // stone[data=1] (granite) remaps, stone[data=0] must stay untouched
            loadLevelDat(Map.of("uptodate:stone", 15137));
            MappingsFile rules = mappings("minecraft:stone[data=1] -> uptodate:stone[data=1]\n");

            int[] ids = {1, 1};
            int[] meta = {0, 1};
            Path input = Files.createTempFile("stone", ".schematic");
            SchematicConverter.writeClassic(input, new SchematicData((short) 2, (short) 1, (short) 1, ids, meta), true);

            SchematicData result = convertAndRead(input, rules, true);
            assertEquals(1, result.getBlockIds()[0], "plain stone must not be remapped");
            assertEquals(0, result.getBlockData()[0]);
            assertEquals(15137, result.getBlockIds()[1], "granite must remap to uptodate:stone");
            assertEquals(1, result.getBlockData()[1]);
        }

        @Test
        void spawnerMappedToAir() throws Exception {
            MappingsFile rules = mappings("minecraft:mob_spawner -> minecraft:air\n");

            int[] ids = {52, 4};
            int[] meta = {0, 0};
            Path input = Files.createTempFile("spawner", ".schematic");
            SchematicConverter.writeClassic(input, new SchematicData((short) 2, (short) 1, (short) 1, ids, meta), true);

            SchematicData result = convertAndRead(input, rules, true);
            assertEquals(0, result.getBlockIds()[0], "spawner must become air");
            assertEquals(4, result.getBlockIds()[1], "cobblestone must be untouched");
        }

        @Test
        void modIdRemappedThroughLevelDat() throws Exception {
            // A classic schematic using a mod ID from an old level.dat gets remapped
            // through its identifier to the new level.dat ID
            loadLevelDat(Map.of("oldmod:crystal", 2500, "newmod:crystal", 3600));
            MappingsFile rules = mappings("oldmod:crystal -> newmod:crystal\n");

            int[] ids = {2500};
            int[] meta = {7};
            Path input = Files.createTempFile("mod", ".schematic");
            SchematicConverter.writeClassic(input, new SchematicData((short) 1, (short) 1, (short) 1, ids, meta), true);

            SchematicData result = convertAndRead(input, rules, true);
            assertEquals(3600, result.getBlockIds()[0]);
            assertEquals(7, result.getBlockData()[0], "meta must be preserved through the remap");
        }
    }

    // ================= mappings: Sponge 1.13+ -> 1.7.10 =================

    @Nested
    class SpongeMappings {
        @Test
        void vanillaBlockRedirectedToModBlock() throws Exception {
            // The user's core flow: 1.13 name -> 1.12 identifier -> mapping.txt -> plug.dat NEID
            loadLevelDat(Map.of("etfuturum:observer", 16000));
            MappingsFile rules = mappings("minecraft:observer -> etfuturum:observer\n");

            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put("minecraft:observer[facing=north,powered=false]", 0);
            Path input = writeSponge(DV_1_13_2, 1, 1, 1, palette, new int[]{0});

            SchematicData result = convertAndRead(input, rules, true);
            assertEquals(16000, result.getBlockIds()[0]);
        }

        @Test
        void dataSpecificMappingFromFlattenedName() throws Exception {
            // prismarine_bricks flattens to minecraft:prismarine[data=1], which the
            // mapping file redirects (mirrors the real mapping.txt)
            loadLevelDat(Map.of("uptodate:prismarine_brick", 15150));
            MappingsFile rules = mappings("minecraft:prismarine[data=1] -> uptodate:prismarine_brick\n");

            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put("minecraft:prismarine_bricks", 0);
            Path input = writeSponge(DV_1_13_2, 1, 1, 1, palette, new int[]{0});

            SchematicData result = convertAndRead(input, rules, true);
            assertEquals(15150, result.getBlockIds()[0]);
        }

        @Test
        void moddedPaletteNameResolvedThroughMappings() throws Exception {
            // A modded 1.13+ palette entry unknown to vanilla resolves via mapping.txt
            loadLevelDat(Map.of("bis:SlimeBlock", 14000));
            MappingsFile rules = mappings("minecraft:slime_block -> bis:SlimeBlock\n");

            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put("minecraft:slime_block", 0);
            Path input = writeSponge(DV_1_13_2, 1, 1, 1, palette, new int[]{0});

            SchematicData result = convertAndRead(input, rules, true);
            assertEquals(14000, result.getBlockIds()[0]);
        }

        @Test
        void moddedPaletteNameFoundDirectlyInLevelDat() throws Exception {
            // A mod block that kept the same identifier only needs plug.dat
            loadLevelDat(Map.of("custommod:machine", 4321));

            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put("custommod:machine[facing=north]", 0);
            Path input = writeSponge(DV_1_19_2, 1, 1, 1, palette, new int[]{0});

            SchematicData result = convertAndRead(input, null, false);
            assertEquals(4321, result.getBlockIds()[0]);
        }

        @Test
        void levelDatOverridesVanillaId() throws Exception {
            // plug.dat wins over the built-in vanilla ID table
            loadLevelDat(Map.of("minecraft:bookshelf", 4700));

            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put("minecraft:bookshelf", 0);
            Path input = writeSponge(DV_1_13_2, 1, 1, 1, palette, new int[]{0});

            SchematicData result = convertAndRead(input, null, false);
            assertEquals(4700, result.getBlockIds()[0]);
        }

        @Test
        void neidBlockSurvivesFullFileRoundTrip() throws Exception {
            // Mapping output above 4095 must survive the AddBlocks2 encode/decode
            loadLevelDat(Map.of("etfuturum:deepslate", 16100));
            MappingsFile rules = mappings("minecraft:deepslate -> etfuturum:deepslate\n");

            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put("minecraft:deepslate[axis=y]", 0);
            palette.put("minecraft:stone", 1);
            Path input = writeSponge(DV_1_19_2, 2, 1, 1, palette, new int[]{0, 1});

            SchematicData result = convertAndRead(input, rules, true);
            assertEquals(16100, result.getBlockIds()[0]);
            assertEquals(1, result.getBlockIds()[1]);
        }
    }

    // ================= mapping rule formats: full cross product =================

    /**
     * Every supported mapping.txt rule format, tested for both input formats:
     *   LHS: ID | ID:META | NAMESPACE | NAMESPACE[data=N]
     *   RHS: ID | ID:META | NAMESPACE | NAMESPACE[data=N] | =NAMESPACE
     */
    @Nested
    class RuleFormats {
        /** Build a classic 1x1x1 schematic with a single id:meta block. */
        private Path classic(int id, int meta) throws Exception {
            Path input = Files.createTempFile("fmt", ".schematic");
            SchematicConverter.writeClassic(input, new SchematicData((short) 1, (short) 1, (short) 1, new int[]{id}, new int[]{meta}), true);
            return input;
        }

        /** Build a Sponge 1x1x1 .schem with a single palette entry. */
        private Path sponge(String paletteEntry) throws Exception {
            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put(paletteEntry, 0);
            return writeSponge(DV_1_13_2, 1, 1, 1, palette, new int[]{0});
        }

        private void assertConverted(Path input, String rules, int expectedId, int expectedMeta) throws Exception {
            SchematicData result = convertAndRead(input, mappings(rules), true);
            assertEquals(expectedId, result.getBlockIds()[0], "wrong id for rules: " + rules);
            assertEquals(expectedMeta, result.getBlockData()[0], "wrong meta for rules: " + rules);
        }

        // ---------- classic .schematic input ----------

        @Test
        void classicIdToNamespace() throws Exception {
            // 52 -> minecraft:air
            assertConverted(classic(52, 0), "52 -> minecraft:air\n", 0, 0);
        }

        @Test
        void classicIdToNamespaceWithData() throws Exception {
            // 19 -> custom:sponge[data=5]
            loadLevelDat(Map.of("custom:sponge", 15100));
            assertConverted(classic(19, 0), "19 -> custom:sponge[data=5]\n", 15100, 5);
        }

        @Test
        void classicIdToId() throws Exception {
            // 41 -> 2001 (meta carried over)
            assertConverted(classic(41, 3), "41 -> 2001\n", 2001, 3);
        }

        @Test
        void classicIdToIdWithMeta() throws Exception {
            // 41 -> 2001:7
            assertConverted(classic(41, 0), "41 -> 2001:7\n", 2001, 7);
        }

        @Test
        void classicIdMetaToNamespace() throws Exception {
            // 1:1 -> minecraft:cobblestone, plain stone untouched
            Path input = Files.createTempFile("fmt", ".schematic");
            SchematicConverter.writeClassic(input, new SchematicData((short) 2, (short) 1, (short) 1, new int[]{1, 1}, new int[]{1, 0}), true);
            SchematicData result = convertAndRead(input, mappings("1:1 -> minecraft:cobblestone[data=0]\n"), true);
            assertEquals(4, result.getBlockIds()[0], "granite (1:1) must remap");
            assertEquals(0, result.getBlockData()[0]);
            assertEquals(1, result.getBlockIds()[1], "plain stone (1:0) must not remap");
            assertEquals(0, result.getBlockData()[1]);
        }

        @Test
        void classicIdMetaToIdMeta() throws Exception {
            // 1:1 -> 4:0
            assertConverted(classic(1, 1), "1:1 -> 4:0\n", 4, 0);
        }

        @Test
        void classicNamespaceToId() throws Exception {
            // minecraft:gold_block -> 2001
            assertConverted(classic(41, 0), "minecraft:gold_block -> 2001\n", 2001, 0);
        }

        @Test
        void classicNamespaceToIdWithMeta() throws Exception {
            // minecraft:gold_block -> 2001:7
            assertConverted(classic(41, 0), "minecraft:gold_block -> 2001:7\n", 2001, 7);
        }

        @Test
        void classicNamespaceToNamespaceWithData() throws Exception {
            // minecraft:sponge -> custom:sponge[data=5], output data overrides input meta
            loadLevelDat(Map.of("custom:sponge", 15100));
            assertConverted(classic(19, 0), "minecraft:sponge -> custom:sponge[data=5]\n", 15100, 5);
        }

        @Test
        void classicNamespaceDataToIdMeta() throws Exception {
            // minecraft:stone[data=1] -> 2002:3
            assertConverted(classic(1, 1), "minecraft:stone[data=1] -> 2002:3\n", 2002, 3);
        }

        @Test
        void classicNoLevelConvertPrefix() throws Exception {
            // = prefix must skip the level.dat lookup and use the vanilla ID table
            loadLevelDat(Map.of("minecraft:diamond_block", 9999));
            assertConverted(classic(41, 0), "minecraft:gold_block -> =minecraft:diamond_block\n", 57, 0);
        }

        @Test
        void classicLevelDatAppliesWithoutPrefix() throws Exception {
            // sanity for the test above: without '=' the level.dat ID wins
            loadLevelDat(Map.of("minecraft:diamond_block", 9999));
            assertConverted(classic(41, 0), "minecraft:gold_block -> minecraft:diamond_block\n", 9999, 0);
        }

        // ---------- Sponge .schem input ----------

        @Test
        void spongeNamespaceToId() throws Exception {
            // minecraft:gold_block -> 2001
            assertConverted(sponge("minecraft:gold_block"), "minecraft:gold_block -> 2001\n", 2001, 0);
        }

        @Test
        void spongeNamespaceToIdWithMeta() throws Exception {
            // minecraft:gold_block -> 2001:7
            assertConverted(sponge("minecraft:gold_block"), "minecraft:gold_block -> 2001:7\n", 2001, 7);
        }

        @Test
        void spongeNamespaceToNamespaceWithData() throws Exception {
            // The wet_sponge pattern from the real mapping.txt:
            // minecraft:wet_sponge -> uptodate:sponge[data=1]
            loadLevelDat(Map.of("uptodate:sponge", 15100));
            assertConverted(sponge("minecraft:wet_sponge"), "minecraft:wet_sponge -> uptodate:sponge[data=1]\n", 15100, 1);
        }

        @Test
        void spongeNumericIdRule() throws Exception {
            // Numeric rules apply to the resolved legacy ID, spawner resolves to 52 first
            assertConverted(sponge("minecraft:spawner"), "52 -> minecraft:air\n", 0, 0);
        }

        @Test
        void spongeNumericIdMetaRule() throws Exception {
            // granite flattens to 1:1 before the numeric rule fires
            assertConverted(sponge("minecraft:granite"), "1:1 -> 4:0\n", 4, 0);
            // plain stone (1:0) must not match the 1:1 rule
            assertConverted(sponge("minecraft:stone"), "1:1 -> 4:0\n", 1, 0);
        }

        @Test
        void spongeNameRuleForSpawner() throws Exception {
            // Name-based spawner removal (the second form used in the real mapping.txt)
            assertConverted(sponge("minecraft:spawner"), "minecraft:mob_spawner -> minecraft:air\n", 0, 0);
        }

        @Test
        void spongeNamespaceDataToNamespaceData() throws Exception {
            // minecraft:stone[data=1] -> uptodate:stone[data=1] on a flattened granite
            loadLevelDat(Map.of("uptodate:stone", 15137));
            assertConverted(sponge("minecraft:granite"), "minecraft:stone[data=1] -> uptodate:stone[data=1]\n", 15137, 1);
        }
    }

    // ================= mixed geometry =================

    @Nested
    class Geometry {
        @Test
        void blockPositionsPreservedInYzxOrder() throws Exception {
            // 2x2x2 sponge schem with a marker in each corner, YZX index = (y*L+z)*W+x
            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put("minecraft:air", 0);
            palette.put("minecraft:stone", 1);
            palette.put("minecraft:oak_planks", 2);

            // stone at (x=1, z=0, y=0) -> index 1; planks at (x=0, z=1, y=1) -> index (1*2+1)*2+0 = 6
            int[] indices = {0, 1, 0, 0, 0, 0, 2, 0};
            Path input = writeSponge(DV_1_13_2, 2, 2, 2, palette, indices);

            SchematicData result = SchematicConverter.read(input);
            assertEquals(1, result.getBlockIds()[1], "stone must stay at (1,0,0)");
            assertEquals(5, result.getBlockIds()[6], "planks must stay at (0,1,1)");
            assertEquals(0, result.getBlockIds()[0]);
            assertEquals(0, result.getBlockIds()[7]);
        }

        @Test
        void repeatedPaletteEntriesShareResolution() throws Exception {
            // Many blocks referencing the same palette entry all resolve identically
            LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
            palette.put("minecraft:red_wool", 0);
            int[] indices = new int[64];
            Path input = writeSponge(DV_1_13_2, 4, 4, 4, palette, indices);

            SchematicData result = SchematicConverter.read(input);
            for (int i = 0; i < 64; i++) {
                assertEquals(35, result.getBlockIds()[i]);
                assertEquals(14, result.getBlockData()[i]);
            }
        }
    }
}
