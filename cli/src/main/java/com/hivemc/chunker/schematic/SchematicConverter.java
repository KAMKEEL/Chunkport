package com.hivemc.chunker.schematic;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.java.JavaDataVersion;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.JavaBlockIdentifierResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyBlockIDResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyBlockIdentifierResolver;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.identifier.Identifier;
import com.hivemc.chunker.mapping.identifier.states.StateValue;
import com.hivemc.chunker.mapping.identifier.states.StateValueString;
import com.hivemc.chunker.mapping.resolver.MappingsFileResolvers;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.io.Reader;
import com.hivemc.chunker.nbt.io.Writer;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.TagWithName;
import com.hivemc.chunker.nbt.tags.array.ByteArrayTag;
import com.hivemc.chunker.nbt.tags.array.IntArrayTag;
import com.hivemc.chunker.nbt.tags.array.LongArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.ShortTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal converter capable of reading Sponge .schem files (v1/v2/v3) or classic
 * WorldEdit .schematic files and writing them back to the classic schematic
 * format used by WorldEdit GTNH (1.7.10) with support for extended block IDs.
 */
public final class SchematicConverter {
    private static final String SCHEMATIC_ROOT_NAME = "Schematic";
    /** Version used to interpret numeric block IDs found in classic schematics. */
    private static final Version LEGACY_INPUT_VERSION = new Version(1, 12, 2);
    /** Version schematics are written for. */
    private static final Version TARGET_VERSION = new Version(1, 7, 10);
    /** Version assumed for Sponge schematics without a DataVersion (WorldEdit 1.13 era). */
    private static final Version DEFAULT_SPONGE_VERSION = new Version(1, 13, 2);
    /** First DataVersion using flattened identifiers (1.13). */
    private static final int FLATTENING_DATA_VERSION = 1519;

    /** Resolves 1.12-era numeric IDs to identifiers for classic schematic inputs. */
    private static final JavaLegacyBlockIDResolver LEGACY_BLOCK_RESOLVER = new JavaLegacyBlockIDResolver(LEGACY_INPUT_VERSION);
    /** Resolves identifiers to 1.7.10 numeric IDs for the written schematic. */
    private static final JavaLegacyBlockIDResolver TARGET_BLOCK_ID_RESOLVER = new JavaLegacyBlockIDResolver(TARGET_VERSION);

    // Schematics store entire builds in single NBT arrays, so the chunk-sized decode
    // limits need to be raised while reading them.
    private static final int SCHEMATIC_MAX_BYTE_ARRAY_LENGTH = 1 << 30; // 1GB of bytes
    private static final int SCHEMATIC_MAX_INT_ARRAY_LENGTH = 1 << 28; // 1GB of ints
    private static final int SCHEMATIC_MAX_LONG_ARRAY_LENGTH = 1 << 27; // 1GB of longs

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

    public static void convert(File input, File output, boolean allowNeids, @Nullable MappingsFile mappingsFile, boolean legacySimpleMappings) throws IOException {
        convert(input, output, allowNeids, new ResolutionContext(mappingsFile, legacySimpleMappings));
    }

    private static void convert(File input, File output, boolean allowNeids, ResolutionContext context) throws IOException {
        ReadResult result = readInternal(input.toPath(), context);
        SchematicData data = result.data();
        // Sponge inputs already apply mappings while resolving the palette, classic
        // inputs apply them per block after reading.
        if (!result.sponge() && context.mappingsFile != null) {
            data = applyMappings(data, context.mappingsFile, context.legacySimpleMappings);
        }
        writeClassic(output.toPath(), data, allowNeids);
    }

    /**
     * Convert all schematics found under the input directory into the output
     * directory, preserving relative paths and writing results with a
     * <code>.schematic</code> extension.
     *
     * @param inputDirectory  root directory to scan for schematics
     * @param outputDirectory directory to emit converted schematics into
     * @param allowNeids      whether NEIDs encoding should be emitted when needed
     * @param mappingsFile    optional mappings file used during conversion
     * @param legacySimpleMappings whether legacy simple mappings are enabled
     * @return number of schematics converted
     * @throws IOException if reading or writing fails
     */
    public static int convertDirectory(Path inputDirectory, Path outputDirectory, boolean allowNeids, @Nullable MappingsFile mappingsFile, boolean legacySimpleMappings) throws IOException {
        Files.createDirectories(outputDirectory);

        List<Path> schematics;
        try (Stream<Path> paths = Files.walk(inputDirectory)) {
            schematics = paths
                    .filter(Files::isRegularFile)
                    .filter(SchematicConverter::isSchematicFile)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        // Share a single resolution context (and its resolvers) across all files
        ResolutionContext context = new ResolutionContext(mappingsFile, legacySimpleMappings);

        int converted = 0;
        for (Path input : schematics) {
            Path relative = inputDirectory.relativize(input);
            String fileName = relative.getFileName().toString();
            String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            Path targetRelative = relative.resolveSibling(base + ".schematic");
            Path output = outputDirectory.resolve(targetRelative);
            Files.createDirectories(output.getParent());
            try {
                convert(input.toFile(), output.toFile(), allowNeids, context);
                converted++;
                System.out.println("Converted " + relative);
            } catch (Exception e) {
                System.err.println("Failed to convert " + relative + ": " + e.getMessage());
            }
        }

        return converted;
    }

    private static boolean isSchematicFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".schem") || name.endsWith(".schematic");
    }

    /**
     * Read a schematic from either Sponge .schem (v1/v2/v3) or classic .schematic.
     */
    public static SchematicData read(Path path) throws IOException {
        return readInternal(path, new ResolutionContext(null, false)).data();
    }

    private static ReadResult readInternal(Path path, ResolutionContext context) throws IOException {
        CompoundTag root = readRoot(path);
        if (root == null) {
            throw new IOException("Invalid schematic: no root tag present");
        }

        // Sponge v3 nests everything inside a "Schematic" compound
        Tag<?> nested = root.get("Schematic");
        if (nested instanceof CompoundTag nestedCompound) {
            root = nestedCompound;
        }

        // Sponge v3 groups block information inside a "Blocks" compound
        Tag<?> blocksTag = root.get("Blocks");
        if (blocksTag instanceof CompoundTag blocksCompound && blocksCompound.contains("Palette")) {
            return new ReadResult(readSponge(path, root, blocksCompound.getCompound("Palette"), blocksCompound.getByteArray("Data"), context), true);
        }

        // Sponge v1/v2 keeps Palette/BlockData on the root
        if (root.contains("Palette") && root.contains("BlockData")) {
            return new ReadResult(readSponge(path, root, root.getCompound("Palette"), root.getByteArray("BlockData"), context), true);
        }
        return new ReadResult(readClassic(root), false);
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
        // Raise the NBT array limits while reading, schematics store the whole build
        // in single arrays which vastly exceed the chunk-sized defaults.
        ByteArrayTag.setMaxDecodeLength(SCHEMATIC_MAX_BYTE_ARRAY_LENGTH);
        IntArrayTag.setMaxDecodeLength(SCHEMATIC_MAX_INT_ARRAY_LENGTH);
        LongArrayTag.setMaxDecodeLength(SCHEMATIC_MAX_LONG_ARRAY_LENGTH);
        try (InputStream input = Files.newInputStream(path);
             BufferedInputStream buffered = new BufferedInputStream(input);
             GZIPInputStream gzip = new GZIPInputStream(buffered);
             DataInputStream dataInputStream = new DataInputStream(gzip)) {
            TagWithName<CompoundTag> pair = Tag.decodeNamed(Reader.toJavaReader(dataInputStream), CompoundTag.class);
            return pair.tag();
        } finally {
            ByteArrayTag.resetMaxDecodeLength();
            IntArrayTag.resetMaxDecodeLength();
            LongArrayTag.resetMaxDecodeLength();
        }
    }

    private static SchematicData readSponge(Path path, CompoundTag root, CompoundTag paletteTag, byte[] blockData, ResolutionContext context) throws IOException {
        short width = root.getShort("Width", (short) -1);
        short height = root.getShort("Height", (short) -1);
        short length = root.getShort("Length", (short) -1);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("Invalid schematic dimensions");
        }

        long volumeLong = (long) width * (long) height * (long) length;
        if (volumeLong > Integer.MAX_VALUE) {
            throw new IOException("Schematic too large: " + volumeLong + " blocks");
        }
        int volume = (int) volumeLong;

        // BlockData is a varint array with one entry per block (YZX order)
        int[] paletteIndices = decodeVarIntArray(blockData, volume);

        // Determine the Java version the palette identifiers belong to
        int dataVersion = root.getInt("DataVersion", -1);
        Version version;
        if (dataVersion >= FLATTENING_DATA_VERSION) {
            version = JavaDataVersion.getNearestVersion(dataVersion).getVersion();
        } else {
            if (dataVersion >= 0) {
                System.err.println("[warn] " + path.getFileName() + ": DataVersion " + dataVersion + " is pre-1.13, treating palette as 1.13.2 identifiers");
            }
            version = DEFAULT_SPONGE_VERSION;
        }

        // Resolve every palette entry to a 1.7.10 block ID + data value
        int paletteSize = 0;
        for (Map.Entry<String, Tag<?>> entry : paletteTag.getValue().entrySet()) {
            if (entry.getValue() instanceof IntTag indexTag) {
                paletteSize = Math.max(paletteSize, indexTag.getValue() + 1);
            }
        }

        int[] paletteIds = new int[paletteSize];
        int[] paletteMeta = new int[paletteSize];
        List<String> unmapped = new ArrayList<>();
        for (Map.Entry<String, Tag<?>> entry : paletteTag.getValue().entrySet()) {
            if (!(entry.getValue() instanceof IntTag indexTag)) continue;
            int index = indexTag.getValue();
            if (index < 0 || index >= paletteSize) continue;

            BlockResolution resolution = resolvePaletteEntry(entry.getKey(), version, context);
            if (resolution != null) {
                paletteIds[index] = resolution.blockId();
                paletteMeta[index] = resolution.data();
            } else {
                paletteIds[index] = 0;
                paletteMeta[index] = 0;
                unmapped.add(entry.getKey());
            }
        }

        if (!unmapped.isEmpty()) {
            System.err.println("[warn] " + path.getFileName() + ": " + unmapped.size() + " palette entries could not be mapped (converted to air):");
            for (String name : unmapped) {
                System.err.println("[warn]   " + name);
            }
        }

        int[] blockIds = new int[volume];
        int[] meta = new int[volume];
        for (int i = 0; i < volume; i++) {
            int paletteIndex = paletteIndices[i];
            if (paletteIndex < 0 || paletteIndex >= paletteSize) {
                throw new IOException("BlockData references palette index " + paletteIndex + " outside palette size " + paletteSize);
            }
            blockIds[i] = paletteIds[paletteIndex];
            meta[i] = paletteMeta[paletteIndex];
        }

        return new SchematicData(width, height, length, blockIds, meta);
    }

    /**
     * Decode a Sponge BlockData varint array into palette indices.
     *
     * @param data          the raw varint bytes.
     * @param expectedCount the expected number of entries (schematic volume).
     * @return an array of palette indices with one entry per block.
     * @throws IOException if the varints are malformed or the count mismatches.
     */
    private static int[] decodeVarIntArray(byte[] data, int expectedCount) throws IOException {
        int[] out = new int[expectedCount];
        int count = 0;
        int i = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (i >= data.length) {
                    throw new IOException("Truncated varint in BlockData");
                }
                if (shift > 28) {
                    throw new IOException("Malformed varint in BlockData (too long)");
                }
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);

            if (count >= expectedCount) {
                throw new IOException("BlockData contains more entries than the schematic volume " + expectedCount);
            }
            out[count++] = value;
        }
        if (count != expectedCount) {
            throw new IOException("BlockData contains " + count + " entries but the schematic volume is " + expectedCount);
        }
        return out;
    }

    /**
     * Resolve a Sponge palette entry (e.g. "minecraft:oak_stairs[facing=east,half=bottom]")
     * to a legacy block ID and data value using the full Chunker pipeline:
     * modern identifier -> Chunker block -> 1.7.10 identifier (+ simple mappings) -> numeric ID.
     *
     * @param state   the palette blockstate string.
     * @param version the Java version of the palette identifiers.
     * @param context the shared resolution context.
     * @return the resolved block, or null when nothing could map it.
     */
    @Nullable
    private static BlockResolution resolvePaletteEntry(String state, Version version, ResolutionContext context) {
        Identifier parsed = parseBlockState(state);

        // 1) Full vanilla pipeline (handles flattened names + block state properties)
        Optional<ChunkerBlockIdentifier> chunker = context.modernResolver(version).to(parsed);
        if (chunker.isPresent()) {
            Optional<Identifier> legacy = context.legacyWriterResolver.from(chunker.get());
            if (legacy.isPresent()) {
                Identifier out = legacy.get();
                // When legacy simple mappings are inactive the resolver won't have applied
                // the mappings file, apply it here so behaviour matches the classic path.
                if (context.mappingsFile != null && !context.legacySimpleMappingsActive) {
                    OptionalInt data = out.getDataValue();
                    Identifier mappingInput = Identifier.fromData(out.getIdentifier(), data);
                    Optional<Identifier> mapped = context.mappingsFile.convertBlock(mappingInput);
                    if (mapped.isPresent()) {
                        Identifier converted = mapped.get();
                        Integer id = resolveTargetBlockId(converted.getIdentifier());
                        if (id != null) {
                            return new BlockResolution(id, converted.getDataValue().orElse(data.orElse(0)));
                        }
                    }
                }

                Integer id = resolveTargetBlockId(out.getIdentifier());
                if (id != null) {
                    return new BlockResolution(id, out.getDataValue().orElse(0));
                }
            }
        }

        // 2) Unknown to vanilla (e.g. modded blocks), try the mappings file on the raw identifier
        if (context.mappingsFile != null) {
            Optional<Identifier> mapped = context.mappingsFile.convertBlock(new Identifier(parsed.getIdentifier()));
            if (mapped.isPresent()) {
                Integer id = resolveTargetBlockId(mapped.get().getIdentifier());
                if (id != null) {
                    return new BlockResolution(id, mapped.get().getDataValue().orElse(0));
                }
            }
        }

        // 3) Direct lookup, the identifier may exist in level.dat under the same name
        Integer direct = resolveTargetBlockId(parsed.getIdentifier());
        if (direct != null) {
            return new BlockResolution(direct, 0);
        }
        return null;
    }

    /**
     * Parse a blockstate string such as "minecraft:oak_stairs[facing=east,half=bottom]"
     * into an Identifier with string state values.
     */
    private static Identifier parseBlockState(String input) {
        String trimmed = input.trim();
        int bracket = trimmed.indexOf('[');
        String name = bracket == -1 ? trimmed : trimmed.substring(0, bracket);
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        if (bracket == -1) {
            return new Identifier(name);
        }

        int end = trimmed.lastIndexOf(']');
        if (end <= bracket) {
            return new Identifier(name);
        }

        Map<String, StateValue<?>> states = new Object2ObjectOpenHashMap<>();
        String stateString = trimmed.substring(bracket + 1, end);
        for (String pair : stateString.split(",")) {
            int equals = pair.indexOf('=');
            if (equals == -1) continue;
            states.put(pair.substring(0, equals).trim(), new StateValueString(pair.substring(equals + 1).trim()));
        }
        return new Identifier(name, states);
    }

    private static SchematicData applyMappings(SchematicData data, MappingsFile mappingsFile, boolean legacySimpleMappings) {
        int[] ids = Arrays.copyOf(data.getBlockIds(), data.getBlockIds().length);
        int[] meta = Arrays.copyOf(data.getBlockData(), data.getBlockData().length);

        for (int i = 0; i < ids.length; i++) {
            String identifier = resolveIdentifierFromMappings(ids[i]);
            if (identifier == null) {
                continue;
            }

            final int index = i;
            final int currentMeta = meta[i];
            OptionalInt metaValue = legacySimpleMappings ? OptionalInt.of(currentMeta) : OptionalInt.empty();
            Identifier input = Identifier.fromData(identifier, metaValue);
            mappingsFile.convertBlock(input).ifPresent(converted -> {
                Integer newId = resolveLegacyBlockId(converted.getIdentifier());
                if (newId != null) {
                    ids[index] = newId;
                    meta[index] = converted.getDataValue().orElse(currentMeta);
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

    private static Integer resolveLegacyBlockId(String identifier) {
        Integer levelId = LevelConvertMappings.getLegacyId(identifier);
        if (levelId != null) {
            return levelId;
        }
        return LEGACY_BLOCK_RESOLVER.from(identifier).orElse(null);
    }

    /**
     * Resolve an identifier to a numeric block ID for the written 1.7.10 schematic,
     * preferring level.dat mappings and falling back to vanilla 1.7.10 IDs.
     */
    private static Integer resolveTargetBlockId(String identifier) {
        Integer levelId = LevelConvertMappings.getLegacyId(identifier);
        if (levelId != null) {
            return levelId;
        }
        return TARGET_BLOCK_ID_RESOLVER.from(identifier).orElse(null);
    }

    private static String resolveIdentifierFromMappings(int id) {
        String identifier = LevelConvertMappings.getLegacyIdentifier(id);
        if (identifier != null) {
            return identifier;
        }
        return LEGACY_BLOCK_RESOLVER.to(id).orElse(null);
    }

    /** Result of reading a schematic, tracking whether it was a Sponge input. */
    private record ReadResult(SchematicData data, boolean sponge) {
    }

    /** A palette entry resolved to a legacy block ID and data value. */
    private record BlockResolution(int blockId, int data) {
    }

    /**
     * Shared state for a conversion run: the mappings file and the resolvers used
     * to translate identifiers between versions.
     */
    private static final class ResolutionContext {
        @Nullable
        final MappingsFile mappingsFile;
        final boolean legacySimpleMappings;
        final boolean legacySimpleMappingsActive;
        final WorldConverter converter;
        final JavaLegacyBlockIdentifierResolver legacyWriterResolver;
        final Map<Version, JavaBlockIdentifierResolver> modernResolvers = new HashMap<>();

        ResolutionContext(@Nullable MappingsFile mappingsFile, boolean legacySimpleMappings) {
            this.mappingsFile = mappingsFile;
            this.legacySimpleMappings = legacySimpleMappings;
            this.legacySimpleMappingsActive = legacySimpleMappings && mappingsFile != null;

            converter = new WorldConverter(UUID.randomUUID());
            if (mappingsFile != null) {
                converter.setBlockMappings(new MappingsFileResolvers(mappingsFile));
            }
            converter.setLegacySimpleMappings(legacySimpleMappingsActive);
            legacyWriterResolver = new JavaLegacyBlockIdentifierResolver(converter, TARGET_VERSION, false, false);
        }

        JavaBlockIdentifierResolver modernResolver(Version version) {
            return modernResolvers.computeIfAbsent(version, v -> new JavaBlockIdentifierResolver(converter, v, true, false));
        }
    }
}
