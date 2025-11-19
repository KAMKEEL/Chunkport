package com.hivemc.chunker.conversion.schematic;

import com.hivemc.chunker.nbt.io.Reader;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.TagWithName;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;

/**
 * Utilities for converting schematic files between Sponge (.schem) and
 * classic WorldEdit (.schematic) layouts. The converter keeps the existing
 * payload intact but ensures legacy consumers always receive a GZip encoded
 * NBT file with a Materials tag set to "Alpha" as expected by 1.7.10
 * WorldEdit forks.
 */
public class SchematicConverter {

    /**
     * Convert the supplied schematic file to the output path. If the input is
     * already a classic schematic, the payload is normalised and written
     * without altering block data. Sponge schematics (.schem) are treated as
     * NBT payloads and re-encoded with the legacy file extension so they can
     * be consumed by the GTNH WorldEdit fork.
     *
     * @param input  input schematic (.schem or .schematic)
     * @param output destination schematic (.schematic)
     * @throws IOException if the file cannot be read or written
     */
    public void convert(@NotNull File input, @NotNull File output) throws IOException {
        CompoundTag schematic = readRawSchematic(input);

        // Ensure legacy clients recognise the payload
        if (schematic.get("Materials") == null) {
            schematic.put("Materials", "Alpha");
        }

        if (output.getParentFile() != null) {
            Files.createDirectories(output.toPath().getParent());
        }

        byte[] encoded = Tag.writeGZipJavaNBT(schematic);
        Files.write(output.toPath(), encoded);
    }

    /**
     * Read a schematic NBT payload without stripping nested Data compounds so
     * schematics that legitimately contain a {@code Data} byte array remain
     * intact.
     *
     * @param file schematic file to read
     * @return parsed schematic root tag, never null
     * @throws IOException if the file cannot be decoded
     */
    public CompoundTag readRawSchematic(@NotNull File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedInputStream bis = new BufferedInputStream(gis);
             DataInputStream dis = new DataInputStream(bis)) {
            TagWithName<CompoundTag> named = Tag.decodeNamed(Reader.toJavaReader(dis), CompoundTag.class);
            return named == null ? new CompoundTag() : named.tag();
        }
    }
}
