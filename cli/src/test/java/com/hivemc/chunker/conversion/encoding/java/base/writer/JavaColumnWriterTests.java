package com.hivemc.chunker.conversion.encoding.java.base.writer;

import com.hivemc.chunker.conversion.bedrock.resolver.MockConverter;
import com.hivemc.chunker.conversion.encoding.java.JavaDataVersion;
import com.hivemc.chunker.conversion.encoding.java.JavaEncoders;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.container.randomizable.ChestBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaColumnWriterTests {
    @TempDir
    Path tempDir;

    @Test
    void preProcessColumnDropsBlockEntitiesBelowLegacyWorldHeight() throws IOException {
        JavaColumnWriter columnWriter = createLegacyColumnWriter();
        ChunkerColumn column = createChestColumn(-25);

        columnWriter.preProcessColumn(column, new CompoundTag());

        assertTrue(column.getBlockEntities().isEmpty());
    }

    @Test
    void preProcessColumnKeepsBlockEntitiesInsideLegacyWorldHeight() throws IOException {
        JavaColumnWriter columnWriter = createLegacyColumnWriter();
        ChunkerColumn column = createChestColumn(64);

        columnWriter.preProcessColumn(column, new CompoundTag());

        assertEquals(1, column.getBlockEntities().size());
        assertEquals(64, column.getBlockEntities().get(0).getY());
    }

    private JavaColumnWriter createLegacyColumnWriter() throws IOException {
        MockConverter converter = new MockConverter(null);
        JavaEncoders.JavaEncoder encoder = JavaEncoders.getNearestEncoder(JavaDataVersion.V1_7_10);
        JavaLevelWriter levelWriter = encoder.writerConstructor().construct(tempDir.toFile(), JavaDataVersion.V1_7_10.getVersion(), converter);
        JavaWorldWriter worldWriter = levelWriter.createWorldWriter();
        return worldWriter.createColumnWriter(Dimension.OVERWORLD);
    }

    private ChunkerColumn createChestColumn(int y) {
        ChunkerColumn column = new ChunkerColumn(new ChunkCoordPair(6, -8));
        column.setBlock(100, y, -117, new ChunkerBlockIdentifier(ChunkerVanillaBlockType.CHEST));

        ChestBlockEntity chest = new ChestBlockEntity();
        chest.setX(100);
        chest.setY(y);
        chest.setZ(-117);
        column.getBlockEntities().add(chest);
        return column;
    }
}
