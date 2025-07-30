package com.hivemc.chunker.conversion.java.resolver.legacy;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyBlockIDResolver;
import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaLegacyBlockIDResolverLevelConvertTest {
    @Test
    public void testReverseLookupWithLevelConvert() throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag fml = new CompoundTag();
        root.put("FML", fml);
        CompoundTag itemData = new CompoundTag();
        fml.put("ItemData", itemData);
        itemData.put("custommod:block", new IntTag(1300));

        File levelDat = File.createTempFile("level", ".dat");
        levelDat.deleteOnExit();
        Tag.writeGZipJavaNBT(levelDat, root);

        LevelConvertMappings.load(levelDat);

        JavaLegacyBlockIDResolver resolver = new JavaLegacyBlockIDResolver(new Version(1, 7, 10));
        Optional<String> result = resolver.to(1300);
        assertTrue(result.isPresent());
        assertEquals("custommod:block", result.get());
    }
}
