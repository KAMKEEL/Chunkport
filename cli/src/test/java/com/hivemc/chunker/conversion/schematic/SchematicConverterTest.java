package com.hivemc.chunker.conversion.schematic;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.array.ByteArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SchematicConverterTest {

    @TempDir
    Path tempDir;

    @Test
    void convertsSpongeExtensionToLegacySchematic() throws Exception {
        CompoundTag input = new CompoundTag();
        input.put("Width", (short) 1);
        input.put("Height", (short) 1);
        input.put("Length", (short) 1);
        input.put("Blocks", new ByteArrayTag(new byte[]{1}));
        input.put("Data", new ByteArrayTag(new byte[]{0}));

        File source = tempDir.resolve("input.schem").toFile();
        File target = tempDir.resolve("output.schematic").toFile();
        Files.write(source.toPath(), Tag.writeGZipJavaNBT(input));

        SchematicConverter converter = new SchematicConverter();
        converter.convert(source, target);

        CompoundTag result = converter.readRawSchematic(target);
        assertNotNull(result);
        assertEquals("Alpha", result.getString("Materials"));
        assertArrayEquals(new byte[]{1}, result.getByteArray("Blocks"));
    }

    @Test
    void preservesExistingMaterialsWhenPresent() throws Exception {
        CompoundTag input = new CompoundTag();
        input.put("Materials", "Alpha");
        input.put("Width", (short) 1);
        input.put("Height", (short) 1);
        input.put("Length", (short) 1);
        input.put("Blocks", new ByteArrayTag(new byte[]{5}));
        input.put("Data", new ByteArrayTag(new byte[]{0}));

        File source = tempDir.resolve("input.schematic").toFile();
        File target = tempDir.resolve("output.schematic").toFile();
        Files.write(source.toPath(), Tag.writeGZipJavaNBT(input));

        SchematicConverter converter = new SchematicConverter();
        converter.convert(source, target);

        CompoundTag result = converter.readRawSchematic(target);
        assertNotNull(result);
        assertEquals("Alpha", result.getString("Materials"));
        assertArrayEquals(new byte[]{5}, result.getByteArray("Blocks"));
    }
}
