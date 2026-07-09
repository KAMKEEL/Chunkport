package com.hivemc.chunker.schematic;

import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.parser.SimpleMappingsParser;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.array.ByteArrayTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.ShortTag;
import com.hivemc.chunker.nbt.tags.TagWithName;
import com.hivemc.chunker.nbt.io.Reader;
import com.hivemc.chunker.nbt.io.Writer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SchematicConverterTests {

    @Test
    void writesAndReadsExtendedIds() throws Exception {
        Path tempOut = Files.createTempFile("schematic", ".schematic");
        SchematicData data = new SchematicData((short) 1, (short) 1, (short) 1, new int[]{5000}, new int[]{32});

        SchematicConverter.writeClassic(tempOut, data, true);

        SchematicData roundTrip = SchematicConverter.read(tempOut);
        assertEquals(5000, roundTrip.getBlockIds()[0]);
        assertEquals(32, roundTrip.getBlockData()[0]);
    }

    @Test
    void convertsSpongePaletteUsingLevelDat() throws Exception {
        CompoundTag palette = new CompoundTag();
        palette.put("custom:block", new IntTag(0));

        CompoundTag spongeRoot = new CompoundTag();
        spongeRoot.put("Width", new ShortTag((short) 1));
        spongeRoot.put("Height", new ShortTag((short) 1));
        spongeRoot.put("Length", new ShortTag((short) 1));
        spongeRoot.put("Palette", palette);
        spongeRoot.put("PaletteMax", new IntTag(1));
        spongeRoot.put("BlockData", new ByteArrayTag(new byte[]{0}));

        Path input = Files.createTempFile("sponge", ".schem");
        Tag.writeGZipJavaNBT(input.toFile(), spongeRoot);

        CompoundTag level = new CompoundTag();
        CompoundTag forge = new CompoundTag();
        CompoundTag itemData = new CompoundTag();
        itemData.put("custom:block", new IntTag(1300));
        forge.put("ItemData", itemData);
        level.put("FML", forge);

        File levelDat = File.createTempFile("level", ".dat");
        Tag.writeGZipJavaNBT(levelDat, level);
        LevelConvertMappings.load(levelDat);

        SchematicData loaded = SchematicConverter.read(input);
        assertEquals(1300, loaded.getBlockIds()[0]);

        Path output = Files.createTempFile("converted", ".schematic");
        SchematicConverter.convert(input.toFile(), output.toFile(), true);
        assertTrue(output.toFile().exists());
    }

    @Test
    void appliesSimpleMappingsWithLevelDat() throws Exception {
        // Prepare a legacy schematic with a single custom block id
        SchematicData data = new SchematicData((short) 1, (short) 1, (short) 1, new int[]{1300}, new int[]{0});
        Path input = Files.createTempFile("legacy", ".schematic");
        SchematicConverter.writeClassic(input, data, true);

        // Level.dat that provides both old and new identifiers
        CompoundTag level = new CompoundTag();
        CompoundTag forge = new CompoundTag();
        CompoundTag itemData = new CompoundTag();
        itemData.put("custom:block", new IntTag(1300));
        itemData.put("custom:other", new IntTag(2000));
        forge.put("ItemData", itemData);
        level.put("FML", forge);

        File levelDat = File.createTempFile("level", ".dat");
        Tag.writeGZipJavaNBT(levelDat, level);
        LevelConvertMappings.load(levelDat);

        // Simple mapping to redirect to the new identifier
        Path mappingFile = Files.createTempFile("simple", ".txt");
        Files.writeString(mappingFile, "custom:block -> custom:other\n");
        MappingsFile mappings = SimpleMappingsParser.parse(mappingFile);

        Path output = Files.createTempFile("converted", ".schematic");
        SchematicConverter.convert(input.toFile(), output.toFile(), true, mappings, true);

        SchematicData converted = SchematicConverter.read(output);
        assertEquals(2000, converted.getBlockIds()[0]);
    }

    @Test
    void appliesMappingsUsingLevelDatIdentifiers() throws Exception {
        // Level.dat that supplies IDs for both source and target identifiers
        CompoundTag level = new CompoundTag();
        CompoundTag forge = new CompoundTag();
        CompoundTag itemData = new CompoundTag();
        itemData.put("minecraft:observer", new IntTag(5000));
        itemData.put("etfuturum:observer", new IntTag(6000));
        forge.put("ItemData", itemData);
        level.put("FML", forge);

        File levelDat = File.createTempFile("level", ".dat");
        Tag.writeGZipJavaNBT(levelDat, level);
        LevelConvertMappings.load(levelDat);

        // Schematic that uses the legacy ID from level.dat
        SchematicData data = new SchematicData((short) 1, (short) 1, (short) 1, new int[]{5000}, new int[]{5});
        Path input = Files.createTempFile("legacy-level", ".schematic");
        SchematicConverter.writeClassic(input, data, true);

        // Simple mapping redirects to the alternate identifier backed by level.dat
        Path mappingFile = Files.createTempFile("observer", ".txt");
        Files.writeString(mappingFile, "minecraft:observer -> etfuturum:observer\n");
        MappingsFile mappings = SimpleMappingsParser.parse(mappingFile);

        Path output = Files.createTempFile("converted-level", ".schematic");
        SchematicConverter.convert(input.toFile(), output.toFile(), true, mappings, true);

        SchematicData converted = SchematicConverter.read(output);
        assertEquals(6000, converted.getBlockIds()[0]);
        assertEquals(5, converted.getBlockData()[0]);
    }

    @Test
    void convertsDirectoriesOfSchematics() throws Exception {
        Path inputDir = Files.createTempDirectory("schem-input");
        Path nested = Files.createDirectories(inputDir.resolve("nested"));

        // Write a Sponge schematic in a nested folder
        CompoundTag palette = new CompoundTag();
        palette.put("minecraft:stone", new IntTag(0));
        CompoundTag spongeRoot = new CompoundTag();
        spongeRoot.put("Width", new ShortTag((short) 1));
        spongeRoot.put("Height", new ShortTag((short) 1));
        spongeRoot.put("Length", new ShortTag((short) 1));
        spongeRoot.put("Palette", palette);
        spongeRoot.put("PaletteMax", new IntTag(1));
        spongeRoot.put("BlockData", new ByteArrayTag(new byte[]{0}));
        Path spongeFile = nested.resolve("sample.schem");
        Tag.writeGZipJavaNBT(spongeFile.toFile(), spongeRoot);

        // Write a classic schematic alongside
        Path classicFile = inputDir.resolve("basic.schematic");
        SchematicData data = new SchematicData((short) 1, (short) 1, (short) 1, new int[]{1}, new int[]{0});
        SchematicConverter.writeClassic(classicFile, data, true);

        Path outputDir = Files.createTempDirectory("schem-output");
        int count = SchematicConverter.convertDirectory(inputDir, outputDir, true, null, false);

        assertEquals(2, count);
        assertTrue(Files.exists(outputDir.resolve("basic.schematic")));
        assertTrue(Files.exists(outputDir.resolve("nested/sample.schematic")));
    }

    @Test
    void readsSchematicsLargerThanChunkArrayLimit() throws Exception {
        // 64x32x64 = 131072 blocks, above the 65536 NBT chunk array limit
        int volume = 64 * 32 * 64;
        int[] ids = new int[volume];
        int[] meta = new int[volume];
        java.util.Arrays.fill(ids, 1);

        Path output = Files.createTempFile("large", ".schematic");
        SchematicData data = new SchematicData((short) 64, (short) 32, (short) 64, ids, meta);
        SchematicConverter.writeClassic(output, data, true);

        SchematicData roundTrip = SchematicConverter.read(output);
        assertEquals(volume, roundTrip.getBlockIds().length);
        assertEquals(1, roundTrip.getBlockIds()[volume - 1]);
    }

    @Test
    void decodesVarintPalettesAbove127Entries() throws Exception {
        // Palettes above 127 entries use multi-byte varints in BlockData
        int paletteCount = 130;
        CompoundTag palette = new CompoundTag();
        CompoundTag itemData = new CompoundTag();
        for (int i = 0; i < paletteCount; i++) {
            palette.put("varinttest:block" + i, new IntTag(i));
            itemData.put("varinttest:block" + i, new IntTag(1000 + i));
        }

        CompoundTag forge = new CompoundTag();
        forge.put("ItemData", itemData);
        CompoundTag level = new CompoundTag();
        level.put("FML", forge);
        File levelDat = File.createTempFile("level", ".dat");
        Tag.writeGZipJavaNBT(levelDat, level);
        LevelConvertMappings.load(levelDat);

        // One block per palette entry, varint encoded
        ByteArrayOutputStream blockData = new ByteArrayOutputStream();
        for (int i = 0; i < paletteCount; i++) {
            int value = i;
            while ((value & ~0x7F) != 0) {
                blockData.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            blockData.write(value);
        }

        CompoundTag spongeRoot = new CompoundTag();
        spongeRoot.put("Width", new ShortTag((short) paletteCount));
        spongeRoot.put("Height", new ShortTag((short) 1));
        spongeRoot.put("Length", new ShortTag((short) 1));
        spongeRoot.put("Palette", palette);
        spongeRoot.put("PaletteMax", new IntTag(paletteCount));
        spongeRoot.put("BlockData", new ByteArrayTag(blockData.toByteArray()));

        Path input = Files.createTempFile("varint", ".schem");
        Tag.writeGZipJavaNBT(input.toFile(), spongeRoot);

        SchematicData loaded = SchematicConverter.read(input);
        assertEquals(1000, loaded.getBlockIds()[0]);
        assertEquals(1000 + 127, loaded.getBlockIds()[127]);
        assertEquals(1000 + 128, loaded.getBlockIds()[128]);
        assertEquals(1000 + 129, loaded.getBlockIds()[129]);
    }

    @Test
    void readsSpongeV3NestedFormat() throws Exception {
        CompoundTag palette = new CompoundTag();
        palette.put("minecraft:stone", new IntTag(0));

        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette);
        blocks.put("Data", new ByteArrayTag(new byte[]{0}));

        CompoundTag schematic = new CompoundTag();
        schematic.put("Version", new IntTag(3));
        schematic.put("DataVersion", new IntTag(3700));
        schematic.put("Width", new ShortTag((short) 1));
        schematic.put("Height", new ShortTag((short) 1));
        schematic.put("Length", new ShortTag((short) 1));
        schematic.put("Blocks", blocks);

        CompoundTag root = new CompoundTag();
        root.put("Schematic", schematic);

        Path input = Files.createTempFile("spongev3", ".schem");
        Tag.writeGZipJavaNBT(input.toFile(), root);

        SchematicData loaded = SchematicConverter.read(input);
        assertEquals(1, loaded.getBlockIds().length);
        assertEquals(1, loaded.getBlockIds()[0]);
    }

    @Test
    void readsUncompressedSpongeSchematic() throws Exception {
        CompoundTag palette = new CompoundTag();
        palette.put("minecraft:stone", new IntTag(0));

        CompoundTag spongeRoot = new CompoundTag();
        spongeRoot.put("Width", new ShortTag((short) 1));
        spongeRoot.put("Height", new ShortTag((short) 1));
        spongeRoot.put("Length", new ShortTag((short) 1));
        spongeRoot.put("Palette", palette);
        spongeRoot.put("PaletteMax", new IntTag(1));
        spongeRoot.put("BlockData", new ByteArrayTag(new byte[]{0}));

        Path input = Files.createTempFile("sponge-plain", ".schem");
        try (DataOutputStream outputStream = new DataOutputStream(Files.newOutputStream(input))) {
            Tag.encodeNamed(Writer.toJavaWriter(outputStream), "", spongeRoot);
        }

        SchematicData loaded = SchematicConverter.read(input);
        assertEquals(1, loaded.getBlockIds().length);
        assertEquals(1, loaded.getBlockIds()[0]);
    }

    @Test
    void resolvesFlattenedIdentifiersWithBlockStates() throws Exception {
        // 1.13+ flattened names with properties should keep orientation data
        CompoundTag palette = new CompoundTag();
        palette.put("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", new IntTag(0));
        palette.put("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]", new IntTag(1));

        CompoundTag spongeRoot = new CompoundTag();
        spongeRoot.put("Version", new IntTag(2));
        spongeRoot.put("DataVersion", new IntTag(1631)); // 1.13.2
        spongeRoot.put("Width", new ShortTag((short) 2));
        spongeRoot.put("Height", new ShortTag((short) 1));
        spongeRoot.put("Length", new ShortTag((short) 1));
        spongeRoot.put("Palette", palette);
        spongeRoot.put("PaletteMax", new IntTag(2));
        spongeRoot.put("BlockData", new ByteArrayTag(new byte[]{0, 1}));

        Path input = Files.createTempFile("stairs", ".schem");
        Tag.writeGZipJavaNBT(input.toFile(), spongeRoot);

        SchematicData loaded = SchematicConverter.read(input);
        assertEquals(53, loaded.getBlockIds()[0]); // oak stairs
        assertEquals(53, loaded.getBlockIds()[1]);
        // Orientation must not be lost, the two entries face different ways
        assertNotEquals(loaded.getBlockData()[0], loaded.getBlockData()[1]);
    }

    @Test
    void preservesWorldEditOffsetFromClassicInput() throws Exception {
        // WEOffset/WEOrigin carry the paste anchor and must survive conversion
        SchematicData data = new SchematicData((short) 1, (short) 1, (short) 1,
                new int[]{1}, new int[]{0}, new int[]{-3, 0, 7}, new int[]{100, 64, -200});
        Path input = Files.createTempFile("offset", ".schematic");
        SchematicConverter.writeClassic(input, data, true);

        Path output = Files.createTempFile("offset-out", ".schematic");
        SchematicConverter.convert(input.toFile(), output.toFile(), true);

        SchematicData result = SchematicConverter.read(output);
        assertArrayEquals(new int[]{-3, 0, 7}, result.getOffset());
        assertArrayEquals(new int[]{100, 64, -200}, result.getOrigin());
    }

    @Test
    void preservesSpongeOffsetAsWorldEditOffset() throws Exception {
        // Sponge "Offset" int[3] becomes WEOffsetX/Y/Z in the classic output
        CompoundTag palette = new CompoundTag();
        palette.put("minecraft:stone", new IntTag(0));

        CompoundTag spongeRoot = new CompoundTag();
        spongeRoot.put("Width", new ShortTag((short) 1));
        spongeRoot.put("Height", new ShortTag((short) 1));
        spongeRoot.put("Length", new ShortTag((short) 1));
        spongeRoot.put("Palette", palette);
        spongeRoot.put("PaletteMax", new IntTag(1));
        spongeRoot.put("BlockData", new ByteArrayTag(new byte[]{0}));
        spongeRoot.put("Offset", new com.hivemc.chunker.nbt.tags.array.IntArrayTag(new int[]{-5, -1, 12}));

        Path input = Files.createTempFile("sponge-offset", ".schem");
        Tag.writeGZipJavaNBT(input.toFile(), spongeRoot);

        Path output = Files.createTempFile("sponge-offset-out", ".schematic");
        SchematicConverter.convert(input.toFile(), output.toFile(), true);

        SchematicData result = SchematicConverter.read(output);
        assertArrayEquals(new int[]{-5, -1, 12}, result.getOffset());
        assertNull(result.getOrigin());
    }

    @Test
    void rejectsClassicSchematicWithTruncatedArrays() throws Exception {
        // Blocks/Data shorter than Width*Height*Length must fail at read time
        CompoundTag root = new CompoundTag();
        root.put("Width", new ShortTag((short) 2));
        root.put("Height", new ShortTag((short) 2));
        root.put("Length", new ShortTag((short) 2));
        root.put("Materials", new com.hivemc.chunker.nbt.tags.primitive.StringTag("Alpha"));
        root.put("Blocks", new ByteArrayTag(new byte[4]));
        root.put("Data", new ByteArrayTag(new byte[4]));

        Path input = Files.createTempFile("truncated", ".schematic");
        Tag.writeGZipJavaNBT(input.toFile(), root);

        assertThrows(java.io.IOException.class, () -> SchematicConverter.read(input));
    }

    @Test
    void writesToParentlessRelativePath() throws Exception {
        // Output paths without a parent directory must not NPE
        Path cwdFile = Path.of("parentless-test-output.schematic");
        try {
            SchematicData data = new SchematicData((short) 1, (short) 1, (short) 1, new int[]{1}, new int[]{0});
            SchematicConverter.writeClassic(cwdFile, data, true);
            assertTrue(Files.exists(cwdFile));
        } finally {
            Files.deleteIfExists(cwdFile);
        }
    }

    @Test
    void writesSchematicRootName() throws Exception {
        Path output = Files.createTempFile("named", ".schematic");
        SchematicData data = new SchematicData((short) 1, (short) 1, (short) 1, new int[]{1}, new int[]{0});

        SchematicConverter.writeClassic(output, data, true);

        try (var inputStream = Files.newInputStream(output);
             var gzipInputStream = new java.util.zip.GZIPInputStream(inputStream);
             var dataInputStream = new java.io.DataInputStream(gzipInputStream)) {
            TagWithName<CompoundTag> decoded = Tag.decodeNamed(Reader.toJavaReader(dataInputStream), CompoundTag.class);
            assertEquals("Schematic", decoded.name());
            assertEquals(1, decoded.tag().getShort("Width", (short) -1));
        }
    }
}
