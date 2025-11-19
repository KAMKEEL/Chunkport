package com.hivemc.chunker.schematic;

import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.parser.SimpleMappingsParser;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.array.ByteArrayTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.ShortTag;
import org.junit.jupiter.api.Test;

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
}
